"""Apply the prepared-downloads routes to jellytip_api/server.py (idempotent check: refuses if already applied)."""
import sys

p = sys.argv[1]
s = open(p).read()
if "DL_JOB_RE" in s:
    sys.exit("already applied")


def rep(a, b):
    global s
    if s.count(a) != 1:
        sys.exit(f"anchor not unique/missing: {a[:60]!r}")
    s = s.replace(a, b)


rep("from .request import Requests, arr_from_env\n",
    "from .request import Requests, arr_from_env\nfrom .downloads import Downloads, Busy as DownloadsBusy, LowStorage\n")
rep('LIST_RE = re.compile(r"^/v1/letterboxd/lists/([^/]+)$")\n',
    'LIST_RE = re.compile(r"^/v1/letterboxd/lists/([^/]+)$")\n'
    'DL_JOB_RE = re.compile(r"^/v1/downloads/jobs/([0-9a-f]{16})$")\n'
    'DL_FILE_RE = re.compile(r"^/v1/downloads/files/([0-9a-f]{16})$")\n')
rep('''class Service:''', '''class RangeFile:
    """Prepared phone download, streamed by the handler (it has the Range header)."""
    def __init__(self, job_id):
        self.job_id = job_id


class Service:''')
rep('''        self.name_cache = {}  # sha256(token) -> (user name, expires)
''', '''        # Prepared phone downloads (jellytip phone app): one server-side transcode at a time, files under DOWNLOADS_DIR
        self.downloads = Downloads(self.jf_url, env.get("JELLYFIN_KEY"),
                                   env.get("DOWNLOADS_DIR") or os.path.expanduser("~/jellytip-downloads"),
                                   free_gb=lambda: storage_mod.get()["freeGb"])
        self.name_cache = {}  # sha256(token) -> (user name, expires)
''')
rep('''        m = LIST_RE.match(path)
        if m:
            lst = self.lb.list(urllib.parse.unquote(m.group(1)))
            return (200, lst) if lst else (404, {"error": "no such list"})
        return 404, {"error": "not found"}
''', '''        m = LIST_RE.match(path)
        if m:
            lst = self.lb.list(urllib.parse.unquote(m.group(1)))
            return (200, lst) if lst else (404, {"error": "no such list"})
        m = DL_JOB_RE.match(path)
        if m:
            return self.downloads.status(m.group(1))
        m = DL_FILE_RE.match(path)
        if m:
            return 200, RangeFile(m.group(1))
        return 404, {"error": "not found"}
''')
rep('''        m = DELETED_RE.match(path)
        if m:
            if not self.jellyfin_admin(jf_token, ip):''', '''        if path == "/v1/downloads/prepare":
            return self.prepare_download(body, jf_token, ip)
        m = DELETED_RE.match(path)
        if m:
            if not self.jellyfin_admin(jf_token, ip):''')
rep('''    def item_deleted(self, iid, hint_path="", hint_type=""):''', '''    def prepare_download(self, body, jf_token, ip=""):
        """POST /v1/downloads/prepare {itemId, quality}: the caller must be able to see the item with their own Jellyfin
        token (the shared app token can't prepare downloads); the transcode itself runs with the admin key."""
        iid = str(body.get("itemId") or "").replace("-", "").lower()
        if not re.fullmatch(r"[0-9a-f]{32}", iid):
            return 400, {"error": "bad itemId"}
        if not self.jf.ok:
            return 503, {"error": "jellyfin not configured"}
        try:
            # /Items?ids= runs with the caller's own library access (an API key sees everything; /Items/{id} needs a user)
            code, found = self._jf_me(f"/Items?ids={iid}", jf_token, ip)
        except OSError:
            return 503, {"error": "jellyfin unavailable"}
        if code in (401, 403) or not jf_token:
            return 403, {"error": "sign in with a Jellyfin account to download"}
        if code != 200 or not (found or {}).get("Items"):
            return 404, {"error": "no such item"}
        try:
            it = self.jf.call("GET", f"/Users/{self.jf.uid()}/Items/{iid}", timeout=10) or {}
        except (JfError, OSError):
            return 503, {"error": "jellyfin unavailable"}
        if it.get("Type") not in ("Movie", "Episode", "Video"):
            return 400, {"error": "only movies and episodes can be downloaded"}
        try:
            return self.downloads.prepare(iid, str(body.get("quality") or "720"), it.get("RunTimeTicks"), it.get("Path"))
        except DownloadsBusy:
            return 429, {"error": "download queue full, try later"}
        except LowStorage:
            return 507, {"error": "seedbox storage is low"}

    def item_deleted(self, iid, hint_path="", hint_type=""):''')
rep('''            if isinstance(body, FileBody):''', '''            if isinstance(body, RangeFile):
                code, headers, fpath, offset, length = service.downloads.file(body.job_id, self.headers.get("Range"))
                self.send_response(code)
                for k, v in (headers or {"Content-Length": "0"}).items():
                    self.send_header(k, v)
                self.end_headers()
                if fpath and length and method != "HEAD":
                    try:
                        with open(fpath, "rb") as f:
                            f.seek(offset)
                            left = length
                            while left > 0:
                                chunk = f.read(min(1 << 20, left))
                                if not chunk:
                                    break
                                self.wfile.write(chunk)
                                left -= len(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        pass  # client went away (DownloadManager pauses/retries with a Range)
                return
            if isinstance(body, FileBody):''')
open(p, "w").write(s)
print("patched")
