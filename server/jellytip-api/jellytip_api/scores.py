"""Score aggregation: MDBList first (one call), OMDb as fallback for IMDb/RT/Metacritic."""
import json
import urllib.error
import urllib.parse
import urllib.request

ORDER = ["imdb", "rt_critic", "rt_audience", "metacritic", "letterboxd", "trakt"]
LABELS = {"imdb": "IMDb", "rt_critic": "Rotten Tomatoes", "rt_audience": "Audience",
          "metacritic": "Metacritic", "letterboxd": "Letterboxd", "trakt": "Trakt"}
MAXES = {"imdb": 10, "letterboxd": 5}
MDBLIST_SOURCES = {"imdb": "imdb", "tomatoes": "rt_critic", "tomatoesaudience": "rt_audience",
                   "metacritic": "metacritic", "letterboxd": "letterboxd", "trakt": "trakt"}


def _num(x):
    try:
        x = float(x)
    except (TypeError, ValueError):
        return None
    return x if x > 0 else None  # 0 / negative / junk means "no value"; never emit it


def _clean(v):
    return int(v) if float(v).is_integer() and v > 10 else round(v, 1)


def entry(source, value, votes=None, url=None):
    value = _num(value)
    if value is None:
        return None
    e = {"source": source, "label": LABELS[source], "value": _clean(value), "max": MAXES.get(source, 100)}
    if votes is not None and _num(votes):
        e["votes"] = int(votes)
    e["url"] = url or None
    return e


def parse_mdblist(data):
    """MDBList item JSON -> {source: entry}. Letterboxd arrives as 0-5 `value` or 0-100 `score`."""
    out = {}
    for r in (data or {}).get("ratings") or []:
        src = MDBLIST_SOURCES.get(r.get("source"))
        if not src:
            continue
        if src == "imdb":
            e = entry(src, r.get("value"), r.get("votes"), r.get("url"))
        elif src == "letterboxd":
            v = _num(r.get("value"))
            if v is not None and v > 5:  # some responses give 0-100 here
                v = v / 20
            if v is None and _num(r.get("score")):
                v = _num(r.get("score")) / 20
            e = entry(src, v, r.get("votes"), r.get("url"))
        else:
            e = entry(src, r.get("score") if _num(r.get("score")) else r.get("value"), None, r.get("url"))
        if e:
            out[src] = e
    return out


def parse_omdb(data):
    data = data or {}
    if data.get("Response") == "False":
        return {}
    out = {}
    votes = (data.get("imdbVotes") or "").replace(",", "")
    e = entry("imdb", data.get("imdbRating"), int(votes) if votes.isdigit() else None)
    if e:
        out["imdb"] = e
    e = entry("metacritic", data.get("Metascore"))
    if e:
        out["metacritic"] = e
    for r in data.get("Ratings") or []:
        if r.get("Source") == "Rotten Tomatoes" and str(r.get("Value", "")).endswith("%"):
            e = entry("rt_critic", r["Value"].rstrip("%"))
            if e:
                out["rt_critic"] = e
    return out


def merge(primary, fallback):
    """Primary wins per source; fallback only fills gaps. Returns the contract's ordered list."""
    merged = {**fallback, **primary}
    return [merged[s] for s in ORDER if s in merged]


def _get_json(url, headers=None, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": "jellytip-api", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


class ScoreProvider:
    def __init__(self, mdblist_key, omdb_key, fetch=_get_json):
        self.mdblist_key, self.omdb_key, self.fetch = mdblist_key, omdb_key, fetch

    def lookup(self, tmdb, imdb, kind):
        """Returns (scores, complete). complete=False means an upstream failed -> cache briefly only."""
        mdb_type = "show" if kind == "series" else "movie"
        primary, fallback, complete = {}, {}, True
        if self.mdblist_key and (tmdb or imdb):
            url = (f"https://api.mdblist.com/tmdb/{mdb_type}/{tmdb}" if tmdb
                   else f"https://api.mdblist.com/imdb/{mdb_type}/{imdb}")
            try:
                primary = parse_mdblist(self.fetch(url + "?" + urllib.parse.urlencode({"apikey": self.mdblist_key})))
            except (urllib.error.URLError, ValueError, OSError):
                complete = False
        else:
            complete = False
        need = {"imdb", "rt_critic", "metacritic"} - set(primary)
        if need and self.omdb_key and imdb:
            try:
                fallback = parse_omdb(self.fetch("https://www.omdbapi.com/?" + urllib.parse.urlencode(
                    {"i": imdb, "apikey": self.omdb_key})))
            except (urllib.error.URLError, ValueError, OSError):
                complete = False
        return merge(primary, fallback), complete
