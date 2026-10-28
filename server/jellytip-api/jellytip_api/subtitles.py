"""Whole-season subtitle fetch via Jellyfin's RemoteSearch (OpenSubtitles / Bazarr providers)."""
import os
import re
import threading
import time
import uuid

from .jf import JfError

LANG3 = {"en": "eng", "es": "spa", "fr": "fra", "de": "deu", "it": "ita", "pt": "por", "nl": "nld", "ja": "jpn", "ko": "kor",
         "zh": "zho", "ru": "rus", "sv": "swe", "da": "dan", "no": "nor", "fi": "fin", "pl": "pol", "tr": "tur", "ar": "ara"}
QUOTA_HINT = re.compile(r"quota|limit|too many|429|allowed", re.I)
MAX_JOBS = 50


def lang3(code):
    c = (code or "eng").lower()
    return LANG3.get(c[:2], c) if len(c) == 2 else c


def has_lang(streams, lang):
    return any(s.get("Type") == "Subtitle" and (s.get("Language") or "").lower() in (lang, next((k for k, v in LANG3.items() if v == lang), lang))
               for s in streams or [])


def tokens(name):
    return set(re.findall(r"[a-z0-9]+", os.path.splitext(name or "")[0].lower()))


def pick(results, file_name):
    """Best candidate: hash match, then release-name overlap, then non-HI/non-machine, then downloads."""
    want = tokens(file_name)

    def score(r):
        return (bool(r.get("IsHashMatch")),
                len(want & tokens(r.get("Name"))) / (len(want) or 1),
                not r.get("HearingImpaired"), not (r.get("MachineTranslated") or r.get("AiTranslated")),
                (r.get("DownloadCount") or 0), r.get("CommunityRating") or 0)
    ok = [r for r in results if not r.get("Forced")]
    return max(ok, key=score) if ok else None


class Jobs:
    def __init__(self, jf):
        self.jf, self.jobs, self.lock = jf, {}, threading.Lock()

    def start(self, season_id, language):
        lang = lang3(language)
        sid = season_id.replace("-", "").lower()
        with self.lock:
            for j in self.jobs.values():
                if j["seasonId"] == sid and j["language"] == lang and j["state"] in ("queued", "running"):
                    return j["id"]
            if len(self.jobs) >= MAX_JOBS:
                for k in sorted(self.jobs, key=lambda k: self.jobs[k]["created"])[:10]:
                    del self.jobs[k]
            job = {"id": uuid.uuid4().hex[:12], "seasonId": sid, "language": lang, "state": "queued", "created": time.time(),
                   "episodes": [], "message": None}
            self.jobs[job["id"]] = job
        threading.Thread(target=self._run, args=(job,), daemon=True).start()
        return job["id"]

    def view(self, job_id):
        with self.lock:
            j = self.jobs.get(job_id)
            if not j:
                return None
            eps = [dict(e) for e in j["episodes"]]
        c = {}
        for e in eps:
            c[e["status"]] = c.get(e["status"], 0) + 1
        got = c.get("ok", 0)
        have = c.get("had", 0)
        out = {"id": j["id"], "seasonId": j["seasonId"], "language": j["language"], "state": j["state"], "message": j["message"],
               "total": len(eps), "got": got, "alreadyHad": have, "none": c.get("none", 0), "failed": c.get("error", 0),
               "remaining": c.get("pending", 0) + c.get("working", 0) + c.get("skipped", 0), "episodes": eps}
        out["summary"] = summary(out)
        return out

    def _set(self, job, **kw):
        with self.lock:
            job.update(kw)

    def _run(self, job):
        jf, lang = self.jf, job["language"]
        try:
            self._set(job, state="running")
            eps = jf.items(ParentId=job["seasonId"], IncludeItemTypes="Episode", Fields="MediaStreams,MediaSources,Path", SortBy="IndexNumber")
            rows = []
            for e in eps:
                streams = e.get("MediaStreams") or [s for ms in e.get("MediaSources") or [] for s in ms.get("MediaStreams") or []]
                rows.append({"id": e["Id"].replace("-", ""), "index": e.get("IndexNumber"), "name": e.get("Name"),
                             "status": "had" if has_lang(streams, lang) else "pending", "detail": None, "_file": os.path.basename(e.get("Path") or "")})
            with self.lock:
                job["episodes"] = rows
            errors = 0
            for row in rows:
                if row["status"] != "pending":
                    continue
                row["status"] = "working"
                try:
                    res = jf.call("GET", f"/Items/{row['id']}/RemoteSearch/Subtitles/{lang}", timeout=60) or []
                    best = pick(res, row["_file"])
                    if not best:
                        row["status"], row["detail"] = "none", "no subtitles found"
                        continue
                    jf.call("POST", f"/Items/{row['id']}/RemoteSearch/Subtitles/{best['Id']}", timeout=90)
                    row["status"], row["detail"] = "ok", f"{best.get('ProviderName')}: {best.get('Name')}"
                    errors = 0
                except JfError as ex:
                    if ex.status in (401, 403, 429) or QUOTA_HINT.search(ex.body or ""):
                        row["status"], row["detail"] = "skipped", "provider refused (daily quota)"
                        for r in rows:
                            if r["status"] == "pending":
                                r["status"] = "skipped"
                        self._set(job, message="Provider refused further downloads (daily quota). Try again tomorrow.")
                        break
                    row["status"], row["detail"] = "error", f"jellyfin {ex.status}"
                    errors += 1
                except Exception as ex:
                    row["status"], row["detail"] = "error", type(ex).__name__
                    errors += 1
                if errors >= 3:
                    for r in rows:
                        if r["status"] == "pending":
                            r["status"] = "skipped"
                    self._set(job, message="Stopped after repeated provider errors.")
                    break
                time.sleep(1)
            self._set(job, state="done")
        except Exception as ex:
            self._set(job, state="failed", message=f"{type(ex).__name__}")


def summary(v):
    n = v["total"]
    got_or_had = v["got"] + v["alreadyHad"]
    parts = [f"{got_or_had} of {n} episodes have {v['language']} subtitles"]
    if v["none"]:
        parts.append(f"{v['none']} none found")
    if v["failed"]:
        parts.append(f"{v['failed']} failed")
    if v["remaining"] and v["state"] != "running":
        parts.append(f"{v['remaining']} not attempted")
    return "; ".join(parts)
