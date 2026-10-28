# jellytip-api

Companion API for the Jellytip Android TV client. Stdlib-only Python 3 (no venv/deps), runs on the seedbox.

Contract v1: `GET /v1/health`, `GET /v1/items/{jellyfinId}/extras?tmdb=&imdb=&type=movie|series`,
`GET /v1/letterboxd/lists`, `GET /v1/letterboxd/lists/{slug}`, `GET /v1/library/sizes`, `POST /v1/subtitles/season` + `GET /v1/subtitles/jobs/{id}`, `POST /v1/items/{id}/deleted` (trailer cleanup; nightly `python3 -m jellytip_api.trailers sweep --apply` in cron). All but health need header `X-Jellytip-Token`.

Config is environment only (see `jellytip.env.example`); the real env file stays on the host, mode 0600.
Letterboxd data is a JSON export produced by the scraper cron on another host and rsynced to `LB_EXPORT`.

    python3 -m pytest tests      # tests
    python3 -m jellytip_api      # run (reads env)
    ./deploy.sh user@host        # rsync code + install systemd --user unit
