"""Read-only view of the realism scores written by jellytip-imgscore (data/imgscores.json)."""
import json
import os
import threading

from .stills import MAX_STILLS

HERO_MIN_DISTANCE = 12  # Hamming bits (of 64) a hero must differ from the card art by
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

    def backdrops(self, item_id):
        """Jellyfin backdrop indices, best photo first. None when the item was never scored."""
        rec = self._load()["items"].get(item_id)
        if rec is None:
            return None
        rows = [{"index": b["i"], "photoScore": b["s"], "phash": b.get("h")} for b in rec["backdrops"]]
        if not rec.get("anim"):  # animation keeps natural order; else same tiering as stills: photos, unscored, non-photos; best score first inside photos
            ph = sorted((r for r in rows if r["photoScore"] is not None and r["photoScore"] >= self.threshold), key=lambda r: -r["photoScore"])
            un = [r for r in rows if r["photoScore"] is None]
            no = [r for r in rows if r["photoScore"] is not None and r["photoScore"] < self.threshold]
            rows = ph + sorted(un, key=lambda r: r["index"]) + sorted(no, key=lambda r: -r["photoScore"])
        thumb = (rec.get("thumb") or {}).get("h")
        return {"itemId": item_id, "animation": bool(rec.get("anim")), "backdrops": rows, "thumbPhash": thumb,
                "heroIndex": self._hero(rows, thumb)}

    @staticmethod
    def _hero(rows, thumb):
        """Best-ranked backdrop that looks different from the card art: the Thumb when there is one, else the
        top-ranked backdrop (what the app's backdrop+logo composite uses). None when there are no backdrops."""
        if not rows:
            return None
        ref = thumb or rows[0]["phash"]
        if ref is None:  # nothing to compare against: just avoid the card's backdrop
            return rows[1]["index"] if len(rows) > 1 else rows[0]["index"]
        dist = [(hamming(ref, r["phash"]), n) for n, r in enumerate(rows) if r["phash"] is not None]
        for d, n in dist:
            if d > HERO_MIN_DISTANCE:
                return rows[n]["index"]
        if not dist:
            return rows[0]["index"]
        return rows[max(dist, key=lambda x: (x[0], -x[1]))[1]]["index"]
