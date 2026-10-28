#!/usr/bin/env python3
"""Download a 720p H.264 trailer for every Radarr movie / Sonarr series.

Trailers land in `<media folder>/trailers/<Folder Name>-trailer.mp4`, which
Jellyfin picks up as a local trailer. Idempotent, with retry backoff recorded
in a state file. Secrets are read at runtime from the Kometa config; nothing
sensitive is stored in this repo.

Exit codes: 0 ok, 2 disk guard tripped, 3 YouTube bot-check (run stopped).
"""
import argparse, fcntl, json, os, random, re, shutil, subprocess, sys, time
import urllib.parse, urllib.request
from pathlib import Path

HOME = Path.home()
WORK = HOME / "jellytip-trailers"
STATE = WORK / "state.json"
TMP = WORK / "tmp"
KOMETA = HOME / "Kometa/config/config.yml"
YTDLP = HOME / "bin/yt-dlp"
FORMAT = ("bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/"
          "b[height<=720][vcodec^=avc1]")
MIN_FREE_GB = 15
BACKOFF_H = [24, 72, 168, 720]  # retry delays by attempt count


def log(*a):
    print(time.strftime("%F %T"), *a, flush=True)


def load_cfg():
    import yaml
    c = yaml.safe_load(KOMETA.read_text())
    return c["tmdb"]["apikey"], c["radarr"], c["sonarr"]


def http_json(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    return json.load(urllib.request.urlopen(req, timeout=30))


def free_gb():
    out = subprocess.run(["quota", "-s"], capture_output=True, text=True).stdout
    nums = re.findall(r"(\d+(?:\.\d+)?)([KMGT])\s", out.splitlines()[-1] + " ")
    mult = {"K": 1 / 1024 ** 2, "M": 1 / 1024, "G": 1, "T": 1024}
    used, quota = (float(n) * mult[u] for n, u in nums[:2])
    return quota - used


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save_state(st):
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(st, indent=1, sort_keys=True))
    tmp.replace(STATE)


def pick_videos(tmdb_key, kind, tmdb_id):
    """Ordered candidate YouTube keys: Trailer>Teaser, official, en, biggest, newest."""
    d = http_json(f"https://api.themoviedb.org/3/{kind}/{tmdb_id}/videos?"
                  + urllib.parse.urlencode({"api_key": tmdb_key, "language": "en-US",
                                            "include_video_language": "en,null"}))
    vids = [v for v in d.get("results", []) if v.get("site") == "YouTube"]

    def rank(v):
        t = {"Trailer": 0, "Teaser": 1}.get(v.get("type"), 9)
        pub = int("".join(c for c in v.get("published_at", "") if c.isdigit()) or 0)
        return (t, not v.get("official"), v.get("iso_639_1") != "en", -(v.get("size") or 0), -pub)

    return [v["key"] for v in sorted((v for v in vids if v.get("type") in ("Trailer", "Teaser")), key=rank)]


def probe_h264(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                        "stream=codec_name,height", "-of", "csv=p=0", str(path)],
                       capture_output=True, text=True)
    return r.stdout.strip().startswith("h264")


YT_ID = re.compile(r"[A-Za-z0-9_-]{11}")


def download(key, dest):
    if not YT_ID.fullmatch(str(key)):
        return None, "bad youtube id"
    TMP.mkdir(parents=True, exist_ok=True)
    for f in TMP.glob("*"):
        f.unlink()
    r = subprocess.run([str(YTDLP), "-f", FORMAT, "--merge-output-format", "mp4",
                        "--no-playlist", "--no-progress", "--socket-timeout", "30",
                        "--max-filesize", "200M", "-o", str(TMP / "t.%(ext)s"),
                        f"https://www.youtube.com/watch?v={key}"],
                       capture_output=True, text=True, timeout=900)
    err = (r.stderr or "")[-600:]
    if "confirm you" in err and "bot" in err:
        raise RuntimeError("BOT")
    out = TMP / "t.mp4"
    if r.returncode != 0 or not out.exists():
        return None, err.strip().splitlines()[-1] if err.strip() else "yt-dlp failed"
    if not probe_h264(out):
        out.unlink()
        return None, "not h264"
    dest.parent.mkdir(exist_ok=True)
    shutil.move(str(out), dest)
    return dest.stat().st_size, ""


def items(kind_sel, rad, son):
    if kind_sel in ("movies", "all"):
        for m in http_json(rad["url"].rstrip("/") + "/api/v3/movie", {"X-Api-Key": rad["token"]}):
            if m.get("hasFile") and m.get("tmdbId"):
                yield "movie", f"movie:{m['tmdbId']}", m["tmdbId"], m["path"], m["title"]
    if kind_sel in ("tv", "all"):
        for s in http_json(son["url"].rstrip("/") + "/api/v3/series", {"X-Api-Key": son["token"]}):
            tid = s.get("tmdbId")
            if tid and s.get("statistics", {}).get("episodeFileCount", 0) > 0:
                yield "tv", f"tv:{tid}", tid, s["path"], s["title"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", default="all", choices=["movies", "tv", "all"])
    ap.add_argument("--limit", type=int, default=0, help="max downloads this run")
    ap.add_argument("--only", help="substring of title (testing)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    WORK.mkdir(exist_ok=True)
    lock = open(WORK / "lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        log("another run active"); return 0
    tmdb, rad, son = load_cfg()
    st = load_state()
    done = added = 0
    now = time.time()
    for kind, key, tid, path, title in items(a.kind, rad, son):
        if a.only and a.only.lower() not in title.lower():
            continue
        folder = Path(path)
        if not folder.is_dir():
            continue
        dest = folder / "trailers" / f"{folder.name}-trailer.mp4"
        if dest.exists():
            continue
        rec = st.get(key, {})
        if rec.get("next_retry", 0) > now:
            continue
        if a.limit and done >= a.limit:
            break
        if free_gb() < MIN_FREE_GB:
            log(f"DISK GUARD: free {free_gb():.1f} GB < {MIN_FREE_GB}; stopping"); save_state(st); return 2
        if a.dry_run:
            log("would fetch", title); done += 1; continue
        reason = "no trailer on TMDB"
        try:
            cands = pick_videos(tmdb, "movie" if kind == "movie" else "tv", tid)
            for ck in cands[:3]:
                size, err = download(ck, dest)
                if size:
                    st[key] = {"status": "done", "title": title, "yt": ck, "bytes": size}
                    added += size; reason = ""
                    log(f"OK {title} {size/1e6:.1f} MB ({ck})")
                    break
                reason = f"{ck}: {err}"
                time.sleep(random.uniform(3, 6))
        except RuntimeError:
            log("YouTube bot check hit; stopping run"); save_state(st); return 3
        except Exception as e:
            reason = f"error: {e}"[:300]
        if reason:
            n = rec.get("attempts", 0)
            st[key] = {"status": "failed", "title": title, "reason": reason, "attempts": n + 1,
                       "next_retry": now + BACKOFF_H[min(n, len(BACKOFF_H) - 1)] * 3600}
            log(f"FAIL {title}: {reason}")
        save_state(st)
        done += 1
        time.sleep(random.uniform(5, 15))
    log(f"run complete: processed {done}, bytes added {added}, free {free_gb():.1f} GB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
