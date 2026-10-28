import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from jellytip_api.audience import friendly, merge_watched


def test_friendly_names():
    assert friendly("richard.c304") == "Richie" and friendly("Daniel Taiaroa") == "Daniel" and friendly("Stephen") == "Stephen"


def test_merge_watched_one_row_per_person_latest_wins():
    rows = [{"name": "Daniel", "lastWatched": "2026-01-01T00:00:00Z", "source": "plex", "completed": False},
            {"name": "Daniel", "lastWatched": "2026-03-01T00:00:00Z", "source": "jellyfin", "completed": True},
            {"name": "Nadine", "lastWatched": "2026-02-01T00:00:00Z", "source": "plex", "completed": True}]
    got = merge_watched(rows)
    assert [r["name"] for r in got] == ["Daniel", "Nadine"]
    assert got[0]["source"] == "jellyfin" and got[0]["completed"] is True
