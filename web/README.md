# Jellytip Web

Browser client for the Jellyfin server, matching the Jellytip TV app. Vite + React + TypeScript, hls.js.

    npm install
    npm run dev      # http://localhost:5178/jellytip/ (proxies /jellyfin and /jellytip-api to the seedbox)
    npm run build    # static files in dist/, served at /jellytip/ next to /jellyfin and /jellytip-api

Hash routing (`#/home`, `#/item/<id>`, `#/play/<id>`), so the static host needs no fallback rule.
The Jellyfin token lives in localStorage and is only ever sent to the same-origin `/jellyfin` and `/jellytip-api`.

Keys: arrows move focus, Enter opens, Esc goes back. Player: Space/k play-pause, ←/→ ±10 s, ↑/↓ volume,
f fullscreen, m mute, s subtitles, [ / ] subtitle delay ±0.1 s (hold to speed up), n next episode.
