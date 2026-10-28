"""HTTP surface for the Jellytip companion API (contract v1). Stdlib only."""
import hmac
import json
import os
import re
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import __version__
from .cache import Cache, EMPTY_TTL, ERROR_TTL, SCORES_TTL
from .letterboxd import Letterboxd
from .scores import ScoreProvider
from .stills import fetch_stills

EXTRAS_RE = re.compile(r"^/v1/items/([^/]+)/extras$")
LIST_RE = re.compile(r"^/v1/letterboxd/lists/([^/]+)$")


class Service:
    def __init__(self, env, scores=None, stills=fetch_stills, letterboxd=None, cache=None):
        self.token = env["JELLYTIP_TOKEN"]
        self.cache = cache or Cache(env.get("JELLYTIP_DB", "jellytip.db"))
        self.scores = scores or ScoreProvider(env.get("MDBLIST_KEY"), env.get("OMDB_KEY"))
        self.stills, self.tmdb_key = stills, env.get("TMDB_KEY")
        self.lb = letterboxd or Letterboxd(env.get("LB_EXPORT", "letterboxd.json"), self._jellyfin_items(env))

    @staticmethod
    def _jellyfin_items(env):
        base, key = env.get("JELLYFIN_URL"), env.get("JELLYFIN_KEY")
        if not (base and key):
            return None

        def fetch():
            q = urllib.parse.urlencode({"Recursive": "true", "IncludeItemTypes": "Movie", "Fields": "ProviderIds"})
            req = urllib.request.Request(f"{base}/Items?{q}", headers={"Authorization": f'MediaBrowser Token="{key}"'})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r).get("Items", [])
        return fetch

    def extras(self, tmdb, imdb, kind):
        ck = f"extras:{kind}:{tmdb or ''}:{imdb or ''}"
        cached = self.cache.get(ck)
        if cached is None:
            scores, ok1 = self.scores.lookup(tmdb, imdb, kind)
            stills, ok2 = self.stills(self.tmdb_key, tmdb, kind)
            cached = {"scores": scores, "stills": stills}
            ttl = ERROR_TTL if not (ok1 and ok2) else SCORES_TTL if scores else EMPTY_TTL
            self.cache.put(ck, cached, ttl)
        # Letterboxd is a local file read: always fresh, never part of the 7-day cache.
        return {"scores": cached["scores"], "letterboxd": self.lb.film(tmdb) if tmdb else None, "stills": cached["stills"]}

    def route(self, path, query, token):
        """Returns (status, body)."""
        if path == "/v1/health":
            return 200, {"ok": True, "version": __version__}
        if not hmac.compare_digest((token or "").encode(), self.token.encode()):
            return 401, {"error": "unauthorized"}
        m = EXTRAS_RE.match(path)
        if m:
            kind = query.get("type", "movie")
            if kind not in ("movie", "series"):
                return 400, {"error": "type must be movie or series"}
            tmdb = query.get("tmdb", "")
            imdb = query.get("imdb", "")
            if (tmdb and not tmdb.isdigit()) or (imdb and not re.fullmatch(r"tt\d+", imdb)):
                return 400, {"error": "bad tmdb/imdb id"}
            return 200, self.extras(tmdb or None, imdb or None, kind)
        if path == "/v1/letterboxd/lists":
            return 200, self.lb.lists()
        m = LIST_RE.match(path)
        if m:
            lst = self.lb.list(urllib.parse.unquote(m.group(1)))
            return (200, lst) if lst else (404, {"error": "no such list"})
        return 404, {"error": "not found"}


def make_handler(service, prefix="/jellytip-api"):
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            u = urllib.parse.urlsplit(self.path)
            path = u.path[len(prefix):] if u.path.startswith(prefix + "/") else u.path
            query = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
            try:
                status, body = service.route(path, query, self.headers.get("X-Jellytip-Token"))
            except Exception as e:  # never leak internals or keys; log the type only
                self.log_message("error %s", type(e).__name__)
                status, body = 500, {"error": "internal error"}
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
    srv = ThreadingHTTPServer(("127.0.0.1", int(env.get("PORT", "18931"))), make_handler(Service(env)))
    srv.serve_forever()
