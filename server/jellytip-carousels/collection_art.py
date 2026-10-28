#!/usr/bin/env python3
"""Generate Primary (2:3 mosaic poster), Thumb and Backdrop (16:9) art for Jellyfin collections lacking it.

Runs on the seedbox (palawan) after sync_carousels.py:
  ~/venvs/imgscore/bin/python collection_art.py [--force] [--only NAME] [--dry-run]

A collection is (re)generated when it has no Primary, or when art we made earlier is stale (fewer than half
of its current top-4 members match the top-4 used then). Art we did not make is left alone unless --force.
Env (~/jellytip-api/jellytip.env): JELLYFIN_URL, JELLYFIN_KEY. Optional JELLYFIN_USER (default ricki28).
"""
import argparse
import base64
import io
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.expanduser("~/jellytip-api/jellytip.env")
FONT = os.path.join(HERE, "fonts", "IS.ttf")
STATE = os.path.join(HERE, "art_state.json")
COMPANION = "http://127.0.0.1:18931"
UA = "jellytip-collection-art/1.0"
BG, FG = (10, 10, 10), (242, 242, 240)
EMOJI = re.compile("[\U0001F000-\U0001FAFF←-⯿️‍⃣]+")


def load_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                env[k.strip()] = v.strip().strip('"')
    return env


class Jf:
    def __init__(self, base, key):
        self.base = base.rstrip("/")
        self.hdr = {"User-Agent": UA, "Authorization": f'MediaBrowser Token="{key}"'}

    def raw(self, method, path, params=None, data=None, ctype=None, base=None, timeout=60):
        url = (base or self.base) + path + ("?" + urllib.parse.urlencode(params) if params else "")
        h = dict(self.hdr)
        if ctype:
            h["Content-Type"] = ctype
        req = urllib.request.Request(url, method=method, headers=h, data=data)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()

    def get(self, path, **params):
        body = self.raw("GET", path, params)
        return json.loads(body) if body else None


def clean_name(s):
    return re.sub(r"\s+", " ", EMOJI.sub("", s)).strip()


def font(size, wght=600):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([100, wght])
    except Exception:
        pass
    return f


def cover(img, w, h):
    img = img.convert("RGB")
    s = max(w / img.width, h / img.height)
    img = img.resize((max(w, round(img.width * s)), max(h, round(img.height * s))), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


def gradient(w, h, direction):
    """Black alpha ramp: 'bottom' (dark at bottom) or 'left' (dark at left)."""
    n = h if direction == "bottom" else w
    vals = []
    for i in range(n):
        t = i / (n - 1)
        a = (t - 0.3) / 0.6 if direction == "bottom" else (0.7 - t) / 0.7
        vals.append(int(235 * max(0.0, min(1.0, a)) ** 1.4))
    ramp = Image.new("L", (1, n) if direction == "bottom" else (n, 1))
    ramp.putdata(vals)
    return Image.new("RGB", (w, h), BG), ramp.resize((w, h))


def wrap(draw, text, fnt, maxw):
    lines, cur = [], ""
    for word in text.split():
        t = (cur + " " + word).strip()
        if draw.textlength(t, font=fnt) <= maxw or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def put_title(img, text, size, x, bottom, maxw, anchor_bottom=True, mid=None):
    d = ImageDraw.Draw(img)
    fnt = font(size)
    lines = wrap(d, text, fnt, maxw)
    lh = int(size * 1.2)
    y = bottom - lh * len(lines) if anchor_bottom else mid - lh * len(lines) // 2
    for ln in lines:
        d.text((x, y), ln, font=fnt, fill=FG)
        y += lh


def poster(images, name):
    W, H = 1000, 1500
    if len(images) >= 4:
        canvas = Image.new("RGB", (W, H), BG)
        for i, im in enumerate(images[:4]):
            canvas.paste(cover(im, 500, 750), ((i % 2) * 500, (i // 2) * 750))
    else:
        canvas = cover(images[0], W, H)
    layer, mask = gradient(W, H, "bottom")
    canvas.paste(layer, (0, 0), mask.point(lambda v: min(255, int(v * 1.12))))
    put_title(canvas, name, 54, 64, H - 72, W - 128)
    return canvas


def thumb(back, name):
    W, H = 1920, 1080
    canvas = cover(back, W, H)
    layer, mask = gradient(W, H, "left")
    canvas.paste(layer, (0, 0), mask)
    layer, mask = gradient(W, H, "bottom")
    canvas.paste(layer, (0, 0), mask.point(lambda v: int(v * 0.8)))
    put_title(canvas, name, 64, 96, H - 96, 900)
    return canvas


def jpeg(img):
    b = io.BytesIO()
    img.save(b, "JPEG", quality=88, optimize=True)
    return b.getvalue()


def members(jf, uid, box):
    kids = jf.get(f"/Users/{uid}/Items", ParentId=box["Id"], Fields="CommunityRating,BackdropImageTags,ImageTags")["Items"]
    kids.sort(key=lambda k: (k.get("CommunityRating") or 0), reverse=True)
    return kids


def fetch_img(jf, item_id, kind, idx=None, **q):
    path = f"/Items/{item_id}/Images/{kind}" + (f"/{idx}" if idx is not None else "")
    return Image.open(io.BytesIO(jf.raw("GET", path, q, timeout=120)))


def hero_index(jf, item):
    try:
        d = json.loads(jf.raw("GET", f"/v1/items/{item['Id']}/backdrops", base=COMPANION, timeout=15))
        i = d.get("heroIndex")
        if isinstance(i, int) and 0 <= i < len(item.get("BackdropImageTags") or []):
            return i
    except Exception:
        pass
    return 0


def upload(jf, box_id, kind, img):
    jf.raw("POST", f"/Items/{box_id}/Images/{kind}", data=base64.b64encode(jpeg(img)), ctype="image/jpeg")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out", help="also save generated JPEGs to this dir")
    args = ap.parse_args()
    env = load_env(ENV_FILE)
    jf = Jf(env["JELLYFIN_URL"], env["JELLYFIN_KEY"])
    uid = next(u["Id"] for u in jf.get("/Users") if u["Name"] == env.get("JELLYFIN_USER", "ricki28"))
    state = json.load(open(STATE)) if os.path.exists(STATE) else {}
    boxes = jf.get(f"/Users/{uid}/Items", IncludeItemTypes="BoxSet", Recursive="true", Fields="BackdropImageTags,ImageTags")["Items"]
    done = skipped = failed = 0
    for box in boxes:
        name = clean_name(box["Name"])
        if args.only and args.only.lower() not in box["Name"].lower():
            continue
        tags = box.get("ImageTags") or {}
        mine = state.get(box["Id"])
        kids = members(jf, uid, box)
        top = [k["Id"] for k in kids if (k.get("ImageTags") or {}).get("Primary")][:4]
        need = {"Primary": "Primary" not in tags, "Thumb": "Thumb" not in tags, "Backdrop": not box.get("BackdropImageTags")}
        stale = bool(mine) and len(set(top) & set(mine["top"])) * 2 < max(len(top), 1)
        if args.force or stale:
            need = {k: True for k in need}
        if not any(need.values()) or not top:
            skipped += 1
            continue
        print(f"{name}: {[k for k, v in need.items() if v]}{' (stale)' if stale else ''}", flush=True)
        if args.dry_run:
            continue
        try:
            if need["Primary"]:
                imgs = [fetch_img(jf, i, "Primary", maxWidth=600) for i in top]
                p = poster(imgs, name)
                upload(jf, box["Id"], "Primary", p)
                if args.out:
                    p.save(os.path.join(args.out, f"{box['Id']}-poster.jpg"), quality=88)
            if need["Thumb"] or need["Backdrop"]:
                src = next((k for k in kids if k.get("BackdropImageTags")), None)
                if src:
                    back = fetch_img(jf, src["Id"], "Backdrop", hero_index(jf, src), maxWidth=1920)
                    t = thumb(back, name)
                    if need["Thumb"]:
                        upload(jf, box["Id"], "Thumb", t)
                    if need["Backdrop"]:
                        if box.get("BackdropImageTags") and (args.force or stale):
                            for i in reversed(range(len(box["BackdropImageTags"]))):
                                jf.raw("DELETE", f"/Items/{box['Id']}/Images/Backdrop/{i}")
                        upload(jf, box["Id"], "Backdrop", t)
                    if args.out:
                        t.save(os.path.join(args.out, f"{box['Id']}-thumb.jpg"), quality=88)
            state[box["Id"]] = {"name": box["Name"], "top": top}
            done += 1
        except Exception as e:
            failed += 1
            print(f"  FAILED {name}: {e}", file=sys.stderr)
    json.dump(state, open(STATE, "w"), indent=1)
    print(f"done={done} skipped={skipped} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
