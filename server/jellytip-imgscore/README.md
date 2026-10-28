# jellytip-imgscore

CLIP (open_clip ViT-B-32 laion2b) zero-shot realism scorer. Runs on palawan (CPU, ~11 images/s), venv `~/venvs/imgscore`.
Score = P(live-action photo still) vs poster / illustration / logo / collage / cartoon prompts (see realism.py).

- `score_library.py` scores TMDB stills (fetched at w780, up to 30 candidates per title) and Jellyfin backdrops for every movie/series, writes `~/jellytip-api/data/imgscores.json` atomically; resumable. Animation titles are skipped (their art is kept).
- `nightly.sh` incremental cron (05:30); log `~/jellytip-imgscore/run.log`.
- `calibrate.py OUTDIR` builds numbered contact sheets with scores for eyeballing.

Threshold 0.6 (companion `imgscores.THRESHOLD`): labelled sample of 60 images (Chinatown, Paris Texas, animation, random). Non-animation: 27/27 photo calls were real stills; misses are posed promo shoots and one Beatles still at 0.59. 0.5 let a graded poster (Chinatown, 0.51) through.
