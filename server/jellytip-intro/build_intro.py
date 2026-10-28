#!/usr/bin/env python3
"""Nightly: cut 5 snippets from the newest films, dissolve them together, end on the Jellytip card.
Writes out/intro.mp4 + out/intro.json atomically. Needs ffmpeg/ffprobe, numpy, opencv (the imgscore venv). Stdlib otherwise."""
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")  # seedbox address-space limit: many threads exhaust it
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from datetime import date

import cv2
import numpy as np

cv2.setNumThreads(1)

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
ENV = os.path.expanduser("~/jellytip-api/jellytip.env")
SNIPS, SNIP_LEN, XFADE = 5, 1.4, 0.3
CARD_LEN, CARD_XFADE = 1.8, 0.6
FPS = 24
# Plays in a family lounge: no war/horror/crime-thriller/documentary sources, and no very dark frames
SKIP_GENRES = {"Documentary", "War", "Horror", "Thriller", "Crime", "War & Politics", "Suspense"}
MIN_LUMA = 60
CASCADE = cv2.CascadeClassifier(os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml"))


def load_env():
    env = {}
    with open(ENV) as f:
        for line in f:
            k, _, v = line.strip().partition("=")
            if k and not k.startswith("#"):
                env[k] = v.strip().strip('"')
    return env


def jf_movies(env):
    base, key = env["JELLYFIN_URL"].rstrip("/"), env["JELLYFIN_KEY"]
    hdr = {"Authorization": f'MediaBrowser Token="{key}"'}

    def get(path, **q):
        with urllib.request.urlopen(urllib.request.Request(base + path + "?" + urllib.parse.urlencode(q), headers=hdr), timeout=30) as r:
            return json.load(r)
    users = get("/Users")
    uid = next((u["Id"] for u in users if u["Name"] == env.get("JELLYFIN_USER", "ricki28")), users[0]["Id"])
    return get(f"/Users/{uid}/Items", Recursive="true", IncludeItemTypes="Movie", SortBy="DateCreated", SortOrder="Descending",
               Limit="80", Fields="Genres,Path,DateCreated,ProductionYear")["Items"]


def run(cmd, **kw):
    try:
        return subprocess.run(cmd, check=True, capture_output=True, **kw)
    except subprocess.CalledProcessError as e:
        print((e.stderr or b"").decode("utf-8", "replace")[-600:], flush=True)
        raise


def probe(path):
    out = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
               "stream=width,height,color_transfer:format=duration", "-of", "json", path]).stdout
    j = json.loads(out)
    s = j["streams"][0]
    return int(s["width"]), int(s["height"]), float(j["format"]["duration"]), s.get("color_transfer", "")


def grab(path, t, width=960):
    raw = run(["ffmpeg", "-v", "error", "-ss", f"{t:.2f}", "-i", path, "-map", "0:v:0", "-frames:v", "1", "-an", "-sn",
               "-vf", f"scale={width}:-2", "-f", "image2pipe", "-c:v", "bmp", "-"]).stdout
    return cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)


def active_box(gray):
    """Bounding box of the picture inside letterbox/pillarbox bars (bars are near-pure black)."""
    h, w = gray.shape
    rows = np.where(np.percentile(gray, 99, axis=1) > 40)[0]
    cols = np.where(np.percentile(gray, 99, axis=0) > 40)[0]
    if len(rows) < h * 0.4 or len(cols) < w * 0.4:
        return 0, 0, w, h
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1


def has_burned_subs(m):
    """Hard-coded subtitles: a thin band of near-white pixels spanning the lower picture, centred."""
    h, w = m.shape
    band = m[int(h * 0.68):int(h * 0.97)] >= 235
    ys, xs = np.nonzero(band)
    if len(xs) < 300 or band.mean() > 0.06:
        return False
    rows = band.sum(axis=1)
    k = max(2, int(h * 0.12))
    densest = max(rows[i:i + k].sum() for i in range(0, max(1, len(rows) - k)))
    return densest > 0.85 * rows.sum() and (xs.max() - xs.min()) > 0.2 * w and abs((xs.max() + xs.min()) / 2 - w / 2) < 0.2 * w


def score_frames(frames):
    """Higher is better; None = reject (too dark/bright). Penalises hard cuts inside the snippet."""
    grays = [cv2.cvtColor(f, cv2.COLOR_BGR2GRAY) for f in frames]
    box = active_box(grays[len(grays) // 2])
    x0, y0, x1, y1 = box
    mids = [g[y0:y1, x0:x1] for g in grays]
    means = [float(m.mean()) for m in mids]
    if not all(MIN_LUMA <= m <= 200 for m in means):
        return None, box, None
    sharp = float(np.mean([min(1.0, np.log1p(cv2.Laplacian(m, cv2.CV_32F).var()) / np.log1p(400)) for m in mids]))
    expo = 1 - abs(float(np.mean(means)) - 115) / 100
    hists = [cv2.calcHist([m], [0], None, [32], [0, 256]) for m in mids]
    cut = min(cv2.compareHist(a, b, cv2.HISTCMP_CORREL) for a, b in zip(hists, hists[1:]))
    skin = max(float(((lambda h: (h[..., 0] <= 25) & (h[..., 1] > 40) & (h[..., 1] < 170) & (h[..., 2] > 70))(cv2.cvtColor(f, cv2.COLOR_BGR2HSV))).mean())
               for f in frames)  # big skin-tone areas = intimate close-ups; this plays in a family lounge
    subs = any(has_burned_subs(m) for m in mids)
    face = None
    faces = CASCADE.detectMultiScale(mids[len(mids) // 2], 1.1, 5, minSize=(40, 40))
    if len(faces):
        fx, fy, fw, fh = max(faces, key=lambda r: r[2] * r[3])
        face = (x0 + fx + fw / 2, y0 + fy + fh / 2)
    return sharp + 0.5 * expo + (0.6 if face else 0) - (1.5 if cut < 0.8 else 0) - (3 if subs else 0) - (1.5 if skin > 0.15 else 0), box, face


def best_snippet(path, w, h, dur):
    """Pick the best of ~6 start times between 25% and 60% of runtime. Returns (score, start, crop 'w:h:x:y')."""
    best = None
    for i in range(6):
        t0 = dur * (0.25 + 0.35 * i / 5)
        try:
            frames = [grab(path, t0 + o) for o in (0.0, SNIP_LEN / 2, SNIP_LEN - 0.1)]
        except subprocess.CalledProcessError:
            continue
        if any(f is None for f in frames):
            continue
        sc, (bx0, by0, bx1, by1), face = score_frames(frames)
        if sc is None:
            continue
        if best is None or sc > best[0]:
            k = w / frames[0].shape[1]
            bw, bh = (bx1 - bx0) * k, (by1 - by0) * k
            cw, ch = (bh * 16 / 9, bh) if bw / bh > 16 / 9 else (bw, bw * 9 / 16)
            fx = (face[0] - bx0) * k if face else bw / 2  # face-weighted centre, clamped inside the picture
            fy = (face[1] - by0) * k if face else bh / 2
            cx = min(max(fx, cw / 2), bw - cw / 2) + bx0 * k
            cy = min(max(bh / 2 + (fy - bh / 2) * 0.5, ch / 2), bh - ch / 2) + by0 * k
            crop = f"{int(cw) // 2 * 2}:{int(ch) // 2 * 2}:{int(cx - cw / 2) // 2 * 2}:{int(cy - ch / 2) // 2 * 2}"
            best = (sc, t0, crop)
    return best


def make_segment(path, t0, crop, out):
    vf = (f"crop={crop},scale=1920:1080:flags=lanczos,"
          f"scale=w='1920*(1+0.045*t/{SNIP_LEN})':h='1080*(1+0.045*t/{SNIP_LEN})':eval=frame:flags=bicubic,crop=1920:1080,"
          "curves=all='0/0 0.25/0.225 0.75/0.775 1/1',eq=saturation=1.04:contrast=1.03,"
          f"fps={FPS},format=yuv420p")
    run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t0:.2f}", "-i", path, "-t", str(SNIP_LEN), "-map", "0:v:0", "-an", "-sn",
         "-vf", vf, "-c:v", "libx264", "-crf", "12", "-preset", "fast", out])


def main():
    env = load_env()
    cands = jf_movies(env)
    live = [m for m in cands if not set(m.get("Genres") or []) & SKIP_GENRES]
    live_action = [m for m in live if "Animation" not in (m.get("Genres") or [])]
    ordered = live_action + [m for m in live if m not in live_action]  # animation only if too few live-action films
    work = tempfile.mkdtemp(prefix="intro-", dir=HERE)
    picked, segs = [], []
    try:
        for m in ordered:
            if len(picked) == SNIPS:
                break
            p = m.get("Path")
            if not p or not os.path.exists(p):
                continue
            try:
                w, h, dur, trc = probe(p)
                if trc in ("smpte2084", "arib-std-b67") or dur < 1800:  # HDR needs tonemapping: skip rather than ship washed-out frames
                    continue
                best = best_snippet(p, w, h, dur)
                if not best or best[0] < 0.3:  # every candidate had burned-in subs / cuts / close-ups: try the next film
                    continue
                seg = os.path.join(work, f"seg{len(picked)}.mp4")
                make_segment(p, best[1], best[2], seg)
            except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as e:
                print("skip", m.get("Name"), type(e).__name__, flush=True)
                continue
            picked.append(m)
            segs.append(seg)
            print("picked", m["Name"], m.get("ProductionYear"), f"score={best[0]:.2f} t={best[1]:.0f}s", flush=True)
        if len(picked) < SNIPS:
            sys.exit(f"only {len(picked)} usable films; keeping previous intro")
        card = os.path.join(HERE, "assets", "endcard.png")
        cmd = ["ffmpeg", "-v", "error", "-y", "-filter_complex_threads", "1"]  # seedbox caps address space (ulimit -v)
        for s in segs:
            cmd += ["-threads", "1", "-i", s]
        cmd += ["-loop", "1", "-framerate", str(FPS), "-t", str(CARD_LEN), "-i", card]
        parts = [f"[{i}:v]settb=1/{FPS},fps={FPS},format=yuv420p[n{i}]" for i in range(SNIPS + 1)]  # xfade needs identical timebases
        last, length = "n0", SNIP_LEN
        for i in range(1, SNIPS):
            off = length - XFADE
            parts.append(f"[{last}][n{i}]xfade=transition=fade:duration={XFADE}:offset={off:.2f}[x{i}]")
            last, length = f"x{i}", off + SNIP_LEN
        parts.append(f"[{last}][n{SNIPS}]xfade=transition=fade:duration={CARD_XFADE}:offset={length - CARD_XFADE:.2f},format=yuv420p[v]")
        tmp = os.path.join(work, "intro.mp4")
        cmd += ["-filter_complex", ";".join(parts), "-map", "[v]", "-an", "-r", str(FPS), "-c:v", "libx264", "-profile:v", "high",
                "-b:v", "8M", "-maxrate", "10M", "-bufsize", "16M", "-preset", "medium", "-threads", "4", "-x264-params", "rc-lookahead=20", "-g", str(FPS * 2), "-movflags", "+faststart", tmp]
        run(cmd)
        total = float(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", tmp]).stdout)
        ids = [m["Id"] for m in picked]
        version = hashlib.sha1((",".join(ids) + date.today().isoformat()).encode()).hexdigest()[:12]
        meta = {"version": version, "titles": [f'{m["Name"]} ({m.get("ProductionYear", "")})'.replace(" ()", "") for m in picked],
                "ids": ids, "duration": round(total, 2), "bytes": os.path.getsize(tmp), "built": date.today().isoformat()}
        os.makedirs(OUT, exist_ok=True)
        os.replace(tmp, os.path.join(OUT, "intro.mp4.new"))
        os.replace(os.path.join(OUT, "intro.mp4.new"), os.path.join(OUT, "intro.mp4"))
        with open(os.path.join(OUT, "intro.json.new"), "w") as f:
            json.dump(meta, f)
        os.replace(os.path.join(OUT, "intro.json.new"), os.path.join(OUT, "intro.json"))
        print(json.dumps(meta, ensure_ascii=False))
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
