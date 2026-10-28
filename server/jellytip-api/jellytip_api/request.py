"""'Get it' for Discover items: add a not-in-library title to Radarr/Sonarr, guarded by a free-space floor.

Films -> Radarr with the library's usual profile/root folder, monitored, search on add.
TV    -> Sonarr, season 1 monitored only, search on add.
Every real add is appended to a JSONL log (who/what/when) that the Hetzner host folds into the request bot's requests.db."""
import collections
import json
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

FLOOR_GB = 30.0
MOVIE_GB = 8.0
EP_GB = 3.0
DEFAULT_EPS = 10
STATUS_TTL = 20
LEDGER_HOURS = 72  # an add counts against free space until its files land, for at most this long
USER_DAILY_CAP = 5  # per Jellyfin user, admins exempt
GLOBAL_DAILY_CAP = 15
GB = 1024.0 ** 3
UNITS = {"K": 1 / 1024 ** 2, "M": 1 / 1024, "G": 1.0, "T": 1024.0}


class ArrError(Exception):
    pass


class Arr:
    """Minimal Radarr/Sonarr v3 client. Stdlib only."""

    def __init__(self, base, key):
        self.base, self.key = (base or "").rstrip("/"), key

    @property
    def ok(self):
        return bool(self.base and self.key)

    def call(self, method, path, params=None, body=None, timeout=30):
        url = f"{self.base}/api/v3{path}" + ("?" + urllib.parse.urlencode(params) if params else "")
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers={"X-Api-Key": self.key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            raise ArrError(f"{e.code}") from None
        except Exception as e:
            raise ArrError(type(e).__name__) from None
        return json.loads(raw) if raw else None


def arr_from_env(env):
    cfg = {}
    if not (env.get("RADARR_KEY") and env.get("SONARR_KEY")):
        try:
            import yaml
            with open(env.get("KOMETA_CONFIG") or os.path.expanduser("~/Kometa/config/config.yml")) as f:
                y = yaml.safe_load(f)
            cfg = {k: y[k] for k in ("radarr", "sonarr") if k in y}
        except Exception:
            pass
    def mk(name):
        c = cfg.get(name, {})
        return Arr(env.get(name.upper() + "_URL") or c.get("url"), env.get(name.upper() + "_KEY") or c.get("token"))
    return mk("radarr"), mk("sonarr")


def parse_quota(text):
    """`quota -s -w` -> (used_gb, limit_gb) of the first filesystem line, or None."""
    for line in text.splitlines():
        m = re.match(r"\s*/\S+\s+(\d+(?:\.\d+)?)([KMGT]?)\s+(\d+(?:\.\d+)?)([KMGT]?)\s", line)
        if m:
            f = lambda n, u: float(n) * UNITS.get(u or "K", UNITS["K"])
            return f(m.group(1), m.group(2)), f(m.group(3), m.group(4))
    return None


def quota_free_gb():
    try:
        out = subprocess.run(["quota", "-s", "-w"], capture_output=True, text=True, timeout=15).stdout
    except Exception:
        return None
    q = parse_quota(out)
    return None if not q or q[1] <= 0 else q[1] - q[0]


def mode(items, keys):
    c = collections.Counter(tuple(i.get(k) for k in keys) for i in items)
    return dict(zip(keys, c.most_common(1)[0][0])) if c else {}


class Requests:
    def __init__(self, radarr, sonarr, log_path, free_gb=quota_free_gb, tmdb_eps=None, clock=time.time):
        self.radarr, self.sonarr, self.log_path = radarr, sonarr, log_path
        self.free_gb, self.tmdb_eps, self.clock = free_gb, tmdb_eps, clock
        self.lock = threading.Lock()
        self._snap = (0, None)
        self.ledger_path = os.path.splitext(log_path)[0] + "_pending.json"
        self._ledger = None  # [{"at": epoch, "who", "type", "tmdb", "estGb"}], persisted

    # ---- helpers ----
    def _arr(self, kind):
        return self.radarr if kind == "movie" else self.sonarr

    def _existing(self, kind, tmdb):
        a = self._arr(kind)
        if kind == "movie":
            r = a.call("GET", "/movie", {"tmdbId": tmdb})
            return r[0] if r else None
        return next((s for s in a.call("GET", "/series") if s.get("tmdbId") == tmdb), None)

    def _lookup(self, kind, tmdb):
        r = self._arr(kind).call("GET", "/movie/lookup" if kind == "movie" else "/series/lookup", {"term": f"tmdb:{tmdb}"})
        return next((x for x in (r or []) if x.get("tmdbId") == tmdb), None)

    def _season1_eps(self, item, tmdb):
        s1 = next((s for s in item.get("seasons", []) if s.get("seasonNumber") == 1), {})
        n = (s1.get("statistics") or {}).get("totalEpisodeCount")
        if not n and self.tmdb_eps:
            n = self.tmdb_eps(tmdb)
        return int(n) if n else DEFAULT_EPS

    def estimate_gb(self, kind, item, tmdb):
        return MOVIE_GB if kind == "movie" else EP_GB * self._season1_eps(item, tmdb)

    @staticmethod
    def _label(kind, item):
        t = f"{item.get('title', '?')}" + (f" ({item['year']})" if item.get("year") else "")
        return t if kind == "movie" else f"Season 1 of {t}"

    # ---- pending-add ledger ----
    def _load_ledger(self):
        if self._ledger is None:
            try:
                with open(self.ledger_path, encoding="utf-8") as f:
                    self._ledger = [e for e in json.load(f) if isinstance(e, dict)]
            except (OSError, ValueError):
                self._ledger = []
        cutoff = self.clock() - LEDGER_HOURS * 3600
        self._ledger = [e for e in self._ledger if e.get("at", 0) >= cutoff]
        return self._ledger

    def _save_ledger(self):
        os.makedirs(os.path.dirname(self.ledger_path) or ".", exist_ok=True)
        tmp = self.ledger_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._ledger, f)
        os.replace(tmp, self.ledger_path)

    def _queue_left(self, a, idkey):
        """(total sizeleft GB, {arr id: sizeleft GB}) of the arr download queue."""
        by_id, total = collections.defaultdict(float), 0.0
        for r in (a.call("GET", "/queue", {"pageSize": 1000}) or {}).get("records", []):
            left = (r.get("sizeleft") or 0) / GB
            total += left
            by_id[r.get(idkey)] += left
        return total, by_id

    def pending_gb(self):
        """GB still to land on disk: everything in the Radarr/Sonarr queues plus the estimate of recent adds that have no
        file yet and are not queued (so a just-added title whose grab has not started still reserves its space)."""
        ledger = self._load_ledger()
        qm, by_movie = self._queue_left(self.radarr, "movieId")
        qs, by_series = self._queue_left(self.sonarr, "seriesId")
        total, keep = qm + qs, []
        movies = series = None
        for e in ledger:
            if e.get("type") == "movie":
                movies = movies if movies is not None else {m.get("tmdbId"): m for m in self.radarr.call("GET", "/movie") or []}
                have, queued = movies.get(e.get("tmdb")), by_movie
            else:
                series = series if series is not None else {m.get("tmdbId"): m for m in self.sonarr.call("GET", "/series") or []}
                have, queued = series.get(e.get("tmdb")), by_series
            if have is None:
                continue  # removed from the arr again: nothing pending, drop it
            if e.get("type") == "movie":
                done = bool(have.get("hasFile"))
            else:
                st = next((x for x in have.get("seasons", []) if x.get("seasonNumber") == 1), {}).get("statistics") or {}
                done = bool(st.get("episodeCount")) and (st.get("episodeFileCount") or 0) >= st["episodeCount"]
            if done:
                continue
            keep.append(e)
            if have.get("id") not in queued:
                total += e.get("estGb") or 0
        if len(keep) != len(ledger):
            self._ledger = keep
            self._save_ledger()
        return total

    def _caps(self, who, is_admin):
        """Block reason when the daily caps are hit, else None."""
        day = self.clock() - 86400
        recent = [e for e in self._load_ledger() if e.get("at", 0) >= day]
        if len(recent) >= GLOBAL_DAILY_CAP:
            return f"daily request limit reached ({GLOBAL_DAILY_CAP} a day across everyone), try tomorrow"
        if not is_admin and sum(1 for e in recent if e.get("who") == who) >= USER_DAILY_CAP:
            return f"you have used your {USER_DAILY_CAP} requests for today"
        return None

    # ---- POST /v1/request ----
    def request(self, tmdb, kind, who, dry_run=False, is_admin=False):
        if kind not in ("movie", "tv") or not isinstance(tmdb, int) or tmdb <= 0:
            return 400, {"error": "need tmdbId (int) and type movie|tv"}
        if not (self.radarr.ok and self.sonarr.ok):
            return 503, {"error": "radarr/sonarr not configured"}
        try:
            with self.lock:  # serialises the free-space check with the add so two taps cannot both squeeze under the floor
                have = self._existing(kind, tmdb)
                if have:
                    return 200, {"status": "exists", "reason": "already requested or in the library", "title": have.get("title"), "dryRun": dry_run}
                item = self._lookup(kind, tmdb)
                if not item:
                    return 404, {"error": "title not found"}
                free, est = self.free_gb(), self.estimate_gb(kind, item, tmdb)
                pending = self.pending_gb()
                base = {"title": item.get("title"), "year": item.get("year"), "label": self._label(kind, item),
                        "estimateGb": round(est, 1), "freeGbNow": None if free is None else round(free, 1), "pendingGb": round(pending, 1), "dryRun": dry_run}
                if free is None:
                    return 200, {**base, "status": "blocked", "reason": "could not read free space", "freeGbAfter": None}
                capped = self._caps(who, is_admin)
                if capped:
                    return 200, {**base, "status": "blocked", "reason": capped, "freeGbAfter": None}
                after = free - pending - est
                base["freeGbAfter"] = round(after, 1)
                if after < FLOOR_GB:
                    return 200, {**base, "status": "blocked", "reason": f"would leave {after:.0f} GB free after downloads in progress (minimum {FLOOR_GB:.0f} GB)"}
                if dry_run:
                    return 200, {**base, "status": "added", "reason": "dry run: not added"}
                self._add(kind, item)
                self._load_ledger().append({"at": self.clock(), "who": who, "type": kind, "tmdb": tmdb, "estGb": round(est, 2)})
                self._save_ledger()
                self._log(who, kind, tmdb, item)
                self._snap = (0, None)
                return 200, {**base, "status": "added", "reason": "added and searching"}
        except ArrError as e:
            return 502, {"error": f"arr failed: {e}"}

    def _add(self, kind, item):
        a = self._arr(kind)
        if kind == "movie":
            d = {**mode(a.call("GET", "/movie"), ("qualityProfileId", "rootFolderPath", "minimumAvailability"))}
            body = {**item, **d, "monitored": True, "addOptions": {"searchForMovie": True}}
            a.call("POST", "/movie", body=body)
        else:
            d = mode(a.call("GET", "/series"), ("qualityProfileId", "rootFolderPath", "languageProfileId", "seasonFolder"))
            d = {k: v for k, v in d.items() if v is not None}
            seasons = [{**s, "monitored": s.get("seasonNumber") == 1} for s in item.get("seasons", [])]
            body = {**item, **d, "seasons": seasons, "monitored": True, "monitorNewItems": "none",
                    "addOptions": {"monitor": "firstSeason", "searchForMissingEpisodes": True}}
            a.call("POST", "/series", body=body)

    def _log(self, who, kind, tmdb, item):
        rec = {"at": time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(self.clock())), "who": who, "type": kind,
               "tmdbId": tmdb, "tvdbId": item.get("tvdbId"), "title": item.get("title"), "year": item.get("year")}
        os.makedirs(os.path.dirname(self.log_path) or ".", exist_ok=True)
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    # ---- GET /v1/request/status ----
    def _snapshot(self):
        now = self.clock()
        if self._snap[1] is not None and now - self._snap[0] < STATUS_TTL:
            return self._snap[1]
        def queue(a, idkey):
            out = collections.defaultdict(lambda: [0, 0])
            for r in (a.call("GET", "/queue", {"pageSize": 1000}) or {}).get("records", []):
                q = out[r.get(idkey)]
                q[0] += r.get("size") or 0
                q[1] += r.get("sizeleft") or 0
            return out
        snap = {"movie": {m["tmdbId"]: m for m in self.radarr.call("GET", "/movie") if m.get("tmdbId")},
                "tv": {s["tmdbId"]: s for s in self.sonarr.call("GET", "/series") if s.get("tmdbId")},
                "mq": queue(self.radarr, "movieId"), "tq": queue(self.sonarr, "seriesId")}
        self._snap = (now, snap)
        return snap

    def status(self, tokens):
        """tokens: 'movie:603' | 'tv:95396' | bare id (checked as movie then tv). -> {token: {state, progress?}}"""
        if not (self.radarr.ok and self.sonarr.ok):
            return 503, {"error": "radarr/sonarr not configured"}
        try:
            snap = self._snapshot()
        except ArrError as e:
            return 502, {"error": f"arr failed: {e}"}
        out = {}
        for tok in tokens[:200]:
            kind, _, num = tok.rpartition(":")
            if not num.isdigit():
                continue
            n = int(num)
            for k in ([kind] if kind in ("movie", "tv") else ["movie", "tv"]):
                it = snap[k].get(n)
                if it:
                    out[tok] = self._state(k, it, snap)
                    break
        return 200, {"status": out}

    @staticmethod
    def _state(kind, it, snap):
        if kind == "movie":
            if it.get("hasFile"):
                return {"state": "in_library"}
            size, left = snap["mq"].get(it["id"], (0, 0))
        else:
            s1 = next((s for s in it.get("seasons", []) if s.get("seasonNumber") == 1), {}).get("statistics") or {}
            tot, have = s1.get("episodeCount") or 0, s1.get("episodeFileCount") or 0
            if tot and have >= tot:
                return {"state": "in_library"}
            size, left = snap["tq"].get(it["id"], (0, 0))
            if have and not size:
                return {"state": "downloading", "progress": int(100 * have / tot) if tot else 0}
        if size:
            return {"state": "downloading", "progress": max(0, min(99, int(100 * (size - left) / size)))}
        return {"state": "requested"}
