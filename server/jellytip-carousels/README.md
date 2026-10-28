# Jellytip carousels

`sync_carousels.py` mirrors Ricki's Kometa collection YAMLs (read-only, `~/Kometa/config`) as Jellyfin
collections and writes `carousels.json`, served by the companion API at `GET /v1/home/carousels`.

- Runs on palawan: `15 5 * * *`, after Kometa's 03:30-05:00 rotation. Python: `~/.pyenv/versions/3.11.9/bin/python3` (PyYAML).
- Supported builders: `tmdb_movie|trending_daily|top_rated|popular|keyword|discover`, `plex_search`/`smart_filter`
  (genre, director, country, year, critic/audience rating, added, label, audio_codec), `plex_all`, `filters`.
- Skipped, listed in `last_run.json`: `tautulli_popular` (Tautulli not running), `imdb_list` (IMDb page is JS-rendered;
  `The Rewatchables` is kept by JFC's `rewatchables_sync.py`), requester `label` shelves (no tags in Jellyfin).
- JFC (`~/jellyfin-collection`, cron 04:30) still owns its 7 library-genre collections; `Trending Now` moved here.
- Manifest: `{version, generated, carousels:[{id,name,title,sortKey,library,order,itemIds}]}` in home order.
  Rows under 3 items are synced as collections but left out (except a one-film "Tonight's Pick").
- Letterboxd lists are NOT mirrored as collections: the companion already serves them with `jellyfinIds`.
