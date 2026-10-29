"""Prepared phone downloads for the Jellytip companion API (drop-in module: jellytip_api/downloads.py). Stdlib only.

The phone app downloads originals when they are already phone-friendly (MP4 + H.264 + AAC, <= 1080p). Everything else
(HEVC/AV1/MKV/DTS...) is prepared here first: Jellyfin transcodes it to a phone MP4 into a file on the seedbox, and the
app then downloads that file with Android's DownloadManager, which can resume because this module serves byte ranges.
A live transcode streamed straight to the phone could not resume and would run at transcode speed.

One job per (item, quality); jobs run one at a time (the seedbox CPU is the limit: ~1x realtime for 4K HEVC -> 1080p).
Finished files are kept for TTL_DAYS after their last request and the folder is held under MAX_BYTES (oldest first).
See web/server/README.md for the routes to add to server.py.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

QUALITIES = {"720": {"w": 1280, "h": 720, "vbr": 2_500_000}, "1080": {"w": 1920, "h": 1080, "vbr": 5_000_000}}
ABR = 160_000
JOB_RE = re.compile(r"^[0-9a-f]{16}$")
ITEM_RE = re.compile(r"^[0-9a-f]{32}$")
TTL_DAYS = 7
MAX_BYTES = 20 * 10**9  # the seedbox quota is tight (~150 GB free on 29-Oct)
MIN_FREE_GB = 40  # refuse new jobs below this much quota headroom (same spirit as the Get-it block at 30 GB)
NICE = 19
MAX_QUEUED = 30
CHUNK = 1 << 20


class Busy(Exception):
    pass


class LowStorage(Exception):
    pass


def job_id(item_id, quality):
    return hashlib.sha256(f"{item_id}:{quality}".encode()).hexdigest()[:16]


def parse_range(header, size):
    """(start, end_inclusive) for a single 'bytes=a-b' / 'bytes=a-' / 'bytes=-n' range, or None (whole file / invalid)."""
    m = re.fullmatch(r"bytes=(\d*)-(\d*)", (header or "").strip())
    if not m or (not m.group(1) and not m.group(2)) or size <= 0:
        return None
    if m.group(1):
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else size - 1
    else:
        start, end = max(0, size - int(m.group(2))), size - 1
    if start >= size or end < start:
        return None
    return start, min(end, size - 1)


class Downloads:
    def __init__(self, jf_url, jf_key, root, ffmpeg=None, fetch=None, clock=time.time, free_gb=None, proc_root="/proc"):
        self.jf_url, self.jf_key = (jf_url or "").rstrip("/"), jf_key
        self.root = root
        os.makedirs(root, exist_ok=True)
        self.ffmpeg = ffmpeg if ffmpeg is not None else shutil.which("ffmpeg")
        self.fetch = fetch or self._fetch
        self.clock = clock
        self.free_gb = free_gb  # callable -> GB of quota left, or None to skip the check
        self.proc_root = proc_root
        self.lock = threading.Lock()
        self.jobs = {}  # id -> dict(state, item, quality, progress, bytes, error, expected)
        self.queue = []
        self.worker = None
        for name in os.listdir(root):  # finished files survive restarts
            if name.endswith(".mp4") and JOB_RE.fullmatch(name[:-4]):
                self.jobs[name[:-4]] = {"state": "ready", "progress": 1.0, "bytes": os.path.getsize(os.path.join(root, name))}
            elif name.endswith(".part"):
                os.remove(os.path.join(root, name))

    # ---------- public ----------

    def prepare(self, item_id, quality, runtime_ticks=None, source_path=None):
        """Start (or join) the job for this item+quality. Returns (http_status, body)."""
        item_id = (item_id or "").replace("-", "").lower()
        if not ITEM_RE.fullmatch(item_id) or quality not in QUALITIES:
            return 400, {"error": "itemId (32 hex) and quality (720|1080) required"}
        jid = job_id(item_id, quality)
        with self.lock:
            job = self.jobs.get(jid)
            if job and job["state"] in ("queued", "running", "ready"):
                if job["state"] == "ready":
                    self._touch(jid)
                return (200 if job["state"] == "ready" else 202), self._view(jid)
            if len(self.queue) >= MAX_QUEUED:
                raise Busy()
            if self.free_gb is not None:
                try:
                    free = self.free_gb()
                except Exception:  # noqa: BLE001: quota unknown: don't block on it
                    free = None
                if free is not None and free < MIN_FREE_GB:
                    raise LowStorage()
            secs = (runtime_ticks or 0) / 10_000_000
            expected = int((QUALITIES[quality]["vbr"] + ABR) * secs / 8) if secs else None
            self.jobs[jid] = {"state": "queued", "item": item_id, "quality": quality, "progress": 0.0, "bytes": 0, "expected": expected,
                              "source": source_path}
            self.queue.append(jid)
            if not (self.worker and self.worker.is_alive()):
                self.worker = threading.Thread(target=self._run, daemon=True)
                self.worker.start()
        return 202, self._view(jid)

    def status(self, jid):
        with self.lock:
            return (200, self._view(jid)) if jid in self.jobs else (404, {"error": "no such job"})

    def file(self, jid, range_header=None):
        """For GET /v1/downloads/files/{jobId}: (status, headers, path, offset, length) or (404, None, None, 0, 0)."""
        path = os.path.join(self.root, f"{jid}.mp4")
        if not JOB_RE.fullmatch(jid or "") or not os.path.isfile(path):
            return 404, None, None, 0, 0
        self._touch(jid)
        size = os.path.getsize(path)
        headers = {"Content-Type": "video/mp4", "Accept-Ranges": "bytes", "ETag": f'"{jid}-{size}"', "Cache-Control": "private, no-store"}
        r = parse_range(range_header, size)
        if range_header and r is None:
            return 416, {**headers, "Content-Range": f"bytes */{size}"}, None, 0, 0
        if r:
            start, end = r
            return 206, {**headers, "Content-Range": f"bytes {start}-{end}/{size}", "Content-Length": str(end - start + 1)}, path, start, end - start + 1
        return 200, {**headers, "Content-Length": str(size)}, path, 0, size

    # ---------- worker ----------

    def _view(self, jid):
        j = self.jobs[jid]
        out = {"jobId": jid, "state": j["state"], "progress": round(j.get("progress") or 0, 3), "bytes": j.get("bytes", 0)}
        if j["state"] == "ready":
            out["fileUrl"] = f"v1/downloads/files/{jid}"  # relative to the API base
        if j.get("error"):
            out["error"] = j["error"]
        return out

    def _touch(self, jid):
        try:
            os.utime(os.path.join(self.root, f"{jid}.mp4"))
        except OSError:
            pass

    def _run(self):
        while True:
            with self.lock:
                if not self.queue:
                    return
                jid = self.queue.pop(0)
                job = self.jobs[jid]
                job["state"] = "running"
            try:
                self._prepare_one(jid, job)
                with self.lock:
                    job.update(state="ready", progress=1.0, bytes=os.path.getsize(os.path.join(self.root, f"{jid}.mp4")))
            except Exception as e:  # noqa: BLE001: any failure ends this job only
                print("download prepare failed", jid, type(e).__name__, e, flush=True)
                with self.lock:
                    job.update(state="failed", error="The server couldn't prepare this title")
            self._cleanup()

    def _transcode_url(self, item_id, quality):
        q = QUALITIES[quality]
        params = {"Static": "false", "VideoCodec": "h264", "AudioCodec": "aac", "MaxWidth": q["w"], "MaxHeight": q["h"],
                  "VideoBitrate": q["vbr"], "AudioBitrate": ABR, "MaxAudioChannels": 2, "DeviceId": "jellytip-downloads",
                  "PlaySessionId": f"jtdl{job_id(item_id, quality)}"}
        return f"{self.jf_url}/Videos/{item_id}/stream.mp4?{urllib.parse.urlencode(params)}"

    def _fetch(self, url):
        req = urllib.request.Request(url, headers={"Authorization": f'MediaBrowser Token="{self.jf_key}"'})
        return urllib.request.urlopen(req, timeout=120)

    def _renice_transcoder(self, source_path, stop):
        """Jellyfin runs the transcode (same user): find its ffmpeg reading this source and drop it to nice 19, so a
        prepared download never competes with someone watching."""
        while not stop.wait(3):
            try:
                pids = [p for p in os.listdir(self.proc_root) if p.isdigit()]
            except OSError:
                return
            for pid in pids:
                try:
                    with open(os.path.join(self.proc_root, pid, "comm")) as f:
                        if f.read().strip() != "ffmpeg":
                            continue
                    with open(os.path.join(self.proc_root, pid, "cmdline"), "rb") as f:
                        if source_path.encode() not in f.read():
                            continue
                    if os.getpriority(os.PRIO_PROCESS, int(pid)) < NICE:
                        os.setpriority(os.PRIO_PROCESS, int(pid), NICE)
                except (OSError, ValueError):
                    continue

    def _prepare_one(self, jid, job):
        part = os.path.join(self.root, f"{jid}.part")
        final = os.path.join(self.root, f"{jid}.mp4")
        stop = threading.Event()
        if job.get("source"):
            threading.Thread(target=self._renice_transcoder, args=(job["source"], stop), daemon=True).start()
        try:
            self._transfer(job, part)
        finally:
            stop.set()
        self._finish(part, final)

    def _transfer(self, job, part):
        with self.fetch(self._transcode_url(job["item"], job["quality"])) as r, open(part, "wb") as f:
            while True:
                buf = r.read(CHUNK)
                if not buf:
                    break
                f.write(buf)
                with self.lock:
                    job["bytes"] += len(buf)
                    if job.get("expected"):
                        job["progress"] = min(0.98, job["bytes"] / job["expected"])

    def _finish(self, part, final):
        if os.path.getsize(part) < 1_000_000:
            os.remove(part)
            raise RuntimeError("transcode output too small")
        if self.ffmpeg:
            # Jellyfin streams fragmented MP4; a faststart remux gives players a proper index (quick seeking)
            fixed = part + ".mp4"
            p = subprocess.run(["nice", "-n", str(NICE), self.ffmpeg, "-v", "error", "-y", "-i", part, "-c", "copy", "-movflags", "+faststart", fixed],
                               capture_output=True, timeout=3600)
            if p.returncode == 0 and os.path.getsize(fixed) > 1_000_000:
                os.replace(fixed, part)
            elif os.path.exists(fixed):
                os.remove(fixed)
        os.replace(part, final)

    def _cleanup(self):
        """Drop files unused for TTL_DAYS, then the oldest until the folder is under MAX_BYTES."""
        now = self.clock()
        files = []
        for name in os.listdir(self.root):
            p = os.path.join(self.root, name)
            if name.endswith(".mp4"):
                st = os.stat(p)
                files.append((st.st_mtime, st.st_size, p, name[:-4]))
        files.sort()
        total = sum(f[1] for f in files)
        for mtime, size, p, jid in files:
            if now - mtime > TTL_DAYS * 86400 or total > MAX_BYTES:
                os.remove(p)
                total -= size
                with self.lock:
                    self.jobs.pop(jid, None)
