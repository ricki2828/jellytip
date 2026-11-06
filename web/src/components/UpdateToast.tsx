// Unobtrusive "A new version is available" toast. Polls version.json (no-store) while the app is visible; never shows
// over the player (deferred until the person leaves it) and never reloads by itself.
import { useEffect, useRef, useState } from "react";
import { BUILD } from "../lib/buildInfo";
import { parseHash } from "../lib/routeParse";
import { createUpdateChecker, decideToast, parseVersionJson, type RemoteVersion } from "../lib/updateCheck";
import "./UpdateToast.css";

const routeName = () => parseHash(location.hash).name;

async function fetchRemote(): Promise<unknown> {
  const res = await fetch(`${import.meta.env.BASE_URL}version.json`, { cache: "no-store" });
  if (!res.ok) throw new Error(`version.json ${res.status}`);
  return res.json().catch(() => null);
}

export function UpdateToast() {
  const [latest, setLatest] = useState<RemoteVersion | null>(null);
  const [route, setRoute] = useState(routeName);
  const [dismissed, setDismissed] = useState<string | null>(null);
  const seen = useRef<string | null>(null);

  useEffect(() => {
    const on = () => setRoute(routeName());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);

  useEffect(() => {
    const checker = createUpdateChecker({
      check: async () => {
        const v = parseVersionJson(await fetchRemote());
        if (!v) return; // unusable body: treat as "nothing new", not an error to back off on
        setLatest(v);
        if (v.sha !== BUILD.sha && seen.current !== v.sha) {
          seen.current = v.sha;
          // Let the service worker fetch the new shell early; it skipWaiting()s, so the next navigation is already fresh
          void navigator.serviceWorker?.getRegistration().then((r) => r?.update()).catch(() => undefined);
        }
      },
      now: () => Date.now(),
      isVisible: () => document.visibilityState === "visible",
      setTimer: (fn, ms) => window.setTimeout(fn, ms),
      clearTimer: (h) => window.clearTimeout(h as number),
    });
    const onVis = () => checker.onVisibility();
    document.addEventListener("visibilitychange", onVis);
    checker.start();
    return () => {
      document.removeEventListener("visibilitychange", onVis);
      checker.stop();
    };
  }, []);

  if (decideToast({ running: BUILD.sha, latest, routeName: route, dismissedSha: dismissed }) !== "show") return null;
  return (
    <div className="update-toast" role="status">
      <span>A new version is available</span>
      <button data-nav onClick={() => location.reload()}>
        Reload
      </button>
      <button data-nav className="x" aria-label="Dismiss" onClick={() => setDismissed(latest?.sha ?? null)}>
        ×
      </button>
    </div>
  );
}
