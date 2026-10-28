"""Pull a mixed sample, score it, and write numbered contact sheets for eyeballing. Usage: calibrate.py OUTDIR"""
import io
import json
import os
import random
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.expanduser("~/jellytip-api"))
from score_library import get, list_items, load_env  # noqa: E402
from realism import Scorer  # noqa: E402
from jellytip_api import stills as st  # noqa: E402

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
env = load_env(os.path.expanduser("~/jellytip-api/jellytip.env"))
base, hdr = env["JELLYFIN_URL"].rstrip("/"), {"Authorization": f'MediaBrowser Token="{env["JELLYFIN_KEY"]}"'}
items = list_items(base, hdr, env.get("JELLYFIN_USER", "ricki28"))
random.seed(7)
must = [i for i in items if i.get("ProviderIds", {}).get("Tmdb") in ("829", "655") and i["Type"] == "Movie"]
anim = random.sample([i for i in items if "Animation" in (i.get("Genres") or []) and i.get("BackdropImageTags")], 5)
rest = random.sample([i for i in items if i not in must and i not in anim and i.get("BackdropImageTags")], 12)
pool = []  # (label, bytes)
for it in must + anim + rest:
    tag = "ANIM" if it in anim else ""
    for j, t in enumerate((it.get("BackdropImageTags") or [])[:3]):
        pool.append((f'{it["Name"][:22]} jf{j} {tag}', get(f'{base}/Items/{it["Id"]}/Images/Backdrop/{j}?maxWidth=780&tag={t}', hdr)))
    tm = it.get("ProviderIds", {}).get("Tmdb")
    if tm:
        s, _ = st.fetch_stills(env["TMDB_KEY"], tm, "series" if it["Type"] == "Series" else "movie", limit=6)
        for j, x in enumerate(s[:3]):
            pool.append((f'{it["Name"][:22]} tm{j} {tag}', get(x["url"].replace("/original/", "/w780/"))))
random.shuffle(pool)
pool = pool[:60]
sc = Scorer().score([b for _, b in pool])
meta = []
for sheet in range(0, len(pool), 15):
    W, H = 400, 225
    im = Image.new("RGB", (W * 3, (H + 22) * 5), "black")
    d = ImageDraw.Draw(im)
    for k, ((lab, b), s) in enumerate(zip(pool[sheet:sheet + 15], sc[sheet:sheet + 15])):
        n = sheet + k
        x, y = (k % 3) * W, (k // 3) * (H + 22)
        im.paste(Image.open(io.BytesIO(b)).convert("RGB").resize((W, H)), (x, y + 22))
        d.text((x + 3, y + 5), f"#{n} {lab} s={s}", fill="yellow")
        meta.append({"n": n, "label": lab, "score": s})
    im.save(f"{out}/sheet{sheet // 15}.jpg", quality=80)
json.dump(meta, open(f"{out}/meta.json", "w"))
print(len(pool), "images")
