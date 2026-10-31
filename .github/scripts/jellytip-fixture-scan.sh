#!/usr/bin/env bash
# Jellytip test fixtures are copies of real companion answers in a public repo: no IPTV provider names, no
# non-TMDB links (stream / play / signed image paths), no tokens or keys. Upstream Wholphin fixtures are skipped
# (only files with Jellytip keys are checked).
set -uo pipefail
dir="${1:-app/src/test/resources}"
fail=0
mapfile -t files < <(grep -rlE '"(providerName|iptvItems|sections|matchInfo|leagues)"' "$dir" --include='*.json' || true)
for f in "${files[@]}"; do
  if grep -nE '"(providerName|provider)"[[:space:]]*:[[:space:]]*"[^"]+"' "$f" | grep -v '"PROVIDER"'; then
    echo "::error file=$f::provider name in a fixture (use \"PROVIDER\")"
    fail=1
  fi
  if grep -noE 'https?://[^"[:space:]]+' "$f" | grep -vE ':https://image\.tmdb\.org/'; then
    echo "::error file=$f::non-TMDB link in a fixture (null it)"
    fail=1
  fi
  if grep -niE '\.SIG\b|[?&](token|api_?key|sig)=|"(token|apiKey|api_key|password|secret)"[[:space:]]*:|/v1/iptv/s/|/play\?' "$f"; then
    echo "::error file=$f::token, key or stream path in a fixture"
    fail=1
  fi
done
echo "Scanned ${#files[@]} Jellytip fixture(s)"
exit $fail
