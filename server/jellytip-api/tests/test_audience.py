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


def test_audience_route_is_admin_only(tmp_path):
    import types
    from jellytip_api.cache import Cache
    from jellytip_api.server import Service
    svc = Service({"JELLYTIP_TOKEN": "secret", "JELLYFIN_URL": "http://jf", "JELLYFIN_KEY": "k"},
                  scores=object(), stills=lambda *a: ([], True), letterboxd=object(), cache=Cache(str(tmp_path / "c.db")))
    svc.jellyfin_ok = types.MethodType(lambda self, t, ip="": t in ("admin", "user"), svc)
    svc.jellyfin_admin = types.MethodType(lambda self, t, ip="": "Ricki" if t == "admin" else None, svc)
    svc.audience.get = lambda jid: (200, {"requestedBy": [], "watchedBy": []})
    path = "/v1/items/" + "a" * 32 + "/audience"
    assert svc.route(path, {}, "secret")[0] == 403  # shared app token is never admin
    assert svc.route(path, {}, None, 'MediaBrowser Token="user"')[0] == 403
    assert svc.route(path, {}, None, 'MediaBrowser Token="admin"') == (200, {"requestedBy": [], "watchedBy": [], "you": "Ricki"})
