"""HTTP surface for the Jellytip companion API (contract v1). Stdlib only."""
import hashlib
import hmac
import json
import os
import re
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from . import storage as storage_mod
from .audience import Audience, Plex, friendly
from .cache import Cache, EMPTY_TTL, ERROR_TTL, SCORES_TTL
from .imgscores import ImgScores
from .letterboxd import Letterboxd
from .scores import ScoreProvider
from .stills import fetch_stills
from . import sizes as sizes_mod
from . import trailers as trailers_mod
from .jf import Jellyfin, JfError
from .subtitles import Jobs
from .request import Requests, arr_from_env

EXTRAS_RE = re.compile(r"^/v1/items/([^/]+)/extras$")
BACKDROPS_RE = re.compile(r"^/v1/items/([0-9a-fA-F-]{32,36})/backdrops$")
AUDIENCE_RE = re.compile(r"^/v1/items/([0-9a-fA-F-]{32,36})/audience$")
DELETED_RE = re.compile(r"^/v1/items/([0-9a-fA-F-]{32,36})/deleted$")
JOB_RE = re.compile(r"^/v1/subtitles/jobs/([0-9a-f]{12})$")
EPISODES_ART_RE = re.compile(r"^/v1/items/([0-9a-fA-F-]{32,36})/episodes-art$")
LIST_RE = re.compile(r"^/v1/letterboxd/lists/([^/]+)$")
TOKEN_RE = re.compile(r'Token="?([^",\s]+)"?')
AUTH_TTL = 600


class FileBody:
    """Route result streamed as a file rather than JSON."""
    def __init__(self, path, content_type, etag):
        self.path, self.content_type, self.etag = path, content_type, etag


class Service:
    def __init__(self, env, scores=None, stills=fetch_stills, letterboxd=None, cache=None, imgscores=None, requests=None):
        self.token = env["JELLYTIP_TOKEN"]
        self.env = env
        self._tag_cache = {}
        self._pid_cache = {}
        self.jf = Jellyfin(env.get("JELLYFIN_URL"), env.get("JELLYFIN_KEY"), env.get("JELLYFIN_USER", "ricki28"))
        self.subs = Jobs(self.jf)
        self.jf_url = (env.get("JELLYFIN_URL") or "").rstrip("/")
        self._auth_cache = {}  # sha256(token) -> (ok, expires); raw tokens are never stored or logged
        self.cache = cache or Cache(env.get("JELLYTIP_DB", "jellytip.db"))
        self.scores = scores or ScoreProvider(env.get("MDBLIST_KEY"), env.get("OMDB_KEY"))
        data_dir = os.path.dirname(env.get("LB_EXPORT", "")) or "."
        self.imgscores = imgscores or ImgScores(env.get("IMGSCORES_FILE") or os.path.join(data_dir, "imgscores.json"))
        self.stills, self.tmdb_key = stills, env.get("TMDB_KEY")
        self.lb = letterboxd or Letterboxd(env.get("LB_EXPORT", "letterboxd.json"), self._jellyfin_items(env),
                                           tmdb_images=self._tmdb_movie_images, posters_path=os.path.join(data_dir, "lb_posters.json"))
        self.name_cache = {}  # sha256(token) -> (user name, expires)
        self.admin_cache = {}  # sha256(token) -> (is_admin, expires)
        plex_url, plex_tok = env.get("PLEX_URL"), env.get("PLEX_TOKEN")
        if not (plex_url and plex_tok):
            try:
                import yaml
                with open(env.get("KOMETA_CONFIG") or os.path.expanduser("~/Kometa/config/config.yml")) as f:
                    c = yaml.safe_load(f)["plex"]
                plex_url, plex_tok = c["url"], c["token"]
            except Exception:
                pass
        self.audience = Audience(self, env.get("REQUESTS_EXPORT") or os.path.join(data_dir, "requests.json"), Plex(plex_url, plex_tok))
        self.requests = requests or Requests(*arr_from_env(env), os.path.join(data_dir, "jellytip_requests.jsonl"), tmdb_eps=self._tmdb_season1_eps)
        self.carousels_path = env.get("CAROUSELS_FILE") or os.path.join(os.path.dirname(env.get("LB_EXPORT", "")) or ".", "carousels.json")

    @staticmethod
    def _jellyfin_items(env):
        base, key = env.get("JELLYFIN_URL"), env.get("JELLYFIN_KEY")
        if not (base and key):
            return None

        def fetch():
            hdr = {"Authorization": f'MediaBrowser Token="{key}"'}
            # User-scoped listing: the admin /Items endpoint under-reports (465 of 647 films on 28-Oct).
            with urllib.request.urlopen(urllib.request.Request(f"{base}/Users", headers=hdr), timeout=30) as r:
                users = json.load(r)
            uid = next((u["Id"] for u in users if u["Name"] == env.get("JELLYFIN_USER", "ricki28")), users[0]["Id"])
            q = urllib.parse.urlencode({"Recursive": "true", "IncludeItemTypes": "Movie", "Fields": "ProviderIds"})
            req = urllib.request.Request(f"{base}/Users/{uid}/Items?{q}", headers=hdr)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r).get("Items", [])
        return fetch

    def extras(self, tmdb, imdb, kind):
        ck = f"extras2:{kind}:{tmdb or ''}:{imdb or ''}"
        cached = self.cache.get(ck)
        if cached is None:
            scores, ok1 = self.scores.lookup(tmdb, imdb, kind)
            stills, ok2 = self.stills(self.tmdb_key, tmdb, kind)
            cached = {"scores": scores, "stills": stills}
            ttl = ERROR_TTL if not (ok1 and ok2) else SCORES_TTL if scores else EMPTY_TTL
            self.cache.put(ck, cached, ttl)
        # Letterboxd is a local file read: always fresh, never part of the 7-day cache.
        return {"scores": cached["scores"], "letterboxd": self.lb.film(tmdb) if tmdb else None, "stills": self.imgscores.rank_stills(cached["stills"])}

    def _provider_ids(self, item_id):
        """(tmdb, imdb, kind) of a Jellyfin item, cached 1 h; ('', '', None) when unknown."""
        hit = self._pid_cache.get(item_id)
        if hit and time.time() - hit[0] < 3600:
            return hit[1]
        out = ("", "", None)
        if self.jf.ok:
            try:
                it = self.jf.call("GET", f"/Users/{self.jf.uid()}/Items/{item_id}", timeout=5) or {}
                ids = it.get("ProviderIds") or {}
                tmdb, imdb = str(ids.get("Tmdb") or ""), str(ids.get("Imdb") or "")
                out = (tmdb if tmdb.isdigit() else "", imdb if re.fullmatch(r"tt\d+", imdb) else "",
                       {"Movie": "movie", "Series": "series"}.get(it.get("Type")))
            except Exception:
                return out  # do not cache failures
        self._pid_cache[item_id] = (time.time(), out)
        return out

    def _backdrop_tags(self, item_id):
        """Current Jellyfin BackdropImageTags of the item (5 min cache), or None when unknown (then the stored set is trusted)."""
        hit = self._tag_cache.get(item_id)
        if hit and time.time() - hit[0] < 300:
            return hit[1]
        tags = None
        if self.jf.ok:
            try:
                tags = (self.jf.call("GET", f"/Users/{self.jf.uid()}/Items/{item_id}", timeout=5) or {}).get("BackdropImageTags") or []
            except Exception:
                tags = None
        if tags is not None:
            self._tag_cache[item_id] = (time.time(), tags)
        return tags

    @staticmethod
    def jellyfin_token(authorization, emby_token):
        if emby_token:
            return emby_token.strip()
        m = TOKEN_RE.search(authorization or "") if (authorization or "").startswith("MediaBrowser") else None
        return m.group(1) if m else None

    def jellyfin_ok(self, jf_token):
        """Validate a Jellyfin access token against the local server (cached 10 min)."""
        if not (jf_token and self.jf_url):
            return False
        k = hashlib.sha256(jf_token.encode()).hexdigest()
        hit = self._auth_cache.get(k)
        now = time.time()
        if hit and hit[1] > now:
            return hit[0]
        try:
            req = urllib.request.Request(f"{self.jf_url}/System/Info", headers={"Authorization": f'MediaBrowser Token="{jf_token}"'})
            with urllib.request.urlopen(req, timeout=5) as r:
                ok = r.status == 200
        except urllib.error.HTTPError:
            ok = False  # 401/403: definitively rejected
        except Exception:
            return False  # Jellyfin unreachable: deny but do not cache
        if len(self._auth_cache) > 1000:
            self._auth_cache.clear()
        self._auth_cache[k] = (ok, now + AUTH_TTL)
        return ok

    def _tmdb_movie_images(self, tmdb):
        """(posterUrl, backdropUrl) from TMDB, or None when the lookup fails (so it is retried, not cached)."""
        if not self.tmdb_key:
            return None
        try:
            with urllib.request.urlopen(f"https://api.themoviedb.org/3/movie/{int(tmdb)}?api_key={self.tmdb_key}", timeout=8) as r:
                m = json.load(r)
        except Exception:
            return None
        base = "https://image.tmdb.org/t/p/"
        return (base + "w342" + m["poster_path"] if m.get("poster_path") else "",
                base + "w780" + m["backdrop_path"] if m.get("backdrop_path") else "")

    def _tmdb_season1_eps(self, tmdb):
        if not self.tmdb_key:
            return None
        try:
            with urllib.request.urlopen(f"https://api.themoviedb.org/3/tv/{int(tmdb)}/season/1?api_key={self.tmdb_key}", timeout=8) as r:
                return len(json.load(r).get("episodes") or []) or None
        except Exception:
            return None

    def jellyfin_name(self, jf_token):
        """Friendly name of the Jellyfin user behind this token (None for the shared app token or on error)."""
        if not (jf_token and self.jf_url):
            return None
        k = hashlib.sha256(jf_token.encode()).hexdigest()
        hit = self.name_cache.get(k)
        now = time.time()
        if hit and hit[1] > now:
            return hit[0]
        try:
            req = urllib.request.Request(f"{self.jf_url}/Users/Me", headers={"Authorization": f'MediaBrowser Token="{jf_token}"'})
            with urllib.request.urlopen(req, timeout=5) as r:
                name = (json.load(r).get("Name") or "").strip() or None
        except urllib.error.HTTPError:
            name = None
        except Exception:
            return None
        if len(self.name_cache) > 1000:
            self.name_cache.clear()
        self.name_cache[k] = (name, now + AUTH_TTL)
        return name

    def jellyfin_admin(self, jf_token):
        """Caller's friendly name when this Jellyfin token belongs to an administrator (/Users/Me, cached 10 min), else None.
        The shared app token is never admin."""
        if not (jf_token and self.jf_url):
            return None
        k = hashlib.sha256(jf_token.encode()).hexdigest()
        hit = self.admin_cache.get(k)
        now = time.time()
        if hit and hit[1] > now:
            return hit[0]
        try:
            req = urllib.request.Request(f"{self.jf_url}/Users/Me", headers={"Authorization": f'MediaBrowser Token="{jf_token}"'})
            with urllib.request.urlopen(req, timeout=5) as r:
                me = json.load(r)
                ok = friendly(me.get("Name")) if (me.get("Policy") or {}).get("IsAdministrator") else None
        except urllib.error.HTTPError:
            ok = None
        except Exception:
            return None
        if len(self.admin_cache) > 1000:
            self.admin_cache.clear()
        self.admin_cache[k] = (ok, now + AUTH_TTL)
        return ok

    def intro(self, want_file):
        """Nightly cold-start intro built by jellytip-intro/build_intro.py (meta json + mp4 in INTRO_DIR)."""
        d = self.env.get("INTRO_DIR") or os.path.expanduser("~/jellytip-intro/out")
        try:
            with open(os.path.join(d, "intro.json"), encoding="utf-8") as f:
                meta = json.load(f)
            mp4 = os.path.join(d, "intro.mp4")
            if not os.path.isfile(mp4):
                raise OSError
        except (OSError, ValueError):
            return 404, {"error": "no intro built yet"}
        if want_file:
            return 200, FileBody(mp4, "video/mp4", meta["version"])
        return 200, {"url": "v1/intro.mp4", "version": meta["version"], "titles": meta.get("titles", []),
                     "duration": meta.get("duration"), "bytes": meta.get("bytes")}  # url is relative to the API base

    def route(self, path, query, token, authorization=None, emby_token=None, method="GET", body=None):
        """Returns (status, body)."""
        if path == "/v1/health":
            return 200, {"ok": True, "version": __version__}
        if not (hmac.compare_digest((token or "").encode(), self.token.encode())
                or self.jellyfin_ok(self.jellyfin_token(authorization, emby_token))):
            return 401, {"error": "unauthorized"}
        if method == "POST":
            return self.route_post(path, body or {}, query, self.jellyfin_token(authorization, emby_token))
        if path == "/v1/request/status":
            ids = [t for t in re.split(r"[,\s]+", query.get("tmdbIds", "")) if re.fullmatch(r"(?:(?:movie|tv):)?\d+", t)]
            return self.requests.status(ids)
        if path == "/v1/storage":
            if not self.jellyfin_admin(self.jellyfin_token(authorization, emby_token)):
                return 403, {"error": "admin only"}
            try:
                return 200, storage_mod.get()
            except Exception as ex:  # quota binary missing / unparsable
                return 503, {"error": "quota unavailable: %s" % ex}
        if path in ("/v1/intro", "/v1/intro.mp4"):
            return self.intro(path.endswith(".mp4"))
        m = EXTRAS_RE.match(path)
        if m:
            kind = query.get("type", "movie")
            if kind not in ("movie", "series"):
                return 400, {"error": "type must be movie or series"}
            tmdb = query.get("tmdb", "")
            imdb = query.get("imdb", "")
            if (tmdb and not tmdb.isdigit()) or (imdb and not re.fullmatch(r"tt\d+", imdb)):
                return 400, {"error": "bad tmdb/imdb id"}
            if not tmdb and not imdb:  # home/list items carry no ProviderIds: resolve them from the Jellyfin item itself
                tmdb, imdb, found_kind = self._provider_ids(m.group(1).replace("-", "").lower())
                kind = found_kind or kind
            return 200, self.extras(tmdb or None, imdb or None, kind)
        m = AUDIENCE_RE.match(path)
        if m:
            me = self.jellyfin_admin(self.jellyfin_token(authorization, emby_token))
            if not me:
                return 403, {"error": "admin only"}
            if not self.jf.ok:
                return 503, {"error": "jellyfin not configured"}
            status, aud = self.audience.get(m.group(1).replace("-", "").lower())
            return status, ({**aud, "you": me} if status == 200 else aud)
        m = BACKDROPS_RE.match(path)
        if m:
            item_id = m.group(1).replace("-", "").lower()
            res = self.imgscores.backdrops(item_id, self._backdrop_tags(item_id))
            return 200, res if res is not None else {"itemId": m.group(1), "animation": False, "backdrops": None}
        m = EPISODES_ART_RE.match(path)
        if m:
            return 200, self.imgscores.episodes_art(m.group(1).replace("-", "").lower())
        if path == "/v1/home/carousels":
            try:
                with open(self.carousels_path, encoding="utf-8") as f:
                    manifest = json.load(f)
            except OSError:
                manifest = {"version": 1, "generated": None, "carousels": []}  # sync not run yet: app shows no extra rows
            if query.get("includeDiscover") != "1":  # discover rows carry TMDB ids, not library items: opt-in only
                manifest = {**manifest, "carousels": [c for c in manifest.get("carousels", []) if not c.get("discover")]}
            lb = self.lb.home_lists(manifest.get("letterboxdConfig"), include_discover=query.get("includeDiscover") == "1")
            return 200, {**manifest, "letterboxd": lb}
        if path == "/v1/library/sizes":
            if not self.jf.ok:
                return 503, {"error": "jellyfin not configured"}
            return 200, sizes_mod.get(self)
        m = JOB_RE.match(path)
        if m:
            v = self.subs.view(m.group(1))
            return (200, v) if v else (404, {"error": "no such job"})
        if path == "/v1/letterboxd/lists":
            return 200, self.lb.lists()
        m = LIST_RE.match(path)
        if m:
            lst = self.lb.list(urllib.parse.unquote(m.group(1)))
            return (200, lst) if lst else (404, {"error": "no such list"})
        return 404, {"error": "not found"}


    def route_post(self, path, body, query=None, jf_token=None):
        if path == "/v1/request":
            who = friendly(self.jellyfin_name(jf_token) or "")
            if not who:
                return 403, {"error": "sign in with a Jellyfin account to request"}
            tmdb = body.get("tmdbId")
            tmdb = int(tmdb) if isinstance(tmdb, str) and tmdb.isdigit() else tmdb
            return self.requests.request(tmdb if isinstance(tmdb, int) else None, str(body.get("type") or ""), who,
                                         dry_run=(query or {}).get("dryRun") == "1")
        if path == "/v1/subtitles/season":
            sid = str(body.get("seasonId", ""))
            if not re.fullmatch(r"[0-9a-fA-F-]{32,36}", sid):
                return 400, {"error": "bad seasonId"}
            if not self.jf.ok:
                return 503, {"error": "jellyfin not configured"}
            return 202, {"jobId": self.subs.start(sid, str(body.get("language") or "eng")[:8])}
        m = DELETED_RE.match(path)
        if m:
            return 200, self.item_deleted(m.group(1).replace("-", "").lower(), str(body.get("path") or ""), str(body.get("type") or ""))
        return 404, {"error": "not found"}

    def item_deleted(self, iid, hint_path="", hint_type=""):
        """Remove the deleted title's trailers/ folder. Path comes from the cached id->folder map (filled by /library/sizes),
        else from Jellyfin if the item still exists. Refuses while the title folder still holds a video file."""
        paths = self.cache.get(sizes_mod.PATHS_KEY) or {}
        info = paths.get(iid)
        if not info and self.jf.ok:
            try:
                it = self.jf.call("GET", f"/Items/{iid}", {"userId": self.jf.uid()})
                p = it.get("Path") or ""
                if it.get("Type") == "Movie":
                    info = {"folder": os.path.dirname(p)}
                elif it.get("Type") == "Series":
                    info = {"folder": p}
            except (JfError, OSError):
                pass
        if not info and hint_path:  # path the app captured before the delete; still verified against media roots + video files below
            info = {"folder": os.path.dirname(hint_path) if hint_type.lower() == "movie" else hint_path}
        if not info:
            return {"removed": False, "reason": "unknown title (not a movie/series, or never indexed)"}
        folder = os.path.normpath(info["folder"])
        roots = [os.path.normpath(r) for r in trailers_mod.default_roots()]
        if os.path.dirname(folder) not in roots:
            return {"removed": False, "reason": "folder outside media roots"}
        reason = trailers_mod.remove_for_folder(folder, log=lambda m: print(m, flush=True))
        return {"removed": reason == "removed", "reason": reason}


def make_handler(service, prefix="/jellytip-api"):
    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            n = min(int(self.headers.get("Content-Length") or 0), 65536)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                body = None
            self.do_GET("POST", body if isinstance(body, dict) else {})

        def do_GET(self, method="GET", body=None):
            u = urllib.parse.urlsplit(self.path)
            path = u.path[len(prefix):] if u.path.startswith(prefix + "/") else u.path
            query = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            try:
                status, body = service.route(path, query, self.headers.get("X-Jellytip-Token"),
                                             self.headers.get("Authorization"), self.headers.get("X-Emby-Token"), method, body)
            except Exception as e:  # never leak internals or keys; log the type only
                self.log_message("error %s", type(e).__name__)
                status, body = 500, {"error": "internal error"}
            if isinstance(body, FileBody):
                if self.headers.get("If-None-Match") == f'"{body.etag}"':
                    self.send_response(304)
                    self.send_header("ETag", f'"{body.etag}"')
                    self.end_headers()
                    return
                size = os.path.getsize(body.path)
                self.send_response(status)
                self.send_header("Content-Type", body.content_type)
                self.send_header("Content-Length", str(size))
                self.send_header("ETag", f'"{body.etag}"')
                self.send_header("Cache-Control", "private, max-age=3600")
                self.end_headers()
                with open(body.path, "rb") as f:
                    shutil.copyfileobj(f, self.wfile)
                return
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):  # request lines carry ids only, no query secrets (keys are never in our URL)
            print("%s %s" % (self.address_string(), fmt % args), flush=True)
    return H


def main():
    env = dict(os.environ)
    svc = Service(env)

    def refresh_paths():  # keeps the id->folder map fresh so trailer cleanup still resolves titles after Jellyfin deletes them
        while True:
            try:
                if svc.jf.ok:
                    sizes_mod.refresh(svc)
            except Exception as e:
                print("path refresh failed", type(e).__name__, flush=True)
            time.sleep(1800)
    threading.Thread(target=refresh_paths, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", int(env.get("PORT", "18931"))), make_handler(svc))
    srv.serve_forever()
