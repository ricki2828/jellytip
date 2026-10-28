"""GET /v1/library/sizes -> {jellyfinId: bytes} from Radarr / Sonarr sizeOnDisk (Jellyfin has no size sort)."""
import json
import os
import re
import urllib.request

SIZES_TTL = 3600
CACHE_KEY = "sizes:v1"
PATHS_KEY = "paths:v1"


def arr_config(env):
    """Radarr/Sonarr (url, key) from env, else the Kometa config on the seedbox."""
    out = {}
    for name in ("radarr", "sonarr"):
        url, key = env.get(f"{name.upper()}_URL"), env.get(f"{name.upper()}_KEY")
        if not (url and key):
            try:
                import yaml
                with open(env.get("KOMETA_CONFIG") or os.path.expanduser("~/Kometa/config/config.yml")) as f:
                    c = yaml.safe_load(f)[name]
                url, key = c["url"], c["token"]
            except Exception:
                continue
        out[name] = (url.rstrip("/"), key)
    return out


def _arr_get(url, key, path):
    req = urllib.request.Request(f"{url}/api/v3/{path}", headers={"X-Api-Key": key})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def norm_path(p):
    return re.sub(r"/+$", "", (p or "").replace("\\", "/")).lower()


def build(jf, arrs):
    """Returns (sizes, paths): sizes {id: bytes}; paths {id: {"kind", "folder"}} for the trailer cleanup."""
    movies = {"tmdb": {}, "path": {}}
    series = {"tvdb": {}, "tmdb": {}, "path": {}}
    if "radarr" in arrs:
        for m in _arr_get(*arrs["radarr"], "movie"):
            sz = m.get("sizeOnDisk") or 0
            movies["tmdb"][str(m.get("tmdbId"))] = sz
            movies["path"][norm_path(m.get("path"))] = sz
    if "sonarr" in arrs:
        for s in _arr_get(*arrs["sonarr"], "series"):
            sz = (s.get("statistics") or {}).get("sizeOnDisk") or 0
            series["tvdb"][str(s.get("tvdbId"))] = sz
            series["tmdb"][str(s.get("tmdbId"))] = sz
            series["path"][norm_path(s.get("path"))] = sz
    sizes, paths = {}, {}
    for kind, jf_type in (("movie", "Movie"), ("series", "Series")):
        for it in jf.items(IncludeItemTypes=jf_type, Fields="ProviderIds,Path"):
            iid = it["Id"].replace("-", "").lower()
            pid = {k.lower(): str(v) for k, v in (it.get("ProviderIds") or {}).items()}
            path = it.get("Path") or ""
            if kind == "movie":
                folder = os.path.dirname(path)
                sz = movies["tmdb"].get(pid.get("tmdb", "")) or movies["path"].get(norm_path(folder))
            else:
                folder = path
                sz = series["tvdb"].get(pid.get("tvdb", "")) or series["tmdb"].get(pid.get("tmdb", "")) or series["path"].get(norm_path(folder))
            if sz:
                sizes[iid] = sz
            if folder:
                paths[iid] = {"kind": kind, "folder": folder}
    return sizes, paths


def get(service):
    cached = service.cache.get(CACHE_KEY)
    return cached if cached is not None else refresh(service)


def refresh(service):
    """Rebuild sizes (cached 1 h) and merge the id->folder map (kept 90 d so cleanup can resolve items after Jellyfin deletes them)."""
    sizes, paths = build(service.jf, arr_config(service.env))
    service.cache.put(CACHE_KEY, sizes, SIZES_TTL)
    old = service.cache.get(PATHS_KEY) or {}
    service.cache.put(PATHS_KEY, {**old, **paths}, 90 * 86400)  # union: never forget an item just because it was deleted
    return sizes
