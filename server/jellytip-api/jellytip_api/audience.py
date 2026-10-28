"""GET /v1/items/{id}/audience (admin only): who requested a title and who has watched it.

requestedBy: JSON export of the media-request bot's requests.db (rsynced here; the DB itself never leaves Hetzner).
watchedBy:   Plex history for every Plex account + Jellyfin played state for every Jellyfin user. Cached 1 h.
"""
import json
import os
import threading
import time
import urllib.parse
import urllib.request

TTL = 3600
# Plex account / Jellyfin user -> the friendly name the request bot uses. Only unambiguous ones; the rest keep their own name.
FRIENDLY = {"rickitaiaroa": "Ricki", "ricki28": "Ricki", "daniel taiaroa": "Daniel", "nadine taiaroa": "Nadine",
            "nadine.taiaroa@gmail.com": "Nadine", "richard.c304": "Richie", "ruchie19": "Richie", "gregandvic": "Greg", "dkt18": "Daniel", "gmccr7": "Greg"}


def friendly(name):
    return FRIENDLY.get((name or "").strip().lower(), (name or "").strip())


def merge_watched(rows):
    """One row per person: latest lastWatched wins, completed if any source says so."""
    out = {}
    for r in rows:
        cur = out.get(r["name"])
        if not cur:
            out[r["name"]] = dict(r)
            continue
        cur["completed"] = cur["completed"] or r["completed"]
        if (r["lastWatched"] or "") > (cur["lastWatched"] or ""):
            cur["lastWatched"], cur["source"] = r["lastWatched"], r["source"]
    return sorted(out.values(), key=lambda r: r["lastWatched"] or "", reverse=True)


def iso(ts):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(int(ts)))


class Plex:
    """Watch history indexed by external id. Rebuilt at most once an hour."""

    def __init__(self, url, token):
        self.url, self.token = (url or "").rstrip("/"), token
        self._lock, self._at, self._idx = threading.Lock(), 0.0, None

    @property
    def ok(self):
        return bool(self.url and self.token)

    def _get(self, path):
        req = urllib.request.Request(self.url + path, headers={"X-Plex-Token": self.token, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)["MediaContainer"]

    @staticmethod
    def _ids(meta):
        out = {}
        for g in meta.get("Guid") or []:
            k, _, v = (g.get("id") or "").partition("://")
            if k in ("tmdb", "tvdb", "imdb") and v:
                out[k] = v
        return out

    def _build(self):
        accounts = {a["id"]: a.get("name") or "" for a in self._get("/accounts").get("Account", [])}
        rk_ids, kinds = {}, {}
        for sec in self._get("/library/sections").get("Directory", []):
            if sec.get("type") not in ("movie", "show"):
                continue
            c = self._get(f"/library/sections/{sec['key']}/all?includeGuids=1")
            for m in c.get("Metadata", []):
                rk_ids[str(m["ratingKey"])] = self._ids(m)
                kinds[str(m["ratingKey"])] = sec["type"]
        movie, show, ep = {}, {}, {}
        hist = self._get("/status/sessions/history/all?X-Plex-Container-Start=0&X-Plex-Container-Size=20000")
        for h in hist.get("Metadata", []):
            who = friendly(accounts.get(h.get("accountID"), ""))
            if not who or not h.get("viewedAt"):
                continue
            ts = h["viewedAt"]
            if h.get("type") == "movie":
                ids, bucket = rk_ids.get(str(h.get("ratingKey")), {}), movie
                keys = [f"{k}:{v}" for k, v in ids.items()]
                for key in keys:
                    bucket.setdefault(key, {})[who] = max(ts, bucket.get(key, {}).get(who, 0))
            elif h.get("type") == "episode":
                grk = str(h.get("grandparentRatingKey") or (h.get("grandparentKey") or "").rsplit("/", 1)[-1])
                ids = rk_ids.get(grk, {})
                for k, v in ids.items():
                    key = f"{k}:{v}"
                    show.setdefault(key, {})[who] = max(ts, show.get(key, {}).get(who, 0))
                    ekey = (key, h.get("parentIndex"), h.get("index"))
                    ep.setdefault(ekey, {})[who] = max(ts, ep.get(ekey, {}).get(who, 0))
        return {"movie": movie, "show": show, "ep": ep}

    def index(self):
        with self._lock:
            if self._idx is None or time.time() - self._at > TTL:
                try:
                    self._idx, self._at = self._build(), time.time()
                except Exception as e:  # keep serving the stale index; retry next call
                    print("plex index failed", type(e).__name__, flush=True)
                    if self._idx is None:
                        self._idx, self._at = {"movie": {}, "show": {}, "ep": {}}, time.time() - TTL + 300
            return self._idx

    def watched(self, kind, ids, season=None, episode=None):
        idx = self.index()
        found = {}
        for k, v in ids.items():
            key = f"{k}:{v}"
            if kind == "Movie":
                src = idx["movie"].get(key, {})
            elif kind == "Episode":
                src = idx["ep"].get((key, season, episode), {})
            elif kind == "Season":
                src = {}
                for (kk, s, _), who in idx["ep"].items():
                    if kk == key and s == season:
                        for w, t in who.items():
                            src[w] = max(t, src.get(w, 0))
            else:
                src = idx["show"].get(key, {})
            for who, ts in src.items():
                found[who] = max(ts, found.get(who, 0))
        return [{"name": w, "lastWatched": iso(t), "source": "plex", "completed": kind in ("Movie", "Episode")}
                for w, t in found.items()]


class Audience:
    def __init__(self, svc, requests_path, plex):
        self.svc, self.requests_path, self.plex = svc, requests_path, plex
        self._cache, self._req, self._req_mtime = {}, {}, None

    def _requests(self):
        try:
            m = os.stat(self.requests_path).st_mtime
            if m != self._req_mtime:
                with open(self.requests_path, encoding="utf-8") as f:
                    self._req, self._req_mtime = json.load(f), m
        except (OSError, ValueError):
            pass
        return self._req

    def _requested(self, kind, ids):
        data = self._requests()
        rows = []
        if kind == "Movie":
            rows = data.get("movie", {}).get(str(ids.get("tmdb", "")), [])
        else:
            rows = data.get("tv", {}).get(str(ids.get("tvdb", "")), [])
        seen, out = set(), []
        for r in sorted(rows, key=lambda r: r["date"]):
            if r["name"] not in seen:  # one entry per person, their first request
                seen.add(r["name"])
                out.append({"name": r["name"], "date": r["date"]})
        return out

    def _jellyfin_watched(self, jid, kind):
        jf, rows = self.svc.jf, []
        for u in jf.call("GET", "/Users"):
            try:
                if kind in ("Movie", "Episode"):
                    d = jf.call("GET", f"/Users/{u['Id']}/Items/{jid}").get("UserData") or {}
                    if d.get("Played"):
                        rows.append((u["Name"], d.get("LastPlayedDate"), True))
                else:
                    d = jf.call("GET", f"/Users/{u['Id']}/Items", {
                        "Recursive": "true", "IncludeItemTypes": "Episode", "ParentId": jid, "IsPlayed": "true",
                        "SortBy": "DatePlayed", "SortOrder": "Descending", "Limit": 1, "Fields": "UserData"})
                    items = d.get("Items", [])
                    if items:
                        rows.append((u["Name"], (items[0].get("UserData") or {}).get("LastPlayedDate"), False))
            except Exception:
                continue
        return [{"name": friendly(n), "lastWatched": (t or "")[:19] + "Z" if t else None, "source": "jellyfin", "completed": c}
                for n, t, c in rows]

    def get(self, jid):
        """Returns (status, body) for a normalised 32-hex Jellyfin id."""
        hit = self._cache.get(jid)
        if hit and hit[0] > time.time():
            return 200, hit[1]
        jf = self.svc.jf
        try:
            it = jf.call("GET", f"/Items/{jid}", {"userId": jf.uid(), "Fields": "ProviderIds"})
        except Exception:
            return 404, {"error": "no such item"}
        kind = it.get("Type")
        if kind not in ("Movie", "Series", "Season", "Episode"):
            return 404, {"error": "not a movie/series/season/episode"}
        season = episode = None
        top = it
        if kind in ("Season", "Episode"):
            season = it.get("IndexNumber") if kind == "Season" else it.get("ParentIndexNumber")
            episode = it.get("IndexNumber") if kind == "Episode" else None
            top = jf.call("GET", f"/Items/{it['SeriesId']}", {"userId": jf.uid(), "Fields": "ProviderIds"})
        ids = {k.lower(): str(v) for k, v in (top.get("ProviderIds") or {}).items() if k.lower() in ("tmdb", "tvdb", "imdb")}
        watched = []
        if self.plex.ok:
            watched += self.plex.watched(kind, ids, season, episode)
        watched += self._jellyfin_watched(jid, kind)
        body = {"requestedBy": self._requested("Movie" if kind == "Movie" else "Series", ids), "watchedBy": merge_watched(watched)}
        self._cache[jid] = (time.time() + TTL, body)
        return 200, body
