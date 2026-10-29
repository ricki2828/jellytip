import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

// Production is served from https://ricki28.palawan.usbx.me/jellytip/, same origin as /jellyfin and /jellytip-api.
// In dev, those two paths are proxied to the same host so the app never talks cross-origin.
const UPSTREAM = "https://ricki28.palawan.usbx.me";

// Production only: dev needs inline scripts (React refresh) and injected <style> tags that this policy forbids.
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob:",
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

export default defineConfig({
  base: "/jellytip/",
  plugins: [react(), securityMeta()],
  server: {
    port: 5178,
    proxy: {
      "/jellyfin": { target: UPSTREAM, changeOrigin: true, secure: true },
      "/jellytip-api": { target: UPSTREAM, changeOrigin: true, secure: true },
    },
  },
  preview: { port: 5179 },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 900 },
});
