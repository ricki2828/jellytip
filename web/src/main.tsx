import { createRoot } from "react-dom/client";
import { App } from "./App";
import { installSpatialNav } from "./lib/spatial";
import { isNative } from "./lib/native";
import { onRootBack, systemBack } from "./lib/router";
import "./styles.css";

installSpatialNav();

// Android Back: sheet / modal / unmuted video first, then the previous in-app screen; on Home with nothing behind it
// the app goes to the background (never exits: Capacitor's default with no listener closes the activity)
if (isNative) {
  import("@capacitor/app").then(({ App: CapApp }) => {
    onRootBack(() => void CapApp.minimizeApp());
    void CapApp.addListener("backButton", () => systemBack());
  });
}
createRoot(document.getElementById("root")!).render(<App />);

// Installable PWA: app-shell service worker (production build only; dev serves modules the shell list doesn't know)
if (import.meta.env.PROD && !isNative && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { scope: import.meta.env.BASE_URL }).catch(() => undefined);
  });
}
