import { createRoot } from "react-dom/client";
import { App } from "./App";
import { installSpatialNav } from "./lib/spatial";
import "./styles.css";

installSpatialNav();
createRoot(document.getElementById("root")!).render(<App />);

// Installable PWA: app-shell service worker (production build only; dev serves modules the shell list doesn't know)
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => undefined);
  });
}
