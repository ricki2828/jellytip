"""TMDB backdrops ranked for full-bleed use."""
import json
import urllib.error
import urllib.parse
import urllib.request

MAX_STILLS = 12
IMG_BASE = "https://image.tmdb.org/t/p/original"


def rank(backdrops, limit=MAX_STILLS):
    """Textless first (iso_639_1 null), then width desc, then vote_average desc."""
    def key(b):
        return (b.get("iso_639_1") is not None, -(b.get("width") or 0), -(b.get("vote_average") or 0))
    return [{"url": IMG_BASE + b["file_path"], "width": b.get("width"), "height": b.get("height"),
             "lang": b.get("iso_639_1")}
            for b in sorted((b for b in backdrops or [] if b.get("file_path")), key=key)[:limit]]


def fetch_stills(tmdb_key, tmdb_id, kind, fetch=None):
    """Returns (stills, complete)."""
    if not (tmdb_key and tmdb_id):
        return [], False
    path = "tv" if kind == "series" else "movie"
    url = f"https://api.themoviedb.org/3/{path}/{tmdb_id}/images?" + urllib.parse.urlencode({"api_key": tmdb_key})
    try:
        if fetch:
            data = fetch(url)
        else:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "jellytip-api"}), timeout=15) as r:
                data = json.load(r)
    except (urllib.error.URLError, ValueError, OSError):
        return [], False
    return rank(data.get("backdrops")), True
