import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Production is served from https://ricki28.palawan.usbx.me/jellytip/, same origin as /jellyfin and /jellytip-api.
// In dev, those two paths are proxied to the same host so the app never talks cross-origin.
const UPSTREAM = "https://ricki28.palawan.usbx.me";

export default defineConfig({
  base: "/jellytip/",
  plugins: [react()],
  server: {
    port: 5178,
    proxy: {
      "/jellyfin": { target: UPSTREAM, changeOrigin: true, secure: true },
      "/jellytip-api": { target: UPSTREAM, changeOrigin: true, secure: true },
    },
  },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 900 },
});
