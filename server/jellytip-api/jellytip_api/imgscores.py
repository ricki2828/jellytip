"""Read-only view of the realism scores written by jellytip-imgscore (data/imgscores.json)."""
import json
import os
import threading

from .stills import MAX_STILLS

HERO_MIN_DISTANCE = 16  # Hamming bits (of 64) a hero must differ from the card art by
# Titled-art quality (Thumb) below this: the card falls back to backdrop + logo. CLIP title score did NOT separate good from bad
# on a 36-thumb eyeballed sheet (clean art scored 0.00-0.03, covers/posters 0.05-0.12 and 0.9 alike), so gating is OFF (0.0) until a better signal exists.
TITLE_THRESHOLD = 0.0
HERO_MIN_LUM = 35.0  # the home hero skips murky frames darker than this (mean luminance 0-255) when a brighter candidate exists
EP_MIN_LAPLACIAN = 40.0  # episode stills: variance of the Laplacian below this is blurry (36 eyeballed: only the lowest, 29.6, was bad)
EP_LUM_RANGE = (10.0, 240.0)  # mean luminance outside this is a black/white frame
THRESHOLD = 0.6  # calibrated on a 60-image labelled sample: 27/27 precision on photo calls, see jellytip-imgscore/README.md


def hamming(a, b):
    return bin(int(a, 16) ^ int(b, 16)).count("1")


class ImgScores:
    def __init__(self, path, threshold=THRESHOLD):
        self.path, self.threshold = path, threshold
        self._mtime, self._data, self._lock = None, {"stills": {}, "items": {}}, threading.Lock()

    def _load(self):
        try:
            m = os.stat(self.path).st_mtime
        except OSError:
            return self._data
        with self._lock:
            if m != self._mtime:
                try:
                    with open(self.path, encoding="utf-8") as f:
                        self._data, self._mtime = json.load(f), m
                except (OSError, ValueError):
                    pass  # half-written/corrupt: keep the last good copy
        return self._data

    def rank_stills(self, stills, limit=MAX_STILLS):
        """photo >= threshold first, then unscored, then below threshold; stable within a tier
        (input is already textless-first, widest-first). Adds photoScore (null when unscored)."""
        sc = self._load()["stills"]

        def tier(s):
            v = s["photoScore"]
            return 1 if v is None else 0 if v >= self.threshold else 2
        out = [{**s, "photoScore": sc.get(s["url"])} for s in stills]
        out.sort(key=tier)
        return out[:limit]

    def backdrops(self, item_id, current_tags=None):
        """Jellyfin backdrop indices, best photo first. None when the item was never scored."""
        rec = self._load()["items"].get(item_id)
        if rec is None:
            return None
        rows = [{"index": b["i"], "tag": b.get("tag"), "photoScore": b["s"], "phash": b.get("h"), "focalX": b.get("fx"), "focalY": b.get("fy"),
                 "face": b.get("face"), "lum": b.get("lum")} for b in rec["backdrops"]]
        if not rec.get("anim"):  # animation keeps natural order; else same tiering as stills: photos, unscored, non-photos; best score first inside photos
            ph = sorted((r for r in rows if r["photoScore"] is not None and r["photoScore"] >= self.threshold), key=lambda r: -r["photoScore"])
            un = [r for r in rows if r["photoScore"] is None]
            no = [r for r in rows if r["photoScore"] is not None and r["photoScore"] < self.threshold]
            rows = ph + sorted(un, key=lambda r: r["index"]) + sorted(no, key=lambda r: -r["photoScore"])
        if current_tags is not None:  # Jellyfin's backdrop set changed since scoring: re-index by tag, drop vanished images
            rows = [{**r, "index": current_tags.index(r["tag"])} for r in rows if r["tag"] in current_tags]
        th = rec.get("thumb")
        thumb = (th or {}).get("h")
        good = th is not None and (th.get("s") is None or th["s"] >= TITLE_THRESHOLD)
        # The card shows the Thumb when it is good, else the top-ranked backdrop with the logo composited on it.
        hero, details = self._picks(rows, thumb if good else None)
        return {"itemId": item_id, "animation": bool(rec.get("anim")), "backdrops": rows, "thumbPhash": thumb,
                "thumb": {"score": th.get("s"), "phash": thumb, "good": good} if th else None,
                "heroIndex": hero, "detailsIndex": details}

    def episodes_art(self, parent_id):
        """Episode stills of a series or season: [{episodeId, score, usable}], usable = real photo, sharp, not black/white."""
        out = []
        for eid, e in self._load().get("episodes", {}).items():
            if parent_id in (e.get("series"), e.get("season")):
                out.append({"episodeId": eid, "score": e.get("s"), "usable": self._episode_usable(e)})
        return out

    def _episode_usable(self, e):
        if e.get("lap") is None:
            return False
        if e["lap"] < EP_MIN_LAPLACIAN or not EP_LUM_RANGE[0] <= e["lum"] <= EP_LUM_RANGE[1]:
            return False
        return e.get("anim") or e.get("s") is None or e["s"] >= self.threshold

    @staticmethod
    def _picks(rows, tile):
        """(hero, details) backdrop indices. Rows are best-photo-first; hero = best that looks different from the card art
        (tile = Thumb hash when the card uses it, else the top-ranked backdrop the card composite uses), details = the next
        one that also differs from the hero. None when there are no backdrops."""
        if not rows:
            return None, None
        ref = tile or rows[0]["phash"]
        if ref is None:  # nothing to compare against: just avoid the card's backdrop
            return (rows[1]["index"] if len(rows) > 1 else rows[0]["index"]), (rows[2]["index"] if len(rows) > 2 else rows[0]["index"])
        hashed = [r for r in rows if r["phash"] is not None]
        ok = [r for r in hashed if hamming(ref, r["phash"]) > HERO_MIN_DISTANCE]
        if ok:
            hero = next((r for r in ok if r["lum"] is None or r["lum"] >= HERO_MIN_LUM), ok[0])
        elif hashed:
            hero = max(hashed, key=lambda r: hamming(ref, r["phash"]))  # first of the most different (stable)
        else:
            return rows[0]["index"], rows[0]["index"]
        others = [r for r in ok if r is not hero]
        apart = [r for r in others if hamming(hero["phash"], r["phash"]) > HERO_MIN_DISTANCE]
        if apart:
            second = apart[0]
        elif others:
            second = others[0]
        else:
            rest = [r for r in hashed if r is not hero]
            second = max(rest, key=lambda r: hamming(ref, r["phash"])) if rest else hero
        return hero["index"], second["index"]
