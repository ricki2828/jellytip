import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

// Production is served from https://ricki28.palawan.usbx.me/jellytip/, same origin as /jellyfin and /jellytip-api.
// In dev, those two paths are proxied to the same host so the app never talks cross-origin.
const UPSTREAM = "https://ricki28.palawan.usbx.me";

// Production only: dev needs inline scripts (React refresh) and injected <style> tags that this policy forbids.
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob: https://image.tmdb.org", // closet picks not in the library carry TMDB posters
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
      const shell = ["./", "manifest.webmanifest", "favicon.svg", "icons/icon-192.png", "icons/icon-512.png", ...assets];
      const version = createHash("sha256").update(assets.sort().join("|")).digest("hex").slice(0, 12);
      const source = readFileSync(fileURLToPath(new URL("./src/sw.template.js", import.meta.url)), "utf8")
        .replace("__VERSION__", version)
        .replace("__SHELL__", JSON.stringify(shell));
      this.emitFile({ type: "asset", fileName: "sw.js", source });
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
