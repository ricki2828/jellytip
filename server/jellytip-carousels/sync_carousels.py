#!/usr/bin/env python3
"""Mirror Ricki's Kometa collection YAMLs as Jellyfin collections and write the home-carousel manifest.

Runs on the seedbox (palawan) under cron after Kometa's own 03:30-05:00 rotation, so the daily AI
carousels flow through without translation drift. Reads Kometa's config read-only; never touches
Plex or Kometa. Builders it cannot evaluate are skipped and listed in the report, never guessed.

  sync_carousels.py [--dry-run] [--only NAME]

Env (from ~/jellytip-api/jellytip.env): JELLYFIN_URL, JELLYFIN_KEY, TMDB_KEY.
Output: <state>/carousels.json (manifest, read by the companion API) and <state>/last_run.json.
"""
import argparse
import datetime as dt
import json
import os
import random
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

import yaml

HOME = os.path.expanduser("~")
KOMETA = os.path.join(HOME, "Kometa")
STATE = os.environ.get("JELLYTIP_CAROUSEL_STATE", os.path.join(HOME, "jellytip-api", "data"))
ENV_FILE = os.path.join(HOME, "jellytip-api", "jellytip.env")
JFC_CONFIG = os.path.join(HOME, "jellyfin-collection", "config", "Movies.yml")
UA = "Mozilla/5.0 (jellytip-carousels)"
USER_NAME = "ricki28"
MAX_ITEMS = 50
MIN_ROW_ITEMS = 3  # a 1-2 film row looks broken on a home screen; the collection is still synced
TMDB_PAGES = 8
# Collections other jobs own: rewatchables_sync.py maintains this one from the IMDb list.
EXTERNALLY_OWNED = {"The Rewatchables"}
META_KEYS = {
    "summary", "sort_title", "collection_order", "sync_mode", "visible_home", "visible_shared", "visible_library",
    "schedule", "limit", "smart_label", "label", "name_mapping", "template", "url_poster", "file_poster",
    "url_background", "item_label", "collection_mode", "delete_below_minimum", "delete_not_scheduled",
    "minimum_items", "tmdb_region", "item_assets", "radarr_add_missing", "sonarr_add_missing", "ignore_ids",
    "test", "item_refresh", "run_again", "cache_builders", "filters", "sort_by", "plex_all",
}


def load_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                env[k.strip()] = v.strip().strip('"')
    return env


class Http:
    def __init__(self, base, headers=None):
        self.base, self.headers = base.rstrip("/"), headers or {}

    def call(self, method, path, params=None, timeout=60):
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        req = urllib.request.Request(url, method=method, headers={"User-Agent": UA, **self.headers})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
        return json.loads(body) if body else None

    def get(self, path, **params):
        return self.call("GET", path, params)


class Unsupported(Exception):
    pass


# ---------- library index ----------

class Library:
    FIELDS = "ProviderIds,People,Genres,ProductionLocations,CommunityRating,CriticRating,Tags,DateCreated,MediaStreams,ProductionYear,PremiereDate,SortName"

    def __init__(self, jf, uid, kind):
        self.kind, self.items = kind, []
        start = 0
        while True:  # user-scoped listing: the admin /Items endpoint under-reports
            page = jf.get(f"/Users/{uid}/Items", IncludeItemTypes=kind, Recursive="true", Fields=self.FIELDS,
                          StartIndex=start, Limit=200)
            self.items += page["Items"]
            start += 200
            if start >= page["TotalRecordCount"]:
                break
        self.by_tmdb, self.by_imdb, self.by_title = {}, {}, {}
        for it in self.items:
            p = it.get("ProviderIds") or {}
            if p.get("Tmdb"):
                self.by_tmdb.setdefault(str(p["Tmdb"]), it)
            if p.get("Imdb"):
                self.by_imdb.setdefault(p["Imdb"], it)
            self.by_title.setdefault((norm(it["Name"]), it.get("ProductionYear")), it)


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


# ---------- filter evaluation (Kometa plex_search / smart_filter -> Jellyfin metadata) ----------

def audio_codecs(it):
    out = set()
    for s in it.get("MediaStreams") or []:
        if s.get("Type") != "Audio":
            continue
        c = (s.get("Codec") or "").lower()
        if c == "dts" and "ma" in (s.get("Profile") or "").lower():
            out.add("dca-ma")
        out.add({"dts": "dca"}.get(c, c))
    return out


def attr_values(it, attr):
    if attr == "genre":
        return [g.lower() for g in it.get("Genres") or []]
    if attr == "director":
        return [p["Name"].lower() for p in it.get("People") or [] if p.get("Type") == "Director"]
    if attr == "country":
        return [c.lower() for c in it.get("ProductionLocations") or []]
    if attr == "label":
        return [t.lower() for t in it.get("Tags") or []]
    if attr == "audio_codec":
        return list(audio_codecs(it))
    if attr == "year":
        return [it.get("ProductionYear")]
    if attr == "audience_rating":
        return [it.get("CommunityRating")]
    if attr == "critic_rating":  # Plex 0-10, Jellyfin RT-style 0-100
        return [it["CriticRating"] / 10 if it.get("CriticRating") is not None else None]
    raise Unsupported(f"filter attribute {attr}")


def cond(it, key, want, now):
    attr, _, op = key.partition(".")
    if attr == "added":
        created = dt.datetime.fromisoformat(it["DateCreated"][:19] + "+00:00")
        return (now - created).days <= int(want)
    vals = attr_values(it, attr)
    if op in ("gte", "lte", "gt", "lt"):
        v = vals[0] if vals else None
        if v is None:
            return False
        w = float(want)
        return {"gte": v >= w, "lte": v <= w, "gt": v > w, "lt": v < w}[op]
    if op not in ("", "is", "not"):
        raise Unsupported(f"filter operator {key}")
    wants = [str(w).lower() for w in (want if isinstance(want, list) else [want])]
    hit = any(str(v).lower() in wants for v in vals if v is not None)
    return not hit if op == "not" else hit


def matches(it, spec, now):
    for key, want in (spec.get("all") or {}).items():
        if not cond(it, key, want, now):
            return False
    anyc = spec.get("any")
    if anyc and not any(cond(it, k, w, now) for k, w in anyc.items()):
        return False
    return True


def sort_items(items, sort_by, rng):
    if sort_by in (None, "random"):
        items = list(items)
        rng.shuffle(items)
        return items
    field, _, direction = sort_by.partition(".")
    keyf = {"audience_rating": lambda i: i.get("CommunityRating") or 0,
            "critic_rating": lambda i: i.get("CriticRating") or 0,
            "release": lambda i: i.get("PremiereDate") or "",
            "added": lambda i: i.get("DateCreated") or "",
            "title": lambda i: i.get("SortName") or i["Name"]}.get(field)
    if not keyf:
        raise Unsupported(f"sort_by {sort_by}")
    return sorted(items, key=keyf, reverse=direction == "desc")


# ---------- builders -> ordered list of Jellyfin items ----------

class Builders:
    def __init__(self, lib, tmdb, tautulli, now):
        self.lib, self.tmdb, self.tautulli, self.now = lib, tmdb, tautulli, now

    def tmdb_pages(self, path, params, want):
        found, seen = [], set()
        for page in range(1, TMDB_PAGES + 1):
            data = self.tmdb.get(path, page=page, **params)
            for r in data.get("results", []):
                it = self.lib.by_tmdb.get(str(r["id"]))
                if it and it["Id"] not in seen:
                    seen.add(it["Id"])
                    found.append(it)
            if len(found) >= want or page >= data.get("total_pages", 1):
                break
        return found

    def build(self, name, c, rng):
        lib, now = self.lib, self.now
        limit = int(c.get("limit") or MAX_ITEMS)
        unknown = sorted(k for k in c if k not in META_KEYS)
        if not unknown and c.get("plex_all"):
            unknown = ["plex_all"]
        if len(unknown) != 1:
            raise Unsupported(f"builders {unknown}")
        b, v = unknown[0], c[unknown[0]]
        if b == "tmdb_movie":
            items = [lib.by_tmdb[str(i)] for i in (v if isinstance(v, list) else [v]) if str(i) in lib.by_tmdb]
        elif b == "tmdb_trending_daily":
            items = self.tmdb_pages("/trending/movie/day", {}, limit)
        elif b == "tmdb_top_rated":
            items = self.tmdb_pages("/movie/top_rated", {}, limit)
        elif b == "tmdb_popular":
            items = self.tmdb_pages("/movie/popular", {}, limit)
        elif b == "tmdb_keyword":
            items = self.tmdb_pages("/discover/movie", {"with_keywords": v, "sort_by": "popularity.desc"}, limit)
        elif b == "tmdb_discover":
            params = {k: val for k, val in v.items() if k != "limit"}
            items = self.tmdb_pages("/discover/movie", params, limit)
        elif b in ("plex_search", "smart_filter"):
            items = sort_items([i for i in lib.items if matches(i, v, now)], v.get("sort_by"), rng)
            limit = int(v.get("limit") or limit)
        elif b == "plex_all":
            items = list(lib.items)
        elif b == "imdb_list":
            items = self.imdb_list(v)
        elif b == "tautulli_popular":
            items = self.tautulli_popular(v)
        else:
            raise Unsupported(f"builder {b}")
        flt = c.get("filters")
        if flt:
            items = [i for i in items if all(cond(i, k, w, now) for k, w in flt.items())]
        return items[:limit]

    def imdb_list(self, url):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Chrome/124 Safari/537.36",
                                                       "Accept-Language": "en"})
            html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
        except urllib.error.URLError as e:
            raise Unsupported(f"imdb_list fetch failed ({type(e).__name__})")
        ids = list(dict.fromkeys(re.findall(r"/title/(tt\d+)", html)))
        if not ids:
            raise Unsupported("imdb_list returned no titles (blocked or JS-rendered)")
        return [self.lib.by_imdb[i] for i in ids if i in self.lib.by_imdb]

    def tautulli_popular(self, v):
        if not self.tautulli:
            raise Unsupported("tautulli not configured")
        if self.lib.kind != "Movie":
            raise Unsupported("tautulli_popular is movies-only")
        try:
            data = self.tautulli.get("/api/v2", cmd="get_home_stats", time_range=v.get("list_days", 30),
                                     stats_type="plays", stats_count=v.get("list_size", 10), stat_id="popular_movies")
        except urllib.error.URLError as e:  # 502 on 28-Oct: Tautulli is not running on the seedbox
            raise Unsupported(f"Tautulli unreachable ({type(e).__name__})")
        rows = []
        for blk in data["response"]["data"]:
            if blk.get("stat_id") == "popular_movies":
                rows = blk["rows"]
        out = []
        for r in rows:
            it = self.lib.by_title.get((norm(r.get("title")), r.get("year")))
            if it:
                out.append(it)
        return out


def order_items(items, order, rng):
    if order == "random":
        items = list(items)
        rng.shuffle(items)
    elif order in ("release", "release.asc"):
        items = sorted(items, key=lambda i: i.get("PremiereDate") or "")
    elif order == "release.desc":
        items = sorted(items, key=lambda i: i.get("PremiereDate") or "", reverse=True)
    elif order == "added.desc":
        items = sorted(items, key=lambda i: i.get("DateCreated") or "", reverse=True)
    return items


# ---------- Kometa config ----------

def kometa_collections():
    """[(library_name, collection_name, merged_dict)] for Movies and TV Shows (default/Music files skipped)."""
    cfg = yaml.safe_load(open(os.path.join(KOMETA, "config", "config.yml")))
    out = []
    for lib in ("Movies", "TV Shows"):
        for f in (cfg["libraries"].get(lib) or {}).get("collection_files") or []:
            path = f.get("file") if isinstance(f, dict) else None
            if not path:
                continue
            doc = yaml.safe_load(open(os.path.join(KOMETA, path))) or {}
            tpls = doc.get("templates") or {}
            for name, c in (doc.get("collections") or {}).items():
                merged = {}
                t = c.get("template")
                for tt in (t if isinstance(t, list) else [t] if t else []):
                    merged.update(tpls.get(tt.get("name") if isinstance(tt, dict) else tt, {}))
                merged.update({k: v for k, v in c.items() if k != "template"})
                out.append((lib, str(name), merged))
    return cfg, out


def sort_number(sort_key):
    """'!001_Trending' / '!0000000010_0000001990s' -> 1 / 10; unnumbered keys sort last, alphabetically."""
    m = re.match(r"!(\d+)", sort_key)
    return (0, int(m.group(1)), "") if m else (1, 0, sort_key.lower())


def strip_emoji(s):
    return re.sub(r"^[^\w'\"(]+", "", s).strip()


# ---------- Jellyfin collection writes ----------

def sync_boxset(jf, uid, existing, name, ids, dry):
    """Make the boxset's children exactly `ids`. Returns (boxset_id, added, removed)."""
    box = existing.get(name)
    if not box:
        if dry:
            return None, len(ids), 0
        created = jf.call("POST", "/Collections", {"name": name, "ids": ",".join(ids[:1])})
        box = {"Id": created["Id"]}
        cur = set(ids[:1])
    else:
        kids = jf.get(f"/Users/{uid}/Items", ParentId=box["Id"], Fields="")["Items"]
        cur = {k["Id"] for k in kids}
    want = set(ids)
    add, rem = sorted(want - cur), sorted(cur - want)
    if not dry:
        for i in range(0, len(add), 50):
            jf.call("POST", f"/Collections/{box['Id']}/Items", {"ids": ",".join(add[i:i + 50])})
        for i in range(0, len(rem), 50):
            jf.call("DELETE", f"/Collections/{box['Id']}/Items", {"ids": ",".join(rem[i:i + 50])})
    return box["Id"], len(add), len(rem)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only")
    args = ap.parse_args()
    env = load_env(ENV_FILE)
    jf = Http(env["JELLYFIN_URL"], {"Authorization": f'MediaBrowser Token="{env["JELLYFIN_KEY"]}"'})
    tmdb = Http("https://api.themoviedb.org/3", {})
    tmdb_get = tmdb.get
    tmdb.get = lambda path, **p: tmdb_get(path, api_key=env["TMDB_KEY"], **p)
    cfg, cols = kometa_collections()
    tcfg = cfg.get("tautulli") or {}
    tautulli = None
    if tcfg.get("url") and tcfg.get("apikey"):
        tautulli = Http(tcfg["url"], {})
        t_get = tautulli.get
        tautulli.get = lambda path, **p: t_get(path, apikey=tcfg["apikey"], **p)

    users = jf.get("/Users")
    uid = next(u["Id"] for u in users if u["Name"] == USER_NAME)
    now = dt.datetime.now(dt.timezone.utc)
    libs = {"Movies": Library(jf, uid, "Movie"), "TV Shows": Library(jf, uid, "Series")}
    existing = {b["Name"]: b for b in jf.get(f"/Users/{uid}/Items", IncludeItemTypes="BoxSet", Recursive="true",
                                              Fields="SortName,ChildCount")["Items"]}
    report = {"generated": now.isoformat(), "synced": [], "skipped": [], "errors": []}
    entries, seen = [], set()
    for libname, name, c in cols:
        if args.only and args.only != name:
            continue
        if (libname, name) in seen:
            continue
        seen.add((libname, name))
        if name in EXTERNALLY_OWNED:
            continue
        lib = libs[libname]
        rng = random.Random(f"{now.date()}:{name}")
        try:
            items = Builders(lib, tmdb, tautulli, now).build(name, c, rng)
            items = order_items(items, c.get("collection_order"), rng)
        except Unsupported as e:
            report["skipped"].append({"name": name, "library": libname, "reason": str(e)})
            continue
        except (urllib.error.URLError, KeyError, ValueError) as e:
            report["errors"].append({"name": name, "error": type(e).__name__})
            continue
        ids = [i["Id"] for i in items]
        if not ids:
            report["skipped"].append({"name": name, "library": libname, "reason": "no items in library"})
            continue
        try:
            box_id, added, removed = sync_boxset(jf, uid, existing, name, ids, args.dry_run)
        except urllib.error.URLError as e:
            report["errors"].append({"name": name, "error": type(e).__name__})
            continue
        report["synced"].append({"name": name, "items": len(ids), "added": added, "removed": removed})
        pick_of_one = int((c.get("smart_filter") or {}).get("limit") or 0) == 1
        if c.get("visible_home") and (len(ids) >= MIN_ROW_ITEMS or pick_of_one):
            entries.append({"id": box_id, "name": name, "title": strip_emoji(name), "sortKey": str(c.get("sort_title") or "~" + name),
                            "library": libname, "order": c.get("collection_order") or "custom", "itemIds": ids[:MAX_ITEMS]})
    # Collections maintained elsewhere (JFC, rewatchables_sync) join the manifest in their Jellyfin SortName order.
    managed = {e["name"] for e in entries}
    jfc_names = set()
    try:
        jfc_names = set((yaml.safe_load(open(JFC_CONFIG)) or {}).get("collections", {}))
    except OSError:
        pass
    for name in sorted((jfc_names | EXTERNALLY_OWNED) - managed):
        box = existing.get(name)
        if box and (box.get("ChildCount") or 0) >= MIN_ROW_ITEMS:
            kids = jf.get(f"/Users/{uid}/Items", ParentId=box["Id"], Fields="")["Items"]
            entries.append({"id": box["Id"], "name": name, "title": strip_emoji(name), "sortKey": box.get("SortName") or name,
                            "library": "Movies", "order": "random" if name in EXTERNALLY_OWNED else "custom",
                            "itemIds": [k["Id"] for k in kids][:MAX_ITEMS]})
    entries.sort(key=lambda e: (sort_number(e["sortKey"]), e["name"]))
    manifest = {"version": 1, "generated": now.isoformat(), "carousels": entries}
    report["manifest_count"] = len(entries)
    if not args.dry_run:
        os.makedirs(STATE, exist_ok=True)
        for fn, doc in (("carousels.json", manifest), ("last_run.json", report)):
            tmp = os.path.join(STATE, fn + ".tmp")
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(doc, f, ensure_ascii=False, indent=1)
            os.replace(tmp, os.path.join(STATE, fn))
    json.dump(report, sys.stdout, ensure_ascii=False, indent=1)
    print()
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
