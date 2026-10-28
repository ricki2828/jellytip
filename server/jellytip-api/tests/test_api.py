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
    assert len(rank([bd(f"/{i}", 1000 + i) for i in range(40)])) == 30 and len(rank([bd(f"/{i}", 1000 + i) for i in range(40)], 12)) == 12
    assert rank(None) == [] and rank([{"width": 5}]) == []


LIVE = {"ids": {"imdb": "tt0071315"}, "ratings": [
    {"source": "imdb", "value": 8.1, "score": 81, "votes": 381599, "url": 1253},
    {"source": "metacritic", "value": 92, "score": 92, "votes": 23, "url": "/chinatown"},
    {"source": "metacriticuser", "value": 8.7, "score": 87.0, "votes": 427, "url": None},
    {"source": "trakt", "value": 79, "score": 79, "votes": 6750, "url": None},
    {"source": "tomatoes", "value": 98, "score": 98, "votes": 144, "url": "/m/chinatown", "fresh": 1},
    {"source": "popcorn", "value": 93, "score": 93, "votes": 5233, "url": "/m/chinatown"},
    {"source": "tmdb", "value": 79, "score": 79, "url": None},
    {"source": "letterboxd", "value": 4.3, "score": 86, "votes": 532339, "url": "/film/chinatown/"},
    {"source": "rogerebert", "value": 4.0, "score": None, "votes": None, "url": "chinatown-1974"},
    {"source": "myanimelist", "value": None, "score": None, "votes": None, "url": None}]}


def test_mdblist_live_shape():
    got = {s["source"]: s for s in merge(parse_mdblist(LIVE), {})}
    assert list(got) == ["imdb", "rt_critic", "rt_audience", "metacritic", "letterboxd", "trakt"]
    assert got["imdb"]["value"] == 8.1 and got["imdb"]["votes"] == 381599
    assert got["imdb"]["url"] == "https://www.imdb.com/title/tt0071315/"
    assert got["rt_critic"]["value"] == 98 and got["rt_critic"]["url"] == "https://www.rottentomatoes.com/m/chinatown"
    assert got["rt_audience"]["value"] == 93
    assert got["metacritic"]["value"] == 92 and got["metacritic"]["url"] is None
    assert got["letterboxd"]["value"] == 4.3 and got["letterboxd"]["max"] == 5
    assert got["letterboxd"]["url"] == "https://letterboxd.com/film/chinatown/"


def test_nulls_and_zero_dropped():
    r = parse_mdblist({"ratings": [{"source": "tomatoes", "value": None, "score": None},
                                   {"source": "trakt", "value": 0, "score": 0}, {"source": "popcorn", "score": 88}]})
    assert list(r) == ["rt_audience"]


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


def test_jellyfin_token_auth(tmp_path):
    svc = Service({"JELLYTIP_TOKEN": "secret", "JELLYFIN_URL": "http://jf", "JELLYFIN_DB": ""},
                  scores=object(), stills=lambda *a: ([], True), letterboxd=object(), cache=Cache(str(tmp_path / "c.db")))
    calls = []

    def fake(tok):
        calls.append(tok)
        return tok == "good"
    import types
    svc.jellyfin_ok = types.MethodType(lambda self, t, ip="": fake(t) if t else False, svc)
    assert Service.jellyfin_token('MediaBrowser Client="x", Token="abc"', None) == "abc"
    assert Service.jellyfin_token(None, " e1 ") == "e1"
    assert Service.jellyfin_token("Bearer abc", None) is None
    assert svc.route("/v1/letterboxd/lists", {}, None)[0] == 401
    assert svc.route("/v1/letterboxd/lists", {}, None, 'MediaBrowser Token="bad"')[0] == 401
    assert svc.route("/v1/nope", {}, "secret")[0] == 404
    assert svc.route("/v1/nope", {}, None, 'MediaBrowser Token="good"')[0] == 404
    assert svc.route("/v1/nope", {}, None, None, "good")[0] == 404


def test_jellyfin_validation_cached_and_hashed(tmp_path, monkeypatch):
    import urllib.request as ur
    svc = Service({"JELLYTIP_TOKEN": "s", "JELLYFIN_URL": "http://jf"},
                  scores=object(), stills=lambda *a: ([], True), letterboxd=object(), cache=Cache(str(tmp_path / "c.db")))
    n = []

    class R:
        status = 200
        def __enter__(self): return self
        def __exit__(self, *a): pass
    monkeypatch.setattr(ur, "urlopen", lambda req, timeout: n.append(req.full_url) or R())
    TOK = "a1" * 16
    assert svc.jellyfin_ok(TOK) and svc.jellyfin_ok(TOK)
    assert len(n) == 1 and n[0] == "http://jf/System/Info"
    assert TOK not in svc._auth_cache and len(next(iter(svc._auth_cache))) == 64


def test_home_carousels_serves_manifest_or_empty(tmp_path):
    env = {"JELLYTIP_TOKEN": "t", "JELLYTIP_DB": str(tmp_path / "c.db"), "LB_EXPORT": str(tmp_path / "lb.json")}
    svc = Service(env)
    status, body = svc.route("/v1/home/carousels", {}, "t")
    assert status == 200 and body["carousels"] == []
    (tmp_path / "carousels.json").write_text(json.dumps({"version": 1, "carousels": [{"name": "A", "itemIds": ["x"]}]}))
    status, body = svc.route("/v1/home/carousels", {}, "t")
    assert body["carousels"][0]["name"] == "A" and body["letterboxd"] == [] and body["carousels"][0]["mediaType"] == "movie"
    assert svc.route("/v1/home/carousels", {}, "bad")[0] == 401


def test_home_carousels_hide_discover_rows_unless_opted_in(tmp_path):
    env = {"JELLYTIP_TOKEN": "t", "JELLYTIP_DB": str(tmp_path / "c.db"), "LB_EXPORT": str(tmp_path / "lb.json")}
    (tmp_path / "carousels.json").write_text(json.dumps({"version": 1, "carousels": [
        {"name": "A", "itemIds": ["x"]}, {"name": "D", "discover": True, "itemIds": [], "items": [{"tmdbId": 1}]}]}))
    svc = Service(env)
    assert [c["name"] for c in svc.route("/v1/home/carousels", {}, "t")[1]["carousels"]] == ["A"]
    assert [c["name"] for c in svc.route("/v1/home/carousels", {"includeDiscover": "1"}, "t")[1]["carousels"]] == ["A", "D"]


def test_letterboxd_home_lists_need_enough_library_films(tmp_path):
    export = tmp_path / "lb.json"
    export.write_text(json.dumps({"films": {}, "lists": [
        {"slug": "big", "name": "Big", "items": [{"tmdb": n, "title": "t"} for n in range(1, 9)]},
        {"slug": "small", "name": "Small", "items": [{"tmdb": 1, "title": "t"}]}]}))
    lb = Letterboxd(str(export), lambda: [{"Id": f"j{n}", "ProviderIds": {"Tmdb": str(n)}} for n in range(1, 7)])
    assert lb.home_lists() == [{"slug": "big", "name": "Big", "source": "letterboxd", "mediaType": "movie", "itemIds": [f"j{n}" for n in range(1, 7)]}]


def _scores_file(tmp_path):
    p = tmp_path / "imgscores.json"
    p.write_text(json.dumps({"stills": {"a": 0.2, "b": 0.9, "c": 0.7}, "items": {
        "a" * 32: {"anim": False, "backdrops": [{"i": 0, "tag": "t", "s": 0.1}, {"i": 1, "tag": "t", "s": 0.95},
                                                {"i": 2, "tag": "t", "s": None}, {"i": 3, "tag": "t", "s": 0.6}]},
        "b" * 32: {"anim": True, "backdrops": [{"i": 0, "tag": "t", "s": None}, {"i": 1, "tag": "t", "s": None}]}}}))
    return str(p)


def test_stills_ranked_photo_first_unscored_next_low_last(tmp_path):
    from jellytip_api.imgscores import ImgScores
    r = ImgScores(_scores_file(tmp_path)).rank_stills(
        [{"url": u} for u in ("a", "z", "b", "c")], limit=3)
    assert [(s["url"], s["photoScore"]) for s in r] == [("b", 0.9), ("c", 0.7), ("z", None)]


def test_stills_without_score_file_keep_input_order(tmp_path):
    from jellytip_api.imgscores import ImgScores
    r = ImgScores(str(tmp_path / "missing.json")).rank_stills([{"url": "x"}, {"url": "y"}])
    assert [s["url"] for s in r] == ["x", "y"] and r[0]["photoScore"] is None


def test_backdrops_ordered_by_photo_score_and_animation_natural(tmp_path):
    from jellytip_api.imgscores import ImgScores
    s = ImgScores(_scores_file(tmp_path))
    assert [b["index"] for b in s.backdrops("a" * 32)["backdrops"]] == [1, 3, 2, 0]
    a = s.backdrops("b" * 32)
    assert a["animation"] and [b["index"] for b in a["backdrops"]] == [0, 1]
    assert s.backdrops("c" * 32) is None


def test_backdrops_route_requires_auth_and_returns_null_for_unscored(tmp_path):
    from jellytip_api.imgscores import ImgScores
    svc = Service({"JELLYTIP_TOKEN": "tok", "JELLYTIP_DB": str(tmp_path / "c.db")}, scores=object(),
                  stills=lambda *a: ([], True), letterboxd=object(), cache=Cache(str(tmp_path / "c2.db")),
                  imgscores=ImgScores(_scores_file(tmp_path)))
    assert svc.route("/v1/items/" + "a" * 32 + "/backdrops", {}, "bad")[0] == 401
    st, body = svc.route("/v1/items/" + "a" * 32 + "/backdrops", {}, "tok")
    assert st == 200 and body["backdrops"][0] == {"index": 1, "photoScore": 0.95, "tag": "t", "phash": None, "focalX": None, "focalY": None, "face": None, "lum": None}
    assert svc.route("/v1/items/" + "c" * 32 + "/backdrops", {}, "tok")[1]["backdrops"] is None


def _hero_scores(tmp_path, thumb, hashes, anim=False):
    p = tmp_path / "h.json"
    p.write_text(json.dumps({"stills": {}, "items": {"d" * 32: {
        "anim": anim, "thumb": {"tag": "t", "h": thumb} if thumb else None,
        "backdrops": [{"i": i, "tag": "t", "s": s, "h": h} for i, (s, h) in enumerate(hashes)]}}}))
    from jellytip_api.imgscores import ImgScores
    return ImgScores(str(p)).backdrops("d" * 32)


A, B, C = "0" * 16, "f" * 16, "00000000ffffffff"  # A-B 64 bits apart, A-C 32, B-C 32


def test_hero_differs_from_thumb(tmp_path):
    r = _hero_scores(tmp_path, A, [(0.95, A), (0.9, B)])  # best backdrop is the thumb's photo
    assert r["thumbPhash"] == A and r["heroIndex"] == 1


def test_hero_without_thumb_avoids_top_ranked_backdrop(tmp_path):
    r = _hero_scores(tmp_path, None, [(0.95, A), (0.9, A), (0.8, B)])  # index 1 is a near-duplicate of the card's backdrop
    assert r["thumbPhash"] is None and r["heroIndex"] == 2


def test_hero_near_hash_is_not_different_enough(tmp_path):
    near = "000000000000007f"  # 7 bits from A: below the 12 threshold
    assert _hero_scores(tmp_path, A, [(0.95, near), (0.9, C)])["heroIndex"] == 1


def test_hero_falls_back_to_most_different(tmp_path):
    a5, a11 = "000000000000001f", "00000000000007ff"  # 5 and 11 bits from A: none beats 12, so the farther one wins
    assert _hero_scores(tmp_path, A, [(0.95, a5), (0.9, a11)])["heroIndex"] == 1


def test_hero_without_hashes_avoids_top_backdrop_or_single(tmp_path):
    assert _hero_scores(tmp_path, None, [(0.9, None), (0.8, None)])["heroIndex"] == 1
    assert _hero_scores(tmp_path, None, [(0.9, None)])["heroIndex"] == 0
    assert _hero_scores(tmp_path, None, [])["heroIndex"] is None


def test_subtitle_pick_prefers_release_match_then_downloads_and_skips_hi_forced():
    from jellytip_api.subtitles import pick
    r = [{"Id": "a", "Name": "Show.S01E01.720p.WEB", "DownloadCount": 5},
         {"Id": "b", "Name": "Show.S01E01.1080p.BluRay.x264-GRP", "DownloadCount": 1},
         {"Id": "c", "Name": "Show.S01E01.1080p.BluRay.x264-GRP", "DownloadCount": 9, "HearingImpaired": True},
         {"Id": "d", "Name": "Show.S01E01.1080p.BluRay.x264-GRP", "DownloadCount": 99, "Forced": True}]
    assert pick(r, "Show.S01E01.1080p.BluRay.x264-GRP.mkv")["Id"] == "b"
    assert pick([], "x.mkv") is None


def test_trailer_sweep_removes_only_orphan_trailers_dirs(tmp_path):
    from jellytip_api import trailers
    movies = tmp_path / "Movies"
    for name, has in (("Alive (2000)", True), ("Gone (2001)", False)):
        (movies / name / "trailers").mkdir(parents=True)
        (movies / name / "trailers" / "t-trailer.mp4").write_bytes(b"x")
        (movies / name / "poster.jpg").write_bytes(b"x")
        if has:
            (movies / name / "m.mkv").write_bytes(b"x")
    dry = trailers.sweep([str(movies)], apply=False, log=lambda m: None)
    assert [os.path.basename(os.path.dirname(o)) for o in dry["orphans"]] == ["Gone (2001)"] and (movies / "Gone (2001)" / "trailers").exists()
    trailers.sweep([str(movies)], apply=True, log=lambda m: None)
    assert not (movies / "Gone (2001)" / "trailers").exists() and (movies / "Gone (2001)" / "poster.jpg").exists()
    assert (movies / "Alive (2000)" / "trailers" / "t-trailer.mp4").exists()
    assert trailers.remove_for_folder(str(movies / "Alive (2000)"), log=lambda m: None) == "title still has media"


def test_trailer_sweep_refuses_unmounted_root(tmp_path):
    import pytest
    from jellytip_api import trailers
    (tmp_path / "Movies").mkdir()
    with pytest.raises(RuntimeError):
        trailers.sweep([str(tmp_path / "Movies")], apply=True)


def test_hero_and_details_differ_from_tile_and_each_other(tmp_path):
    far = "00000000ffffffff"  # 32 bits from A and from B
    r = _hero_scores(tmp_path, A, [(0.95, A), (0.9, B), (0.85, far), (0.8, B)])
    assert (r["heroIndex"], r["detailsIndex"]) == (1, 2)  # 0 is the tile's photo; 3 duplicates the hero
    r = _hero_scores(tmp_path, None, [(0.95, A), (0.9, B), (0.85, far)])  # no Thumb: tile = top-ranked backdrop
    assert (r["heroIndex"], r["detailsIndex"]) == (1, 2)


def test_bad_thumb_means_card_uses_backdrop_composite(tmp_path, monkeypatch):
    monkeypatch.setattr("jellytip_api.imgscores.TITLE_THRESHOLD", 0.5)
    p = tmp_path / "t.json"
    p.write_text(json.dumps({"stills": {}, "items": {"d" * 32: {
        "anim": False, "thumb": {"tag": "t", "h": B, "s": 0.1},  # poor titled art -> card falls back to rows[0]
        "backdrops": [{"i": 0, "tag": "t", "s": 0.95, "h": A}, {"i": 1, "tag": "t", "s": 0.9, "h": B}]}}}))
    from jellytip_api.imgscores import ImgScores
    r = ImgScores(str(p)).backdrops("d" * 32)
    assert r["thumb"]["good"] is False and r["heroIndex"] == 1  # tile is backdrop 0 (A); B differs, thumb ignored


def test_episodes_art_usable_rules(tmp_path):
    p = tmp_path / "e.json"
    ep = lambda s, lap, lum, anim=False: {"series": "s" * 32, "season": "n" * 32, "tag": "t", "s": s, "lap": lap, "lum": lum, "anim": anim}
    p.write_text(json.dumps({"stills": {}, "items": {}, "episodes": {
        "e1": ep(0.9, 300, 100), "e2": ep(0.9, 5, 100), "e3": ep(0.9, 300, 3), "e4": ep(0.2, 300, 100),
        "e5": ep(None, 300, 100, True), "e6": ep(None, None, None)}}))
    from jellytip_api.imgscores import ImgScores
    got = {e["episodeId"]: e["usable"] for e in ImgScores(str(p)).episodes_art("s" * 32)}
    assert got == {"e1": True, "e2": False, "e3": False, "e4": False, "e5": True, "e6": False}
    assert ImgScores(str(p)).episodes_art("n" * 32) == ImgScores(str(p)).episodes_art("s" * 32)


def _rot_lb(tmp_path, n_lists=10):
    export = tmp_path / "lb.json"
    export.write_text(json.dumps({"films": {}, "lists": [
        {"slug": f"l{k:02d}", "name": f"List {k}", "items": [{"tmdb": k * 10 + m, "title": f"t{k}{m}", "year": 2000} for m in range(5)]}
        for k in range(n_lists)]}))
    # every list has its first 3 films in the library, except l09 which has only 1
    jf = [{"Id": f"j{k * 10 + m}", "ProviderIds": {"Tmdb": str(k * 10 + m)}} for k in range(n_lists) for m in range(1 if k == 9 else 3)]
    return Letterboxd(str(export), lambda: jf, tmdb_images=lambda t: (f"p{t}", f"b{t}"))


def test_letterboxd_rotation_is_daily_stable_pins_first_and_covers_all(tmp_path):
    import datetime
    lb = _rot_lb(tmp_path)
    cfg = {"per_day": 4, "pinned": ["List 5"], "exclude": ["l01"]}
    d0 = datetime.date(2026, 10, 28)
    day = lambda n: [r["slug"] for r in lb.home_lists(cfg, today=d0 + datetime.timedelta(days=n))]
    assert day(0) == day(0) and day(0)[0] == "l05" and len(day(0)) == 4 and day(0) != day(1)
    seen = {s for n in range(8) for s in day(n)}
    assert seen == {f"l{k:02d}" for k in range(10)} - {"l01", "l09"}  # l09 lacks 3 library films, l01 excluded


def test_letterboxd_discover_rows_only_with_flag(tmp_path):
    import datetime
    lb = _rot_lb(tmp_path)
    cfg = {"per_day": 20}
    plain = lb.home_lists(cfg, today=datetime.date(2026, 1, 1))
    assert all("items" not in r for r in plain) and "l09" not in [r["slug"] for r in plain]
    full = {r["slug"]: r for r in lb.home_lists(cfg, include_discover=True, today=datetime.date(2026, 1, 1))}
    assert full["l09"]["itemIds"] == ["j90"] and [i["tmdbId"] for i in full["l09"]["items"]] == [91, 92, 93, 94]
    assert full["l09"]["items"][0]["discover"] is True and full["l09"]["items"][0]["posterUrl"] == "p91"
    assert all(r["source"] == "letterboxd" for r in full.values())


def test_liked_lists_take_at_least_liked_weight_slots_and_are_named_by_owner(tmp_path):
    import datetime
    export = tmp_path / "lb.json"
    mk = lambda slug, name, base, **kw: {"slug": slug, "name": name, **kw, "items": [{"tmdb": base + m, "title": "t", "year": 1} for m in range(4)]}
    export.write_text(json.dumps({"films": {}, "lists": [mk(f"own{k}", f"Own {k}", k * 10) for k in range(6)]
                                  + [mk(f"bob--l{k}", f"Liked {k}", 100 + k * 10, owner="bob", liked=True) for k in range(3)]}))
    jf = [{"Id": f"j{t}", "ProviderIds": {"Tmdb": str(t)}} for k in range(9) for t in ([k * 10 + m for m in range(4)] if k < 6 else [100 + (k - 6) * 10 + m for m in range(3)])]
    lb = Letterboxd(str(export), lambda: jf)
    rows = lb.home_lists({"per_day": 4, "liked_weight": 2}, today=datetime.date(2026, 10, 28))
    liked = [r for r in rows if r.get("liked")]
    assert len(rows) == 4 and len(liked) == 2 and liked[0]["name"].endswith("(by bob)") and liked[0]["owner"] == "bob"
    assert not any(r.get("liked") for r in lb.home_lists({"per_day": 4, "include_liked": False}, today=datetime.date(2026, 10, 28)))


def test_hero_skips_dark_frame_when_a_brighter_one_exists(tmp_path):
    p = tmp_path / "l.json"
    p.write_text(json.dumps({"stills": {}, "items": {"d" * 32: {"anim": False, "thumb": None, "backdrops": [
        {"i": 0, "tag": "t", "s": 0.99, "h": A, "lum": 80}, {"i": 1, "tag": "t", "s": 0.95, "h": B, "lum": 12},
        {"i": 2, "tag": "t", "s": 0.9, "h": "00000000ffffffff", "lum": 90}]}}}))
    from jellytip_api.imgscores import ImgScores
    assert ImgScores(str(p)).backdrops("d" * 32)["heroIndex"] == 2  # 1 is differs-from-tile but murky


def test_backdrops_reindexed_by_current_tags(tmp_path):
    p = tmp_path / "r.json"
    p.write_text(json.dumps({"stills": {}, "items": {"d" * 32: {"anim": False, "thumb": None, "backdrops": [
        {"i": 0, "tag": "old", "s": 0.99, "h": A}, {"i": 1, "tag": "keep", "s": 0.9, "h": B}, {"i": 2, "tag": "new?", "s": 0.8, "h": C}]}}}))
    from jellytip_api.imgscores import ImgScores
    r = ImgScores(str(p)).backdrops("d" * 32, current_tags=["keep", "other"])
    assert [(b["tag"], b["index"]) for b in r["backdrops"]] == [("keep", 0)]  # "old"/"new?" vanished; "keep" moved 1 -> 0
    assert ImgScores(str(p)).backdrops("d" * 32)["backdrops"][0]["index"] == 0


def test_intro_endpoints(tmp_path):
    import json as _json
    from jellytip_api.server import FileBody, Service
    svc = Service.__new__(Service)
    svc.env = {"INTRO_DIR": str(tmp_path)}
    assert svc.intro(False)[0] == 404
    (tmp_path / "intro.mp4").write_bytes(b"x")
    (tmp_path / "intro.json").write_text(_json.dumps({"version": "abc", "titles": ["A (2000)"]}))
    st, body = svc.intro(False)
    assert st == 200 and body["version"] == "abc" and body["url"] == "v1/intro.mp4"
    st, fb = svc.intro(True)
    assert st == 200 and isinstance(fb, FileBody) and fb.etag == "abc"


def test_extras_blank_ids_resolved_from_jellyfin_item(tmp_path):
    calls = []
    s = make_service(tmp_path, calls)

    class FakeJf:
        ok = True

        def uid(self):
            return "u"

        def call(self, method, path, params=None, timeout=30):
            assert path == "/Users/u/Items/" + "a" * 32
            return {"Type": "Movie", "ProviderIds": {"Tmdb": "829", "Imdb": "tt0034583"}}
    s.jf = FakeJf()
    st, body = s.route("/v1/items/" + "a" * 32 + "/extras", {"tmdb": "", "imdb": "", "type": "movie"}, "tok")
    assert st == 200 and calls == ["829"] and body["scores"]
