import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { createHash } from "node:crypto";
import { readFileSync, existsSync } from "node:fs";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Production is served from https://ricki28.palawan.usbx.me/jellytip/, same origin as /jellyfin and /jellytip-api.
// In dev, those two paths are proxied to the same host so the app never talks cross-origin.
const UPSTREAM = "https://ricki28.palawan.usbx.me";

// Production only: dev needs inline scripts (React refresh) and injected <style> tags that this policy forbids.
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob: https://image.tmdb.org https://a.espncdn.com https://*.thesportsdb.com", // TMDB posters (closet picks); team crests and venue photos (match info)
  "media-src 'self' blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

function securityMeta(): Plugin {
  return {
    name: "jellytip-security-meta",
    apply: "build",
    transformIndexHtml: () => [
      { tag: "meta", attrs: { "http-equiv": "Content-Security-Policy", content: CSP }, injectTo: "head-prepend" },
      { tag: "meta", attrs: { name: "referrer", content: "same-origin" }, injectTo: "head-prepend" },
    ],
  };
}

// Build identity. The deploy script exports JELLYTIP_BUILD_SHA / JELLYTIP_BUILD_TIME (it builds from a `git archive`, no .git);
// otherwise ask git when a repo is there, else "dev".
function buildIdentity() {
  let sha = (process.env.JELLYTIP_BUILD_SHA ?? "").trim();
  if (!sha && existsSync(fileURLToPath(new URL("../.git", import.meta.url))) ) {
    try {
      sha = execSync("git rev-parse --short=8 HEAD", { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    } catch {
      // no git binary: fall through
    }
  }
  if (!/^[0-9a-z]{4,40}$/i.test(sha)) sha = "dev";
  const t = (process.env.JELLYTIP_BUILD_TIME ?? "").trim();
  const builtAt = sha === "dev" && !t ? "" : Number.isNaN(Date.parse(t)) ? new Date().toISOString() : t;
  return { sha, builtAt };
}
const BUILD = buildIdentity();

/**
 * Emits sw.js: an app-shell-only service worker. It precaches index + the hashed build assets + manifest/icons and
 * never touches /jellyfin, /jellytip-api or media (they don't fall under the /jellytip/ scope prefix it checks).
 */
function appShellWorker(): Plugin {
  return {
    name: "jellytip-app-shell-sw",
    apply: "build",
    generateBundle(_opts, bundle) {
      const assets = Object.keys(bundle).filter((f) => f !== "sw.js" && !f.endsWith(".map"));
      // The video engines (hls.js, mpegts.js: ~850 KB) are fetched on first playback and kept then; installing the
      // worker must not pull them down beside the first screen's requests (on every deploy, too)
      const eager = assets.filter((f) => !/(^|\/)(hls|mpegts)-/.test(f));
      const shell = ["./", "manifest.webmanifest", "favicon.svg", "icons/icon-192.png", "icons/icon-512.png", ...eager];
      const version = createHash("sha256").update(assets.sort().join("|")).digest("hex").slice(0, 12);
      const source = readFileSync(fileURLToPath(new URL("./src/sw.template.js", import.meta.url)), "utf8")
        .replace("__VERSION__", version)
        .replace("__SHELL__", JSON.stringify(shell));
      this.emitFile({ type: "asset", fileName: "sw.js", source });
      // Polled by the app (cache: no-store) to notice a newer deploy; assetsVersion = the sw cache version above
      this.emitFile({ type: "asset", fileName: "version.json", source: JSON.stringify({ sha: BUILD.sha, builtAt: BUILD.builtAt, assetsVersion: version }) + "\n" });
    },
  };
}

// `vite build --mode android` = the bundle for the Capacitor app (npm run build:android): served from the WebView's
// own https://localhost root, no service worker, and no CSP meta (Capacitor injects its bridge script inline;
// the app only ever loads its own bundled files). API calls go to VITE_SERVER_ORIGIN (.env.android).
export default defineConfig(({ mode }) => {
  const app = mode === "android";
  return {
    base: app ? "./" : "/jellytip/",
    define: { __BUILD_SHA__: JSON.stringify(BUILD.sha), __BUILD_TIME__: JSON.stringify(BUILD.builtAt) },
    plugins: app ? [react()] : [react(), securityMeta(), appShellWorker()],
    server: {
      port: 5178,
      proxy: {
        "/jellyfin": { target: UPSTREAM, changeOrigin: true, secure: true },
        "/jellytip-api": { target: UPSTREAM, changeOrigin: true, secure: true },
      },
    },
    preview: { port: 5179 },
    build: { outDir: app ? "dist-android" : "dist", sourcemap: false, chunkSizeWarningLimit: 900 },
  };
});
