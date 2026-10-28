import copy
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from jellytip_api.request import Requests, parse_quota, Arr


class Fake(Arr):
    def __init__(self, store, lookup, queue=None):
        super().__init__("http://x", "k")
        self.store, self.lk, self.q, self.posts = store, lookup, queue or [], []

    def call(self, method, path, params=None, body=None, timeout=30):
        if method == "POST":
            self.posts.append((path, body))
            return {}
        if path in ("/movie", "/series"):
            r = self.store
            return [m for m in r if m.get("tmdbId") == int(params["tmdbId"])] if params else r
        if path.endswith("/lookup"):
            return self.lk
        if path == "/queue":
            return {"records": self.q}


MOVIES = [{"id": 1, "tmdbId": 5, "qualityProfileId": 1, "rootFolderPath": "/m", "minimumAvailability": "released", "hasFile": True}]
SERIES = [{"id": 9, "tmdbId": 7, "qualityProfileId": 4, "rootFolderPath": "/t", "languageProfileId": 1, "seasonFolder": True,
           "seasons": [{"seasonNumber": 1, "statistics": {"episodeCount": 10, "episodeFileCount": 4}}]}]
SEV = {"title": "Severance", "year": 2022, "tmdbId": 95396, "tvdbId": 371980,
       "seasons": [{"seasonNumber": 0}, {"seasonNumber": 1, "statistics": {"totalEpisodeCount": 9}}, {"seasonNumber": 2}]}
MAT = {"title": "The Matrix", "year": 1999, "tmdbId": 603}


def mk(tmp_path, free=80.0, queue=None):
    r, s = Fake(copy.deepcopy(MOVIES), [MAT]), Fake(copy.deepcopy(SERIES), [SEV], queue)
    return Requests(r, s, str(tmp_path / "req.jsonl"), free_gb=lambda: free, clock=lambda: 0), r, s


def test_parse_quota():
    out = "Disk quotas for user x (uid 1):\n     Filesystem   space   quota   limit   grace   files\n     /dev/sdbk1   3666G   3725G   3725G           1445k"
    assert parse_quota(out) == (3666.0, 3725.0)
    assert parse_quota("nothing") is None


def test_dry_run_adds_nothing_and_logs_nothing(tmp_path):
    rq, r, s = mk(tmp_path)
    st, b = rq.request(95396, "tv", "Ricki", dry_run=True)
    assert st == 200 and b["status"] == "added" and b["dryRun"] and b["estimateGb"] == 27.0 and b["freeGbAfter"] == 53.0
    assert b["label"] == "Season 1 of Severance (2022)" and not s.posts and not (tmp_path / "req.jsonl").exists()


def test_tv_added_season_one_only_and_logged(tmp_path):
    rq, r, s = mk(tmp_path)
    st, b = rq.request(95396, "tv", "Ricki")
    assert b["status"] == "added"
    path, body = s.posts[0]
    assert path == "/series" and [x["monitored"] for x in body["seasons"]] == [False, True, False]
    assert body["addOptions"]["searchForMissingEpisodes"] and body["qualityProfileId"] == 4 and body["rootFolderPath"] == "/t"
    rec = json.loads((tmp_path / "req.jsonl").read_text())
    assert rec["who"] == "Ricki" and rec["tmdbId"] == 95396 and rec["tvdbId"] == 371980 and rec["type"] == "tv"


def test_movie_added_with_library_defaults(tmp_path):
    rq, r, s = mk(tmp_path)
    st, b = rq.request(603, "movie", "Greg")
    assert b["status"] == "added" and b["estimateGb"] == 8.0
    path, body = r.posts[0]
    assert path == "/movie" and body["monitored"] and body["addOptions"]["searchForMovie"] and body["qualityProfileId"] == 1


def test_exists_is_idempotent(tmp_path):
    rq, r, s = mk(tmp_path)
    assert rq.request(5, "movie", "A")[1]["status"] == "exists"
    assert rq.request(7, "tv", "A")[1]["status"] == "exists"
    assert not r.posts and not s.posts


def test_blocked_below_floor(tmp_path):
    rq, r, s = mk(tmp_path, free=35.0)
    st, b = rq.request(603, "movie", "A")
    assert b["status"] == "blocked" and b["freeGbAfter"] == 27.0 and not r.posts
    assert mk(tmp_path, free=38.0)[0].request(603, "movie", "A")[1]["status"] == "added"
    rq2 = mk(tmp_path, free=None)[0]
    assert rq2.request(603, "movie", "A")[1]["status"] == "blocked"


def test_bad_input_and_not_found(tmp_path):
    rq, r, s = mk(tmp_path)
    assert rq.request(None, "movie", "A")[0] == 400 and rq.request(1, "music", "A")[0] == 400
    r.lk = []
    assert rq.request(999, "movie", "A")[0] == 404


def test_status_states(tmp_path):
    q = [{"seriesId": 9, "size": 1000, "sizeleft": 550}]
    rq, r, s = mk(tmp_path, queue=q)
    st, b = rq.status(["movie:5", "tv:7", "tv:123", "5", "bogus"])
    assert st == 200 and b["status"] == {"movie:5": {"state": "in_library"}, "tv:7": {"state": "downloading", "progress": 45},
                                         "5": {"state": "in_library"}}
    rq2, r2, s2 = mk(tmp_path)
    r2.store[0]["hasFile"] = False
    s2.store[0]["seasons"][0]["statistics"] = {"episodeCount": 10, "episodeFileCount": 0}
    out = rq2.status(["movie:5", "tv:7"])[1]["status"]
    assert out["movie:5"]["state"] == "requested" and out["tv:7"]["state"] == "requested"
    s2.store[0]["seasons"][0]["statistics"]["episodeFileCount"] = 10
    rq2._snap = (0, None)
    assert rq2.status(["tv:7"])[1]["status"]["tv:7"]["state"] == "in_library"


def test_route_requires_named_jellyfin_user(tmp_path):
    import types
    from jellytip_api.server import Service
    from jellytip_api.cache import Cache
    rq, r, s = mk(tmp_path)
    svc = Service({"JELLYTIP_TOKEN": "sec", "JELLYFIN_URL": "http://jf"}, scores=object(), stills=lambda *a: ([], True),
                  letterboxd=object(), cache=Cache(str(tmp_path / "c.db")), requests=rq)
    svc.jellyfin_ok = types.MethodType(lambda self, t, ip="": t == "good", svc)
    svc.jellyfin_name = types.MethodType(lambda self, t, ip="": "ricki28" if t == "good" else None, svc)
    body = {"tmdbId": 603, "type": "movie"}
    assert svc.route("/v1/request", {}, None, None, None, "POST", body)[0] == 401
    assert svc.route("/v1/request", {}, "sec", None, None, "POST", body)[0] == 403  # shared token has no identity
    st, b = svc.route("/v1/request", {"dryRun": "1"}, None, None, "good", "POST", body)
    assert st == 200 and b["status"] == "added" and b["dryRun"] and not r.posts
    assert svc.route("/v1/request/status", {"tmdbIds": "movie:5,tv:7"}, "sec")[1]["status"]["movie:5"]["state"] == "in_library"
