import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import sync_carousels as s

NOW = dt.datetime(2026, 10, 28, tzinfo=dt.timezone.utc)


class Lib:
    items = [{"Name": n, "Genres": g} for n, g in (("a", ["Drama"]), ("b", ["Drama"]), ("c", ["Drama", "Crime"]), ("d", ["Crime"]))]


def test_tv_filters_read_enriched_facts():
    it = {"Name": "x", "_x": {"episodes_added": 3, "tmdb_rating": 8.4, "tmdb_votes": 900, "origin_country": ["gb"],
                              "partially_watched": True, "status": "ended", "avg_runtime": 25.0, "new_season": None}}
    ok = {"all": {"tmdb_rating.gte": 8, "tmdb_votes.gte": 500, "origin_country": "GB", "partially_watched": True,
                  "status": "ended", "avg_runtime.lte": 30}, "any": {"episodes_added": 7, "episode_aired": 7}}
    assert s.matches(it, ok, NOW)
    assert not s.matches(it, {"all": {"new_season": 60}}, NOW)  # None never matches a days filter
    assert not s.matches(it, {"all": {"tmdb_votes.gte": 1000}}, NOW)


def test_sort_days_ago_attrs_newest_first():
    a, b, c = ({"Name": n, "_x": {"episodes_added": d}} for n, d in (("a", 5), ("b", 1), ("c", None)))
    assert [i["Name"] for i in s.sort_items([a, b, c], "episodes_added.desc", None)] == ["b", "a", "c"]


def test_fractional_sort_keys_interleave_with_movie_rows():
    keys = ["!003_Gems", "!2.5_TV", "!002_Tonight"]
    assert sorted(keys, key=s.sort_number) == ["!002_Tonight", "!2.5_TV", "!003_Gems"]


def test_genre_rows_follow_library_counts():
    rows = s.tv_collections({"genre_rows": {"min_items": 3}}, Lib())
    assert [r[1] for r in rows] == ["Drama Series"]  # Crime has only 2 series


def test_relative_dates():
    assert s.rel_date("-60d", NOW) == "2026-08-29" and s.rel_date("2026-01-01", NOW) == "2026-01-01"
