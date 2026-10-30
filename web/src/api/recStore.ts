// The person's recordings, shared by the nav badge, the Home row and the page: one fetch in flight, a short freshness
// window, refreshed on demand (after recording, cancelling) and every minute while something is pending.
import { useEffect, useState } from "react";
import { recordings } from "./iptv";
import { afterStartup } from "../lib/startup";
import { hasPending, type Rec } from "../lib/recs";

const EVENT = "jellytip-recs";
const FRESH_MS = 20_000;
const PENDING_REFRESH_MS = 60_000;

let state: Rec[] | null = null; // null = not asked / no recordings service
let at = 0;
let inflight: Promise<void> | null = null;

export function refreshRecs(force = false): Promise<void> {
  if (inflight) return inflight;
  if (!force && state && Date.now() - at < FRESH_MS) return Promise.resolve();
  inflight = recordings()
    .then((a) => {
      if (a.status === 200 && a.data) {
        state = a.data.recordings as Rec[];
        at = Date.now();
        window.dispatchEvent(new Event(EVENT));
      }
    })
    .finally(() => (inflight = null));
  return inflight;
}

window.addEventListener("jellytip-session", () => {
  state = null;
  at = 0;
  window.dispatchEvent(new Event(EVENT));
});

/** Recordings (null until known). `soon`: wait for the first screen to start loading before asking. */
export function useRecs(soon = true): Rec[] | null {
  const [recs, setRecs] = useState(state);
  useEffect(() => {
    let alive = true;
    const on = () => setRecs(state);
    window.addEventListener(EVENT, on);
    void (soon ? afterStartup() : Promise.resolve()).then(() => (alive ? refreshRecs() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
  }, [soon]);
  // Something scheduled or recording changes by itself: look again every minute
  const pending = !!recs && hasPending(recs);
  useEffect(() => {
    if (!pending) return;
    const t = setInterval(() => void refreshRecs(true), PENDING_REFRESH_MS);
    return () => clearInterval(t);
  }, [pending]);
  return recs;
}
