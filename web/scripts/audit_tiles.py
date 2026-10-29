"""Count carousel items whose tile would render without a picture, and tile images that fail to load.

Walks every companion carousel and Letterboxd row (not just the ones Home shows), resolves the first layer of the
web app's tile fallback chain (Thumb > Backdrop > Primary poster > Logo > title text) and fetches each tile image
at the canonical tile size. Usage: JELLYFIN_TOKEN=... JELLYFIN_USER_ID=... python3 scripts/audit_tiles.py
"""
import collections
import concurrent.futures as cf
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("JELLYTIP_BASE", "https://ricki28.palawan.usbx.me")
TOKEN = os.environ["JELLYFIN_TOKEN"]
UID = os.environ["JELLYFIN_USER_ID"]
HDR = {"Authorization": f'MediaBrowser Client="Jellytip audit", Device="script", DeviceId="jellytip-audit", Version="1", Token="{TOKEN}"'}
TILE = "fillWidth=528&fillHeight=297&quality=85"  # keep in step with TILE_W/TILE_H in src/lib/art.ts


def get(path):
    with urllib.request.urlopen(urllib.request.Request(BASE + path, headers=HDR), timeout=60) as r:
        return json.load(r)


def fetch(url):
    t = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE + url, headers=HDR), timeout=60) as r:
            r.read()
            return r.status, time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, time.time() - t
    except OSError as e:
        return type(e).__name__, time.time() - t


def first_layer(it):
    tags, bd = it.get("ImageTags", {}), it.get("BackdropImageTags", [])
    if "Thumb" in tags:
        return "thumb", f"/jellyfin/Items/{it['Id']}/Images/Thumb?tag={tags['Thumb']}&{TILE}"
    if bd:
        return "backdrop", f"/jellyfin/Items/{it['Id']}/Images/Backdrop/0?tag={bd[0]}&{TILE}"
    if "Primary" in tags:
        return "poster", f"/jellyfin/Items/{it['Id']}/Images/Primary?tag={tags['Primary']}&maxHeight=297&quality=85"
    return ("logo", None) if "Logo" in tags else ("text", None)


def main():
    m = get("/jellytip-api/v1/home/carousels")
    rows = [(c["title"], {"ParentId": c["id"]}) for c in m["carousels"]]
    rows += [("Letterboxd · " + l["name"], {"Ids": ",".join(dict.fromkeys(l["itemIds"]))}) for l in m.get("letterboxd", [])]
    layers, checks, no_picture = collections.Counter(), [], []
    for title, q in rows:
        items = get(f"/jellyfin/Users/{UID}/Items?" + urllib.parse.urlencode({**q, "Limit": 60}))["Items"]
        for it in items:
            kind, url = first_layer(it)
            layers[kind] += 1
            if url:
                checks.append((title, it["Name"], kind, url))
            else:
                no_picture.append((title, it["Name"], kind))
    with cf.ThreadPoolExecutor(6) as ex:
        results = list(ex.map(lambda c: (c, fetch(c[3])), checks))
    failing = [(c[0], c[1], c[2], s) for c, (s, _) in results if s != 200]
    slow = sorted(((round(t, 1), c[1]) for c, (s, t) in results if s == 200), reverse=True)[:5]
    print(f"rows {len(rows)}  items {sum(layers.values())}  first layer {dict(layers)}")
    print(f"no picture (logo/text only) {len(no_picture)}  failing images {len(failing)}  slowest {slow}")
    for row in no_picture + failing:
        print("  ", row)


if __name__ == "__main__":
    main()
