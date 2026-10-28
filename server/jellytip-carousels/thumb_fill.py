#!/usr/bin/env python3
"""Fill missing Jellyfin Thumb (titled 16:9 art) for Movies + Series. Never overwrites an existing Thumb unless --force.

  ~/venvs/imgscore/bin/python thumb_fill.py [--dry-run] [--force] [--only TITLE] [--out DIR]

Sources, in order: (a) fanart.tv moviethumb/tvthumb (only if FANART_KEY is in ~/jellytip-api/jellytip.env;
none is configured today, so skipped), (b) TMDB English backdrop (title usually baked in), >=1280 wide, most voted,
(c) generated: a photo-ranked backdrop that is NOT the home hero image + the clearlogo (typeset title fallback).
Env: JELLYFIN_URL, JELLYFIN_KEY, TMDB_KEY. Logs one line per item with the source used.
"""
import argparse
import io
import json
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import collection_art as ca  # noqa: E402

W, H = 1920, 1080


def http_json(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": ca.UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def http_img(url):
    req = urllib.request.Request(url, headers={"User-Agent": ca.UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return Image.open(io.BytesIO(r.read()))


def fanart_thumb(item, key):
    tmdb = (item.get("ProviderIds") or {}).get("Tmdb")
    tvdb = (item.get("ProviderIds") or {}).get("Tvdb")
    ident, kind, field = (tmdb, "movies", "moviethumb") if item["Type"] == "Movie" else (tvdb, "tv", "tvthumb")
    if not key or not ident:
        return None
    try:
        d = http_json(f"https://webservice.fanart.tv/v3/{kind}/{ident}?api_key={key}")
    except Exception:
        return None
    rows = [r for r in d.get(field, []) if r.get("lang") in ("en", "")]
    rows.sort(key=lambda r: -int(r.get("likes") or 0))
    return rows[0]["url"] if rows else None


def tmdb_en_backdrop(item, key):
    tmdb = (item.get("ProviderIds") or {}).get("Tmdb")
    if not tmdb:
        return None
    kind = "movie" if item["Type"] == "Movie" else "tv"
    try:
        d = http_json(f"https://api.themoviedb.org/3/{kind}/{tmdb}/images?api_key={key}&include_image_language=en")
    except Exception:
        return None
    rows = [b for b in d.get("backdrops", []) if b.get("iso_639_1") == "en" and b.get("width", 0) >= 1280]
    rows.sort(key=lambda b: (-(b.get("vote_average") or 0), -b.get("width", 0)))
    return f"https://image.tmdb.org/t/p/w1920{rows[0]['file_path']}" if rows else None


def pick_backdrop_index(jf, item):
    """Best photo-ranked backdrop index that differs from the hero; falls back to any index."""
    n = len(item.get("BackdropImageTags") or [])
    if not n:
        return None
    try:
        d = json.loads(jf.raw("GET", f"/v1/items/{item['Id']}/backdrops", base=ca.COMPANION, timeout=15))
        hero = d.get("heroIndex")
        for r in d.get("backdrops", []):
            if r["index"] != hero and 0 <= r["index"] < n:
                return r["index"]
    except Exception:
        hero = 0
    return 1 if n > 1 else 0


def generate(jf, item):
    idx = pick_backdrop_index(jf, item)
    if idx is None:
        return None
    img = ca.cover(ca.fetch_img(jf, item["Id"], "Backdrop", idx, maxWidth=W), W, H)
    layer, mask = ca.gradient(W, H, "bottom")
    img.paste(layer, (0, 0), mask)
    layer, mask = ca.gradient(W, H, "left")
    img.paste(layer, (0, 0), mask.point(lambda v: int(v * 0.6)))
    if (item.get("ImageTags") or {}).get("Logo"):
        logo = ca.fetch_img(jf, item["Id"], "Logo", maxWidth=900).convert("RGBA")
        s = min(760 / logo.width, 230 / logo.height)
        logo = logo.resize((max(1, round(logo.width * s)), max(1, round(logo.height * s))), Image.LANCZOS)
        img.paste(logo, (96, H - 96 - logo.height), logo)
        return img, "generated+logo"
    ca.put_title(img, item["Name"], 84, 96, H - 96, 1100)
    return img, "generated+text"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--out")
    args = ap.parse_args()
    env = ca.load_env(ca.ENV_FILE)
    jf = ca.Jf(env["JELLYFIN_URL"], env["JELLYFIN_KEY"])
    uid = next(u["Id"] for u in jf.get("/Users") if u["Name"] == env.get("JELLYFIN_USER", "ricki28"))
    items = jf.get(f"/Users/{uid}/Items", IncludeItemTypes="Movie,Series", Recursive="true",
                   Fields="ImageTags,BackdropImageTags,ProviderIds")["Items"]
    missing = [i for i in items if args.force or "Thumb" not in (i.get("ImageTags") or {})]
    if args.only:
        missing = [i for i in missing if args.only.lower() in i["Name"].lower()]
    print(f"items={len(items)} missing_thumb={len(missing)}", flush=True)
    fkey = env.get("FANART_KEY")
    if not fkey:
        print("fanart.tv: no FANART_KEY configured, skipping source (a)")
    counts, failed = {}, 0
    for it in missing:
        src = "none"
        try:
            if args.dry_run:
                continue
            img = None
            url = fanart_thumb(it, fkey)
            if url:
                img, src = ca.cover(http_img(url), W, H), "fanart"
            if img is None:
                url = tmdb_en_backdrop(it, env["TMDB_KEY"])
                if url:
                    img, src = ca.cover(http_img(url), W, H), "tmdb-en"
            if img is None:
                got = generate(jf, it)
                if got:
                    img, src = got
            if img is None:
                counts["none"] = counts.get("none", 0) + 1
                print(f"{it['Name']}: no source (no backdrops)")
                continue
            ca.upload(jf, it["Id"], "Thumb", img)
            if args.out:
                img.save(os.path.join(args.out, f"{it['Name'].replace('/', '_')}-{src}.jpg"), quality=88)
            counts[src] = counts.get(src, 0) + 1
            print(f"{it['Name']}: {src}", flush=True)
        except Exception as e:
            failed += 1
            print(f"FAILED {it['Name']}: {e}", file=sys.stderr)
    print(f"sources={counts} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
