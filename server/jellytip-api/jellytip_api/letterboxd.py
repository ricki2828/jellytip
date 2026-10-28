"""Ricki's Letterboxd data, read from the export the Hetzner cron rsyncs here (never scraped per request)."""
import concurrent.futures
import datetime
import json
import os
import threading
import time

JF_MAP_TTL = 600
DISCOVER_PER_ROW = 20


def map_jellyfin(items):
    """Jellyfin /Items payload -> {tmdbId(int): jellyfinId}."""
    out = {}
    for it in items or []:
        tmdb = (it.get("ProviderIds") or {}).get("Tmdb")
        if tmdb and str(tmdb).isdigit() and it.get("Id"):
            out.setdefault(int(tmdb), it["Id"])
    return out


def shape_list(lst, jf_by_tmdb):
    return {"name": lst["name"],
            "items": [{"tmdb": i.get("tmdb"), "title": i["title"], "year": i.get("year"),
                       "jellyfinId": jf_by_tmdb.get(i.get("tmdb"))} for i in lst["items"]]}


class Letterboxd:
    def __init__(self, export_path, jellyfin_items=None, clock=time.time, tmdb_images=None, posters_path=None):
        self.path, self._jf_items, self._clock = export_path, jellyfin_items, clock
        self._tmdb_images, self._posters_path = tmdb_images, posters_path
        try:
            with open(posters_path, encoding="utf-8") as f:
                self._posters = {k: tuple(v) for k, v in json.load(f).items()}
        except (OSError, TypeError, ValueError):
            self._posters = {}
        self._data, self._mtime = {"films": {}, "lists": []}, None
        self._jf, self._jf_at = {}, 0.0
        self._lock = threading.Lock()

    def _load(self):
        try:
            m = os.stat(self.path).st_mtime
        except OSError:
            return
        if m != self._mtime:
            with open(self.path, encoding="utf-8") as f:
                self._data, self._mtime = json.load(f), m

    def film(self, tmdb):
        """Contract `letterboxd` object, or None if Ricki hasn't logged it."""
        with self._lock:
            self._load()
            f = self._data["films"].get(str(tmdb))
        if not f:
            return None
        return {"myRating": f.get("rating"), "liked": bool(f.get("liked")),
                "watchedDates": f.get("watchedDates") or [], "rewatch": bool(f.get("rewatch")), "url": f.get("url")}

    def _jf_map(self):
        with self._lock:
            if self._clock() - self._jf_at < JF_MAP_TTL or not self._jf_items:
                return self._jf
        try:
            m = map_jellyfin(self._jf_items())
        except (OSError, ValueError):
            m = None
        with self._lock:
            if m is not None:
                self._jf = m
            self._jf_at = self._clock()  # on failure keep the stale map, retry after the TTL
            return self._jf

    def lists(self):
        with self._lock:
            self._load()
            return [{"slug": l["slug"], "name": l["name"], "count": len(l["items"])} for l in self._data["lists"]]

    def list(self, slug):
        with self._lock:
            self._load()
            lst = next((l for l in self._data["lists"] if l["slug"] == slug), None)
        return shape_list(lst, self._jf_map()) if lst else None

    def _poster_info(self, tmdb):
        """(posterUrl, backdropUrl) for a not-in-library film, cached for good; (None, None) when unknown."""
        with self._lock:
            hit = self._posters.get(str(tmdb))
        if hit is not None:
            return hit
        info = self._tmdb_images(tmdb) if self._tmdb_images else None
        if info is None:
            return (None, None)  # lookup failed: retry on a later request
        with self._lock:
            self._posters[str(tmdb)] = info
            if self._posters_path:
                try:
                    with open(self._posters_path, "w", encoding="utf-8") as f:
                        json.dump(self._posters, f)
                except OSError:
                    pass
        return info

    def home_lists(self, config=None, include_discover=False, today=None, min_items=3):
        """Today's Letterboxd home rows: [{slug, name, source, itemIds[, discover, items]}].

        `config` = {per_day, pinned, exclude}. Pinned lists (slug or name) come first; the remaining slots rotate
        through the other lists by date, so every list gets a turn and the set is stable within a day. Without
        `include_discover` only lists with >= min_items library films qualify (and rotation runs over those);
        with it every list qualifies and the list's other films follow as discover items with TMDB ids."""
        cfg = config or {}
        per_day = int(cfg.get("per_day") or 6)
        pinned, exclude = [str(x).lower() for x in cfg.get("pinned") or []], {str(x).lower() for x in cfg.get("exclude") or []}
        with self._lock:
            self._load()
            lists = list(self._data["lists"])
        jf = self._jf_map()
        rows = {}
        for l in sorted(lists, key=lambda l: l["slug"]):
            if l["slug"].lower() in exclude or l["name"].lower() in exclude:
                continue
            have = [jf[i["tmdb"]] for i in l["items"] if i.get("tmdb") in jf]
            if include_discover or len(have) >= min_items:
                rows[l["slug"]] = (l, have)
        pins = [slug for p in pinned for slug, (l, _) in rows.items() if p in (slug.lower(), l["name"].lower())]
        pins = list(dict.fromkeys(pins))[:per_day]
        day = (today or datetime.date.today()).toordinal()

        def rotate(pool, slots):
            slots = min(slots, len(pool))
            start = (day * slots) % len(pool) if pool else 0
            return [pool[(start + k) % len(pool)] for k in range(slots)]

        liked_pool = [s for s in rows if s not in pins and rows[s][0].get("liked")] if cfg.get("include_liked", True) else []
        own_pool = [s for s in rows if s not in pins and not rows[s][0].get("liked")]
        liked_pick = rotate(liked_pool, min(int(cfg.get("liked_weight", 2)), per_day - len(pins)))
        own_pick = rotate(own_pool, per_day - len(pins) - len(liked_pick))
        # the "at least liked_weight" floor, then any slots own lists could not fill go to more liked lists
        extra = per_day - len(pins) - len(liked_pick) - len(own_pick)
        more = rotate([s for s in liked_pool if s not in liked_pick], extra) if extra > 0 else []
        picked = pins + own_pick + liked_pick + more  # pinned first, then own lists, liked lists last
        out = []
        for slug in picked:
            l, have = rows[slug]
            row = {"slug": slug, "name": l["name"] + (f" (by {l['owner']})" if l.get("liked") else ""), "source": "letterboxd", "mediaType": "movie",
                   "itemIds": have[:50]}
            if l.get("liked"):
                row.update(liked=True, owner=l.get("owner"))
            if include_discover:
                missing = [i for i in l["items"] if i.get("tmdb") and i["tmdb"] not in jf][:DISCOVER_PER_ROW]
                with concurrent.futures.ThreadPoolExecutor(8) as pool:
                    imgs = list(pool.map(lambda i: self._poster_info(i["tmdb"]), missing))
                row["items"] = [{"discover": True, "tmdbId": i["tmdb"], "mediaType": "movie", "title": i["title"], "year": i.get("year"),
                                 "posterUrl": p, "backdropUrl": b} for i, (p, b) in zip(missing, imgs) if p]
            out.append(row)
        return out
