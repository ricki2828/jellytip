"""Ricki's Letterboxd data, read from the export the Hetzner cron rsyncs here (never scraped per request)."""
import json
import os
import threading
import time

JF_MAP_TTL = 600


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
    def __init__(self, export_path, jellyfin_items=None, clock=time.time):
        self.path, self._jf_items, self._clock = export_path, jellyfin_items, clock
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

    def home_lists(self, min_items=6, limit=8):
        """Lists with enough films in the library to make a home row: [{slug, name, itemIds}] in export order."""
        with self._lock:
            self._load()
            lists = list(self._data["lists"])
        jf = self._jf_map()
        out = []
        for l in lists:
            ids = [jf[i["tmdb"]] for i in l["items"] if i.get("tmdb") in jf]
            if len(ids) >= min_items:
                out.append({"slug": l["slug"], "name": l["name"], "itemIds": ids[:50]})
        return out[:limit]
