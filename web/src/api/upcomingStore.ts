// Upcoming sport, shared by the Home row, the page and the nav: one fetch, kept a few minutes; a 404 (server without
// the route) marks it unavailable and everything stays hidden.
import { useEffect, useState } from "react";
import { iptvGet } from "./iptv";
import { afterStartup } from "../lib/startup";
import { parseUpcoming, type UpEvent } from "../lib/upcoming";

const EVENT = "jellytip-upcoming";
const FRESH_MS = 3 * 60_000;
let state: UpEvent[] | null = null;
let unavailable = false;
let at = 0;
let inflight: Promise<void> | null = null;

export function refreshUpcoming(force = false): Promise<void> {
  if (inflight) return inflight;
  if (unavailable || (!force && state && Date.now() - at < FRESH_MS)) return Promise.resolve();
  inflight = iptvGet<unknown>("v1/iptv/sport/upcoming?days=3&limit=60")
    .then((a) => {
      if (a.status === 404) unavailable = true;
      else if (a.status === 200) {
        state = parseUpcoming(a.data);
        at = Date.now();
      }
      window.dispatchEvent(new Event(EVENT));
    })
    .finally(() => (inflight = null));
  return inflight;
}

// Muting or unmuting a sport changes what the server lists: look again
window.addEventListener("jellytip-sport-muted", () => void refreshUpcoming(true));

window.addEventListener("jellytip-session", () => {
  state = null;
  unavailable = false;
  at = 0;
  window.dispatchEvent(new Event(EVENT));
});

/** Events (null = not known yet, or the server has no such route). */
export function useUpcoming(soon = true): UpEvent[] | null {
  const [ev, setEv] = useState(state);
  useEffect(() => {
    let alive = true;
    const on = () => setEv(state);
    window.addEventListener(EVENT, on);
    void (soon ? afterStartup() : Promise.resolve()).then(() => (alive ? refreshUpcoming() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
  }, [soon]);
  return ev;
}
