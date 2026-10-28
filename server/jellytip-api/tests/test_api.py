import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from jellytip_api.cache import Cache
from jellytip_api.letterboxd import Letterboxd, map_jellyfin
from jellytip_api.scores import ScoreProvider, merge, parse_mdblist, parse_omdb
from jellytip_api.server import Service
from jellytip_api.stills import rank


def bd(path, w, lang=None, votes=0.0):
    return {"file_path": path, "width": w, "height": w * 9 // 16, "iso_639_1": lang, "vote_average": votes}


def test_stills_textless_first_then_width_then_votes_and_cap():
    b = [bd("/en4k", 3840, "en"), bd("/t1080", 1920, None, 5), bd("/t4k", 3840, None, 1), bd("/t1080b", 1920, None, 7)]
    assert [s["url"].rsplit("/", 1)[1] for s in rank(b)] == ["t4k", "t1080b", "t1080", "en4k"]
    assert len(rank([bd(f"/{i}", 1000 + i) for i in range(30)])) == 12
    assert rank(None) == [] and rank([{"width": 5}]) == []


def test_mdblist_parse_and_missing_never_zero():
    data = {"ratings": [
        {"source": "imdb", "value": 7.9, "votes": 123456, "url": "https://imdb"},
        {"source": "tomatoes", "value": 94, "score": 94},
        {"source": "tomatoesaudience", "value": None, "score": 0},
        {"source": "metacritic", "score": 81},
        {"source": "letterboxd", "value": 4.1, "url": "https://letterboxd.com/film/x/"},
        {"source": "trakt", "score": 82},
        {"source": "rogerebert", "value": 4}]}
    got = {s["source"]: s for s in merge(parse_mdblist(data), {})}
    assert list(got) == ["imdb", "rt_critic", "metacritic", "letterboxd", "trakt"]
    assert got["imdb"]["value"] == 7.9 and got["imdb"]["max"] == 10 and got["imdb"]["votes"] == 123456
    assert got["letterboxd"]["max"] == 5 and got["letterboxd"]["value"] == 4.1
    assert got["rt_critic"]["url"] is None


def test_letterboxd_scale_normalised():
    assert parse_mdblist({"ratings": [{"source": "letterboxd", "value": 82}]})["letterboxd"]["value"] == 4.1


def test_omdb_fallback_fills_gaps_only():
    omdb = parse_omdb({"imdbRating": "8.1", "imdbVotes": "1,000", "Metascore": "N/A",
                       "Ratings": [{"Source": "Rotten Tomatoes", "Value": "99%"}]})
    assert set(omdb) == {"imdb", "rt_critic"} and omdb["imdb"]["votes"] == 1000
    out = {s["source"]: s["value"] for s in merge({"imdb": parse_mdblist({"ratings": [{"source": "imdb", "value": 7.9}]})["imdb"]}, omdb)}
    assert out == {"imdb": 7.9, "rt_critic": 99}
    assert parse_omdb({"Response": "False"}) == {}


def test_provider_falls_back_to_omdb_when_mdblist_down():
    def fetch(url):
        if "mdblist" in url:
            raise OSError("down")
        return {"imdbRating": "8.0", "Ratings": []}
    scores, complete = ScoreProvider("k", "o", fetch).lookup("829", "tt0071315", "movie")
    assert [s["source"] for s in scores] == ["imdb"] and complete is False


def test_mdblist_series_uses_show_endpoint():
    urls = []
    ScoreProvider("k", None, lambda u: urls.append(u) or {"ratings": []}).lookup("1396", None, "series")
    assert urls[0].startswith("https://api.mdblist.com/tmdb/show/1396")


def test_cache_ttl(tmp_path):
    c = Cache(str(tmp_path / "c.db"))
    c.put("k", {"a": 1}, 10, now=100)
    assert c.get("k", now=109) == {"a": 1} and c.get("k", now=110) is None


def test_map_jellyfin():
    items = [{"Id": "a", "ProviderIds": {"Tmdb": "829"}}, {"Id": "b", "ProviderIds": {}}, {"Id": "c", "ProviderIds": {"Tmdb": "829"}}]
    assert map_jellyfin(items) == {829: "a"}


def make_service(tmp_path, calls, export=None):
    p = tmp_path / "lb.json"
    p.write_text(json.dumps(export or {"films": {"829": {"slug": "chinatown", "rating": 4.5, "liked": True,
               "watchedDates": ["2026-03-02"], "rewatch": False, "url": "https://letterboxd.com/ricki28/film/chinatown/"}},
               "lists": [{"slug": "top", "name": "Top", "items": [{"tmdb": 829, "title": "Chinatown", "year": 1974},
                                                                  {"tmdb": 1, "title": "Nope", "year": 2000}]}]}))

    class Scores:
        def lookup(self, tmdb, imdb, kind):
            calls.append(tmdb)
            return [{"source": "imdb", "label": "IMDb", "value": 8.1, "max": 10, "url": None}], True
    svc = Service({"JELLYTIP_TOKEN": "tok", "JELLYTIP_DB": str(tmp_path / "c.db"), "TMDB_KEY": "t"},
                  scores=Scores(), stills=lambda k, i, kind: ([{"url": "u", "width": 1, "height": 1, "lang": None}], True),
                  letterboxd=Letterboxd(str(p), lambda: [{"Id": "jf1", "ProviderIds": {"Tmdb": "829"}}]))
    return svc


def test_auth_health_and_extras_cache(tmp_path):
    calls = []
    s = make_service(tmp_path, calls)
    assert s.route("/v1/health", {}, None)[0] == 200
    assert s.route("/v1/items/x/extras", {"tmdb": "829"}, None)[0] == 401
    assert s.route("/v1/items/x/extras", {"tmdb": "829"}, "bad")[0] == 401
    st, body = s.route("/v1/items/x/extras", {"tmdb": "829", "type": "movie"}, "tok")
    assert st == 200 and body["letterboxd"]["myRating"] == 4.5 and body["scores"][0]["source"] == "imdb"
    s.route("/v1/items/y/extras", {"tmdb": "829", "type": "movie"}, "tok")
    assert calls == ["829"]  # second item hit the cache, no upstream call
    assert s.route("/v1/items/x/extras", {"tmdb": "999"}, "tok")[1]["letterboxd"] is None
    assert s.route("/v1/items/x/extras", {"tmdb": "abc"}, "tok")[0] == 400


def test_lists_and_jellyfin_mapping(tmp_path):
    s = make_service(tmp_path, [])
    assert s.route("/v1/letterboxd/lists", {}, "tok")[1] == [{"slug": "top", "name": "Top", "count": 2}]
    body = s.route("/v1/letterboxd/lists/top", {}, "tok")[1]
    assert [i["jellyfinId"] for i in body["items"]] == ["jf1", None]
    assert s.route("/v1/letterboxd/lists/nope", {}, "tok")[0] == 404
