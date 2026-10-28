import copy
import os
import sys
import types

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import pytest

from jellytip_api import request as rqmod
from jellytip_api.cache import Cache
from jellytip_api.request import Requests
from jellytip_api.server import Bucket, Service
from jellytip_api.subtitles import Busy, Jobs
from jellytip_api import trailers
from test_request import Fake, MOVIES, SERIES, MAT, SEV

GB = 1024 ** 3
ADMIN, USER = "a" * 32, "b" * 32


class LiveFake(Fake):
    """Fake whose POST /movie|/series adds the title (no file yet), like the real arr."""
    def call(self, method, path, params=None, body=None, timeout=30):
        if method == "POST":
            self.store.append({**body, "id": 100 + len(self.store), "hasFile": False})
            return {}
        return super().call(method, path, params, body, timeout)


def mk(tmp_path, free=80.0, rq=None, sq=None, clock=None):
    r, s = LiveFake(copy.deepcopy(MOVIES), [MAT], rq), LiveFake(copy.deepcopy(SERIES), [SEV], sq)
    t = clock or (lambda: 1000.0)
    return Requests(r, s, str(tmp_path / "req.jsonl"), free_gb=lambda: free, clock=t), r, s


def test_pending_adds_reduce_free_space(tmp_path):
    rq, r, s = mk(tmp_path, free=45.0)
    assert rq.request(603, "movie", "A")[1]["status"] == "added"  # 45 - 8 = 37 >= 30
    r.lk = [{"title": "Two", "year": 2000, "tmdbId": 604}]
    b = rq.request(604, "movie", "B")[1]
    assert b["status"] == "blocked" and b["pendingGb"] == 8.0 and b["freeGbAfter"] == 29.0  # 45 - 8 pending - 8 new


def test_pending_ledger_persists_and_expires(tmp_path):
    now = [1000.0]
    rq, r, s = mk(tmp_path, free=45.0, clock=lambda: now[0])
    rq.request(603, "movie", "A")
    rq2 = Requests(r, s, str(tmp_path / "req.jsonl"), free_gb=lambda: 45.0, clock=lambda: now[0])  # fresh process
    assert rq2.pending_gb() == 8.0
    now[0] += 73 * 3600
    assert rq2.pending_gb() == 0


def test_pending_ends_when_file_lands(tmp_path):
    rq, r, s = mk(tmp_path)
    rq.request(603, "movie", "A")
    r.store[-1]["hasFile"] = True
    assert rq.pending_gb() == 0


def test_arr_queue_sizeleft_counts_and_replaces_estimate(tmp_path):
    rq, r, s = mk(tmp_path, rq=[{"movieId": 1, "sizeleft": 20 * GB}])
    assert rq.pending_gb() == 20.0
    rq.request(603, "movie", "A")
    new_id = r.store[-1]["id"]
    r.q = [{"movieId": 1, "sizeleft": 20 * GB}, {"movieId": new_id, "sizeleft": 3 * GB}]
    assert rq.pending_gb() == 23.0  # queued title uses real sizeleft, not the 8 GB estimate


def test_queue_blocks_request(tmp_path):
    rq, r, s = mk(tmp_path, free=60.0, rq=[{"movieId": 1, "sizeleft": 30 * GB}])
    st, b = rq.request(603, "movie", "A")
    assert b["status"] == "blocked" and b["freeGbAfter"] == 22.0


def test_per_user_and_global_caps(tmp_path):
    rq, r, s = mk(tmp_path, free=5000.0)
    for i in range(rqmod.USER_DAILY_CAP):
        r.lk = [{"title": "t", "tmdbId": 1000 + i}]
        assert rq.request(1000 + i, "movie", "U")[1]["status"] == "added"
    r.lk = [{"title": "t", "tmdbId": 2000}]
    b = rq.request(2000, "movie", "U")[1]
    assert b["status"] == "blocked" and "requests for today" in b["reason"]
    assert rq.request(2000, "movie", "U", dry_run=True)[1]["status"] == "blocked"
    assert rq.request(2000, "movie", "V")[1]["status"] == "added"  # other user fine
    for i in range(rqmod.GLOBAL_DAILY_CAP - rqmod.USER_DAILY_CAP - 1):  # admin exempt from per-user cap
        r.lk = [{"title": "t", "tmdbId": 3000 + i}]
        assert rq.request(3000 + i, "movie", "ADM", is_admin=True)[1]["status"] == "added"
    r.lk = [{"title": "t", "tmdbId": 4000}]
    b = rq.request(4000, "movie", "ADM", is_admin=True)[1]
    assert b["status"] == "blocked" and "daily request limit" in b["reason"]


def make_svc(tmp_path, **kw):
    return Service({"JELLYTIP_TOKEN": "sec", "JELLYFIN_URL": "http://jf", **kw}, scores=kw.get("scores") or object(),
                   stills=lambda *a: ([], True), letterboxd=object(), cache=Cache(str(tmp_path / "c.db")))


def fake_jf(monkeypatch, calls):
    import urllib.request as ur

    class R:
        status = 200
        def __init__(self, tok): self.tok = tok
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def read(self):
            import json
            return json.dumps({"Name": "ricki28", "Policy": {"IsAdministrator": self.tok == ADMIN}}).encode()
    def urlopen(req, timeout=0):
        tok = req.headers["Authorization"].split('"')[1]
        calls.append(req.full_url)
        return R(tok)
    monkeypatch.setattr(ur, "urlopen", urlopen)


def test_malformed_tokens_never_reach_jellyfin(tmp_path, monkeypatch):
    calls = []
    fake_jf(monkeypatch, calls)
    svc = make_svc(tmp_path)
    for bad in ("x", "a" * 31, "g" * 32, "a" * 33):
        assert svc.route("/v1/letterboxd/lists", {}, None, None, bad)[0] == 401
    assert calls == []
    assert svc.route("/v1/nope", {}, None, None, USER)[0] == 404 and len(calls) == 1


def test_validation_concurrency_and_ip_rate_limit(tmp_path, monkeypatch):
    calls = []
    fake_jf(monkeypatch, calls)
    svc = make_svc(tmp_path)
    for _ in range(4):
        assert svc._jf_sem.acquire(timeout=0)
    assert svc.jellyfin_ok(USER) is False and calls == []  # all four slots busy: denied, not cached
    for _ in range(4):
        svc._jf_sem.release()
    assert svc.jellyfin_ok(USER) is True
    # per-IP: 10 burst of uncached validations, then denied
    ok = [svc.jellyfin_ok(f"{i:032x}", "1.2.3.4") for i in range(14)]
    assert ok[:10] == [True] * 10 and not any(ok[10:])
    assert svc.jellyfin_ok(f"{99:032x}", "5.6.7.8") is True  # other IP unaffected


def test_request_rate_limit_429(tmp_path):
    svc = make_svc(tmp_path)
    codes = [svc.route("/v1/nope", {}, "sec", ip="9.9.9.9")[0] for _ in range(70)]
    assert codes[0] == 404 and codes[-1] == 429
    assert svc.route("/v1/health", {}, None, ip="9.9.9.9")[0] == 200


def test_bucket_refills():
    t = [0.0]
    b = Bucket(1, 2, clock=lambda: t[0])
    assert b.allow("k") and b.allow("k") and not b.allow("k")
    t[0] += 1.5
    assert b.allow("k")


def test_deleted_and_subtitles_admin_only(tmp_path, monkeypatch):
    calls = []
    fake_jf(monkeypatch, calls)
    svc = make_svc(tmp_path)
    svc.jf = types.SimpleNamespace(ok=True, uid=lambda: "u", call=lambda *a, **k: {})
    iid, sid = "c" * 32, "d" * 32
    for tok, want in ((None, 401), ("sec", 403), (USER, 403)):
        kw = {"token": tok} if tok == "sec" else {}
        for path, body in ((f"/v1/items/{iid}/deleted", {"path": "/x"}), ("/v1/subtitles/season", {"seasonId": sid})):
            assert svc.route(path, {}, "sec" if tok == "sec" else None, None, USER if tok == USER else None, "POST", body)[0] == (403 if tok else 401)
    assert svc.route("/v1/subtitles/jobs/" + "0" * 12, {}, None, None, USER)[0] == 403
    assert svc.route(f"/v1/items/{iid}/deleted", {}, None, None, ADMIN, "POST", {"path": "/etc"})[0] == 200  # path-safety still applies
    assert svc.route("/v1/subtitles/jobs/" + "0" * 12, {}, None, None, ADMIN)[0] == 404


def test_subtitle_language_validated(tmp_path, monkeypatch):
    fake_jf(monkeypatch, [])
    svc = make_svc(tmp_path)
    svc.jf = types.SimpleNamespace(ok=True)
    svc.subs = types.SimpleNamespace(start=lambda sid, lang: "job1")
    for bad in ("../x", "en/../..", "e", "engl", "en us", "EN1"):
        assert svc.route("/v1/subtitles/season", {}, None, None, ADMIN, "POST", {"seasonId": "d" * 32, "language": bad})[0] == 400
    assert svc.route("/v1/subtitles/season", {}, None, None, ADMIN, "POST", {"seasonId": "d" * 32, "language": "EN"})[0] == 202


def test_subtitle_jobs_capped_and_paths_encoded():
    started = []
    class JF:
        ok = True
        def items(self, **kw): return []
    j = Jobs(JF())
    j._run = lambda job: started.append(job)
    j.start("a" * 32, "eng"); j.start("b" * 32, "eng")
    with pytest.raises(Busy):
        j.start("c" * 32, "eng")
    assert j.start("a" * 32, "eng")  # same season/lang still returns the existing job
    for job in j.jobs.values():
        job["state"] = "done"
    j.start("c" * 32, "eng")  # slot freed
    from jellytip_api.subtitles import q
    assert q("a/b?c") == "a%2Fb%3Fc"


def test_content_length_clamped(tmp_path):
    import io
    from jellytip_api.server import make_handler
    svc = make_svc(tmp_path)
    h = make_handler(svc)
    for raw in ("-5", "abc", "999999999"):
        obj = h.__new__(h)
        obj.headers = {"Content-Length": raw}
        obj.rfile = io.BytesIO(b"{}" * 100000)
        seen = {}
        obj.do_GET = lambda method="GET", body=None, seen=seen: seen.update(body=body)
        h.do_POST(obj)
        assert seen["body"] == {} or seen["body"] is not None
        assert obj.rfile.tell() <= 65536


def test_trailer_sweep_knows_more_video_exts():
    for ext in (".vob", ".mts", ".m2ts", ".flv", ".ogm", ".rmvb", ".wmv", ".iso"):
        assert ext in trailers.VIDEO_EXT


def test_extras_miss_limit_and_blank_ids(tmp_path):
    calls = []
    class Scores:
        def lookup(self, tmdb, imdb, kind):
            calls.append(tmdb)
            return [], True
    svc = Service({"JELLYTIP_TOKEN": "sec", "JELLYFIN_URL": ""}, scores=Scores(), stills=lambda *a: ([], True),
                  letterboxd=types.SimpleNamespace(film=lambda t: None), cache=Cache(str(tmp_path / "c.db")))
    svc.imgscores = types.SimpleNamespace(rank_stills=lambda s: s)
    svc.jf = types.SimpleNamespace(ok=False)
    assert svc.route("/v1/items/"+"e"*32+"/extras", {}, "sec")[0] == 200 and calls == []  # blank ids: no upstream
    for i in range(60):
        assert svc.route("/v1/items/"+"e"*32+"/extras", {"tmdb": str(i + 1)}, "sec", ip=str(i))[0] == 200
    assert svc.route("/v1/items/"+"e"*32+"/extras", {"tmdb": "999"}, "sec", ip="z")[0] == 429
    assert svc.route("/v1/items/"+"e"*32+"/extras", {"tmdb": "1"}, "sec", ip="z")[0] == 200  # cache hit still served


def test_extras_rejects_non_guid_ids_and_storage_error_is_generic(tmp_path, monkeypatch):
    svc = make_svc(tmp_path)
    assert svc.route("/v1/items/../../Users/extras", {}, "sec")[0] == 404
    assert svc.route("/v1/items/abc/extras", {}, "sec")[0] == 404
    fake_jf(monkeypatch, [])
    from jellytip_api import storage
    monkeypatch.setattr(storage, "get", lambda: (_ for _ in ()).throw(RuntimeError("secret /path")))
    st, b = svc.route("/v1/storage", {}, None, None, ADMIN)
    assert st == 503 and b == {"error": "quota unavailable"}


def test_episodes_art_non_series_is_empty_200(tmp_path):
    svc = make_svc(tmp_path)
    svc.imgscores = types.SimpleNamespace(episodes_art=lambda i: [])
    assert svc.route("/v1/items/" + "9" * 32 + "/episodes-art", {}, "sec") == (200, [])
