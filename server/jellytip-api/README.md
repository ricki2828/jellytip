# jellytip-api

Companion API for the Jellytip Android TV client. Stdlib-only Python 3 (no venv/deps), runs on the seedbox.

Contract v1: `GET /v1/health`, `GET /v1/items/{jellyfinId}/extras?tmdb=&imdb=&type=movie|series`,
`GET /v1/letterboxd/lists`, `GET /v1/letterboxd/lists/{slug}`, `GET /v1/library/sizes`, `POST /v1/subtitles/season` + `GET /v1/subtitles/jobs/{id}`, `POST /v1/items/{id}/deleted` (trailer cleanup; nightly `python3 -m jellytip_api.trailers sweep --apply` in cron). All but health need header `X-Jellytip-Token`.

Config is environment only (see `jellytip.env.example`); the real env file stays on the host, mode 0600.
Letterboxd data is a JSON export produced by the scraper cron on another host and rsynced to `LB_EXPORT`.

    python3 -m pytest tests      # tests
    python3 -m jellytip_api      # run (reads env)
    ./deploy.sh user@host        # rsync code + install systemd --user unit

## Abuse limits (server-side)

- Jellyfin tokens are only validated upstream when they are 32 hex chars, at most 4 at a time, and 10 burst / 1 per second per client IP; auth results are cached 2 min.
- All authenticated routes: 15 req/s (burst 60) per client IP, then 429. Client IP is `X-Real-IP` (set by nginx, see below) else the socket peer.
- `/extras` cache misses: 60/min globally (MDBList/TMDB quotas), then 429. Cache hits are unaffected.
- Subtitle jobs: 2 running at once (429 beyond), language `^[a-z]{2,3}$`, admin only (as are `/deleted` and the job status route).
- `POST /v1/request`: free space minus the Radarr/Sonarr queue remainder and the estimate of adds from the last 72 h that have no file yet
  (ledger `jellytip_requests_pending.json`) must stay above 30 GB; 5 requests/day per user (admins exempt), 15/day overall.

nginx: `proxy.d/jellytip-api.conf` should carry `proxy_set_header X-Real-IP $remote_addr;` so per-IP limits see real clients.
`limit_req` needs a `limit_req_zone` at `http {}` level, and the seedbox's `nginx.conf` is managed and has no `conf.d`, so it is not
configured here. If you can edit it: `limit_req_zone $binary_remote_addr zone=jt:10m rate=20r/s;` in `http {}` and
`limit_req zone=jt burst=60 nodelay;` inside `location ^~ /jellytip-api/`.
