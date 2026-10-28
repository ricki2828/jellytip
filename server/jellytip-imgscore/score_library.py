"""Score every Jellyfin movie/series' TMDB stills + Jellyfin backdrops for photo realism.

Resumable (skips images already in the store), writes imgscores.json atomically.
Usage: score_library.py [--limit N] [--only ITEMID] [--out PATH]   (env from jellytip.env)
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "jellytip-api"))
sys.path.insert(0, os.path.expanduser("~/jellytip-api"))
from jellytip_api import stills as stills_mod  # noqa: E402

UA = "Jellytip/1.0 (+https://github.com/ricki2828/jellytip)"
CANDIDATES = 30


def log(*a):
    print(time.strftime("%F %T"), *a, flush=True)


def get(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def load_env(path):
    env = dict(os.environ)
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env.setdefault(k, v.strip().strip('"').strip("'"))
    return env


def list_items(base, hdr, user):
    users = json.loads(get(f"{base}/Users", hdr))
    uid = next((u["Id"] for u in users if u["Name"] == user), users[0]["Id"])
    q = urllib.parse.urlencode({"Recursive": "true", "IncludeItemTypes": "Movie,Series",
                                "Fields": "ProviderIds,Genres,BackdropImageTags"})
    return json.loads(get(f"{base}/Users/{uid}/Items?{q}", hdr, 120)).get("Items", [])


def save(store, path):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(store, f, separators=(",", ":"))
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int)
    ap.add_argument("--only")
    ap.add_argument("--out", default=os.path.expanduser("~/jellytip-api/data/imgscores.json"))
    ap.add_argument("--env", default=os.path.expanduser("~/jellytip-api/jellytip.env"))
    ap.add_argument("--rate", type=float, default=0.15, help="seconds between TMDB lookups")
    a = ap.parse_args()
    env = load_env(a.env)
    base, hdr = env["JELLYFIN_URL"].rstrip("/"), {"Authorization": f'MediaBrowser Token="{env["JELLYFIN_KEY"]}"'}
    from realism import Scorer
    scorer = Scorer()
    store = {"version": 1, "stills": {}, "items": {}}
    if os.path.exists(a.out):
        store = json.load(open(a.out))
    items = list_items(base, hdr, env.get("JELLYFIN_USER", "ricki28"))
    if a.only:
        items = [i for i in items if i["Id"] == a.only]
    if a.limit:
        items = items[:a.limit]
    log(f"{len(items)} items; {len(store['stills'])} stills + {len(store['items'])} items already scored")
    pool = ThreadPoolExecutor(8)
    t0, n_img, failed = time.time(), 0, 0
    for n, it in enumerate(items, 1):
        iid, kind = it["Id"], ("series" if it["Type"] == "Series" else "movie")
        anim = "Animation" in (it.get("Genres") or [])
        tags = it.get("BackdropImageTags") or []
        rec = store["items"].get(iid, {})
        have = {b["i"]: b for b in rec.get("backdrops", [])}
        rec = {"name": it.get("Name"), "kind": kind, "anim": anim, "backdrops": [], "tmdb": it.get("ProviderIds", {}).get("Tmdb")}
        todo = []  # (kind, key, fetch-url)
        for i, tag in enumerate(tags):
            old = have.get(i)
            if old and old["tag"] == tag and (old["s"] is not None or anim):
                rec["backdrops"].append(old)
            else:
                rec["backdrops"].append({"i": i, "tag": tag, "s": None})
                if not anim:
                    todo.append(("b", i, f"{base}/Items/{iid}/Images/Backdrop/{i}?maxWidth=780&tag={tag}"))
        tmdb = rec["tmdb"]
        if tmdb and not anim and env.get("TMDB_KEY"):
            time.sleep(a.rate)
            st, _ = stills_mod.fetch_stills(env["TMDB_KEY"], tmdb, kind, limit=CANDIDATES)
            for s in st:
                if s["url"] not in store["stills"]:
                    todo.append(("s", s["url"], s["url"].replace("/original/", "/w780/")))
        if todo:
            def dl(t):
                try:
                    return get(t[2], hdr if t[0] == "b" else None)
                except Exception:
                    return None
            blobs = list(pool.map(dl, todo))
            ok = [(t, b) for t, b in zip(todo, blobs) if b]
            failed += len(todo) - len(ok)
            scores = scorer.score([b for _, b in ok]) if ok else []
            for (t, _), sc in zip(ok, scores):
                if t[0] == "s":
                    store["stills"][t[1]] = sc
                else:
                    rec["backdrops"][t[1]]["s"] = sc
            n_img += len(ok)
        store["items"][iid] = rec
        if n % 10 == 0 or n == len(items):
            save(store, a.out)
            el = time.time() - t0
            log(f"{n}/{len(items)} items, {n_img} images scored ({n_img / max(el, 1e-6):.1f}/s), {failed} fetch failures")
    store["updated"] = time.strftime("%FT%TZ", time.gmtime())
    save(store, a.out)
    log("done")


if __name__ == "__main__":
    main()
