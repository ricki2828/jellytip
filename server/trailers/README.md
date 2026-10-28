# Jellytip trailer pipeline

Runs on the seedbox (`~/jellytip-trailers/`). For each Radarr movie / Sonarr series with files,
finds the best TMDB YouTube trailer and downloads a 720p H.264 MP4 (Chromecast with Google TV
cannot hardware-decode AV1) to `<media folder>/trailers/<Folder Name>-trailer.mp4`, which Jellyfin
treats as a local trailer.

- `python3 jellytip_trailers.py [--kind movies|tv|all] [--limit N] [--only TITLE] [--dry-run]`
- Idempotent; failures recorded in `state.json` with retry backoff (1d, 3d, 7d, 30d).
- Stops on YouTube bot-check (exit 3) or free quota < 15 GB (exit 2).
- Reads TMDB/Radarr/Sonarr credentials at runtime from `~/Kometa/config/config.yml`; none are stored here.
- Cron (nightly, new items only): `30 4 * * * cd ~/jellytip-trailers && python3 jellytip_trailers.py >> run.log 2>&1`
