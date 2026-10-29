# Companion patch: prepared phone downloads

`downloads.py` is a drop-in module for the Jellytip companion API (`~/jellytip-api/jellytip_api/downloads.py`).
It is **not applied yet** (server.py was being edited by someone else); these are the changes to make.
Tests: `python3 -m unittest web/server/test_downloads.py` (stdlib, Jellyfin stubbed).

## What it does
- `POST /v1/downloads/prepare {itemId, quality: "720"|"1080"}`: queues a server-side transcode of the item to a phone
  MP4 (H.264 + stereo AAC, 1280x720 @ 2.5 Mbps or 1920x1080 @ 5 Mbps) using Jellyfin's own transcoder with the admin
  key, written to `DOWNLOADS_DIR` (default `~/jellytip-downloads`). Answers `202 {jobId, state, progress}`, or `200` when
  the file is already there. One job per item+quality, run one at a time. If `ffmpeg` is on PATH the result gets a
  `+faststart` remux.
- `GET /v1/downloads/jobs/{jobId}` → `{jobId, state: queued|running|ready|failed, progress 0..1, bytes, fileUrl?}`.
- `GET /v1/downloads/files/{jobId}`: the MP4 with `Range` support (206/416), so Android's DownloadManager can resume.
- Housekeeping: files unused for 7 days are deleted, and the folder is held under 60 GB, oldest first.
  The seedbox quota is the real limit; tune `MAX_BYTES` in the module.

The app sends the user's Jellyfin token (`Authorization: MediaBrowser Token="…"`) on all three, like the other routes,
so `route()`'s existing Jellyfin-token check covers auth. No new secrets.

## server.py changes

1. Import and construct (in `Service.__init__`):
   ```python
   from .downloads import Downloads, Busy as DownloadsBusy
   ...
   self.downloads = Downloads(self.jf_url, env.get("JELLYFIN_KEY"),
                              env.get("DOWNLOADS_DIR") or os.path.expanduser("~/jellytip-downloads"))
   ```
2. Route regexes next to the others:
   ```python
   DL_JOB_RE = re.compile(r"^/v1/downloads/jobs/([0-9a-f]{16})$")
   DL_FILE_RE = re.compile(r"^/v1/downloads/files/([0-9a-f]{16})$")
   ```
3. In `route_post`, before the final 404. Check that the caller can see the item with their own token, then queue:
   ```python
   if path == "/v1/downloads/prepare":
       iid = str(body.get("itemId") or "").replace("-", "").lower()
       if not re.fullmatch(r"[0-9a-f]{32}", iid):
           return 400, {"error": "bad itemId"}
       try:
           code, it = self._jf_me(f"/Items/{iid}", jf_token, ip)  # caller's own token: 404/403 if they can't see it
       except OSError:
           return 503, {"error": "jellyfin unavailable"}
       if code != 200 or not it:
           return 404, {"error": "no such item"}
       try:
           return self.downloads.prepare(iid, str(body.get("quality") or "720"), it.get("RunTimeTicks"))
       except DownloadsBusy:
           return 429, {"error": "download queue full, try later"}
   ```
   (`_jf_me` returns the JSON for any path other than `/System/Info`.)
4. In `_route_authed` (GET):
   ```python
   m = DL_JOB_RE.match(path)
   if m:
       return self.downloads.status(m.group(1))
   m = DL_FILE_RE.match(path)
   if m:
       return 200, RangeFile(m.group(1))   # served by the handler, which has the Range header
   ```
5. File serving with ranges. Add a body type, and in `make_handler.do_GET` handle it before the `FileBody` branch:
   ```python
   class RangeFile:
       def __init__(self, job_id):
           self.job_id = job_id
   ```
   ```python
   if isinstance(body, RangeFile):
       code, headers, fpath, offset, length = service.downloads.file(body.job_id, self.headers.get("Range"))
       self.send_response(code)
       for k, v in (headers or {"Content-Length": "0"}).items():
           self.send_header(k, v)
       self.end_headers()
       if fpath and length:
           with open(fpath, "rb") as f:
               f.seek(offset)
               left = length
               while left > 0:
                   chunk = f.read(min(1 << 20, left))
                   if not chunk:
                       break
                   self.wfile.write(chunk)
                   left -= len(chunk)
       return
   ```
   `ThreadingHTTPServer` is already used, so a long download doesn't block other requests. nginx: if
   `proxy_buffering`/`proxy_max_temp_file_size` are at their defaults for `/jellytip-api/`, large files are spooled to
   disk by nginx first; add `proxy_buffering off;` to the `jellytip-api` location for these.
6. Rate limit: the per-IP request bucket (15/s) is fine, because the app polls job status every 2 s per preparing item.

## Notes
- CPU: a 4K HEVC → 1080p job runs at about 1x realtime on the seedbox (playback spike, 29-Oct), so a 2 h film takes
  about 2 h to prepare. 720p is quicker. Jobs are serialised so they don't fight live playback.
- Until this is deployed the app falls back to a live transcode download for non-phone-friendly titles. That works
  but can't resume and isn't seekable until the file is complete.
