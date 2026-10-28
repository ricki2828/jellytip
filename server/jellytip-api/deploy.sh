#!/bin/bash
# Usage: ./deploy.sh user@host   (code + unit only; the env file is created/edited on the host by hand)
set -euo pipefail
cd "$(dirname "$0")"
H=${1:?user@host}
rsync -a --delete --exclude tests --exclude __pycache__ --exclude '*.env' --exclude '*.db' jellytip_api "$H":jellytip-api/
rsync -a jellytip-api.service "$H":.config/systemd/user/
ssh "$H" 'systemctl --user daemon-reload && systemctl --user enable jellytip-api >/dev/null 2>&1; systemctl --user restart jellytip-api; systemctl --user is-active jellytip-api'
