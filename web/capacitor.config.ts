import type { CapacitorConfig } from "@capacitor/cli";

// Jellytip phone app (sideloaded APK): the bundled web build (npm run build:android) in a WebView, talking to the
// seedbox directly. Distinct from the TV app's id.
const config: CapacitorConfig = {
  appId: "com.jellytip.phone",
  appName: "Jellytip",
  webDir: "dist-android",
  backgroundColor: "#0a0a0a",
  android: {
    backgroundColor: "#0a0a0a",
    allowMixedContent: false,
  },
  plugins: {
    // Edge to edge with viewport-fit=cover: env(safe-area-inset-*) carries the real insets (styles.css pads for them)
    SystemBars: { insetsHandling: "css", initialViewportFitValueHint: "cover", style: "DARK" },
    // Native HTTP is used explicitly for the companion API (it sends no CORS headers); not patched over fetch/XHR,
    // so hls.js and media keep using the WebView's own networking.
    CapacitorHttp: { enabled: false },
  },
};

export default config;
