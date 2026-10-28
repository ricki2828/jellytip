#!/bin/bash
# Incremental run: only new/changed images are scored. Cron: 30 5 * * * ~/jellytip-imgscore/nightly.sh
cd "$HOME/jellytip-imgscore" || exit 1
flock -n /tmp/jellytip-imgscore.lock "$HOME/venvs/imgscore/bin/python" score_library.py >> run.log 2>&1
