// The server speaks HTTP/1.1 (6 connections per host) at ~250 ms RTT: every request in the first seconds competes with
// the rows, tiles and art the screen is waiting for. Extras (prefs, Live TV check, on-now) start after this.
const AFTER_MS = 2500;
const ready = new Promise<void>((resolve) => {
  if (typeof window === "undefined") return resolve();
  const go = () => window.setTimeout(resolve, AFTER_MS);
  if (document.readyState === "complete") go();
  else window.addEventListener("load", go, { once: true });
});

/** Resolves a little after the page has loaded (immediately for later calls). */
export const afterStartup = () => ready;
