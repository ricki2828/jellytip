# jellytip-intro

Nightly cold-start intro for the Jellytip TV app: 5 ~1.4 s snippets from the newest live-action films (25-60% of runtime,
best of 6 candidates by exposure/sharpness/face; hard-coded-subtitle, skin-close-up and HDR sources avoided), 0.3 s dissolves,
slight push-in, mild grade, then `assets/endcard.png` (icon, wordmark, slogan). 1080p H.264 ~8 Mbps, no audio, ~7 s.

- `build_intro.py` -> `out/intro.mp4` + `out/intro.json` (atomic). Keeps the previous intro if fewer than 5 films qualify.
- `make_endcard.py` regenerates the card (Pillow). Instrument Sans variable font minimum weight is 400, so no true Light.
- Deployed by hand to palawan `~/jellytip-intro` (run with `~/venvs/imgscore/bin/python`: cv2+numpy). Seedbox caps address space
  (`ulimit -v`), hence the thread limits in the ffmpeg calls.
- Cron (palawan): `40 5 * * * cd ~/jellytip-intro && nice ~/venvs/imgscore/bin/python build_intro.py >> run.log 2>&1`
- Served by the companion API: `GET /v1/intro` -> `{url, version, titles}`, `GET /v1/intro.mp4` (ETag = version).
