// Jellytip Web service worker (generated at build time from src/sw.template.js; see vite.config.ts).
// Caches ONLY the app shell. Jellyfin / companion API responses, images and media are never cached or even handled:
// they live at /jellyfin and /jellytip-api, outside the "/jellytip/" prefix checked below.
const CACHE = "jellytip-shell-__VERSION__";
const SHELL = __SHELL__;
const SCOPE = new URL(self.registration.scope).pathname; // "/jellytip/"

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((c) => c.addAll(SHELL.map((p) => new URL(p, self.registration.scope).href)))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith("jellytip-shell-") && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin || !url.pathname.startsWith(SCOPE)) return; // API, images, media: straight to network
  if (req.mode === "navigate") {
    // Fresh index when online (picks up new deploys), cached shell when offline
    event.respondWith(fetch(req).catch(() => caches.match(new URL("./", self.registration.scope).href)));
    return;
  }
  // Hashed build files outside the install list (the lazy video engines) are kept after their first fetch
  event.respondWith(
    caches.match(req).then(
      (hit) =>
        hit ||
        fetch(req).then((res) => {
          if (res.ok && url.pathname.startsWith(`${SCOPE}assets/`)) {
            const copy = res.clone();
            event.waitUntil(caches.open(CACHE).then((c) => c.put(req, copy)));
          }
          return res;
        }),
    ),
  );
});
