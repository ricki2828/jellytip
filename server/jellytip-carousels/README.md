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

## TV rows: `config/tv.yml`

Kometa-style `collections:` block read after the Kometa files; copy a block to add a row. Position comes from
`sort_title: "!N_name"` (decimals interleave with movie rows, e.g. `!2.5` sits between movie rows 2 and 3).
`defaults:` merge into every row. `genre_rows:` adds one "<Genre> Series" row per library genre with >= `min_items`
series, derived on each run. "Just Added TV" stays in Kometa's `tv-carousels.yml`.

TV-only `smart_filter` keys (`all:` = AND, `any:` = at least one; `.gte/.lte/.gt/.lt` for numbers):

| Key | Meaning |
|---|---|
| `episodes_added: N` / `episode_aired: N` | newest episode added to Jellyfin / aired within N days |
| `new_season: N` | season 2+ whose first episode arrived within N days |
| `ended: N` | series ended within N days; `status: ended\|continuing` |
| `season_count`, `avg_runtime` | seasons; average episode length in minutes |
| `partially_watched: true`, `unwatched: true` | some but not all aired episodes played / nothing played |
| `last_played: N` | last episode played within N days |
| `tmdb_rating`, `tmdb_votes`, `tmdb_type` (`miniseries`...), `origin_country` (`GB`) | TMDB data |
| `genre`, `year`, `added`, `audience_rating` | as for movies |

`sort_by`: `episodes_added|new_season|ended|last_played|tmdb_rating|runtime|release|added|title` + `.asc|.desc`
(days-ago keys: `.desc` = most recent first). `limit` caps the row.

`discover:` builds rows for shows NOT in the library from TMDB: `rows:` entries use `tmdb_trending_weekly: true` or
`tmdb_discover: {...}` (TMDB /discover/tv params; `"-60d"` = relative date), and `favourite_genres: N` adds a "New
<genre> Series" row per top library genre. They are manifest-only rows (`discover: true`, `itemIds: []`,
`items: [{tmdbId, mediaType, title, year, overview, rating, posterUrl, backdropUrl}]`), no Jellyfin collection, nothing
requested. The companion omits them unless `GET /v1/home/carousels?includeDiscover=1`.

Rows under 3 items still sync as collections but stay out of the manifest (small library: "Continue a Series" and
"Recently Ended" appear once they reach 3). Tests: `python3 -m pytest server/jellytip-carousels`.
