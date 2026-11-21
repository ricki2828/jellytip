// Sport interaction events to the companion (POST /v1/me/sport/events). Fire-and-forget like taste signals (api/signals):
// batched, every 30 s and when the page is hidden or left, 50 a call; a server without the route (404) turns it off for the
// session; nothing here ever shows an error or blocks a press.
import { useEffect, useRef, type RefObject } from "react";
import { getSession } from "./jellyfin";
import { companionCall } from "./companionCall";
import { COMPANION_BASE } from "./companion";
import { isNative } from "../lib/native";
import { SHOWN_AFTER_MS, SPORT_BATCH_MAX, createSportQueue, wireEvent, type SportEvent, type SportEventType, type SportSurface } from "../lib/sportEvents";

const FLUSH_MS = 30_000;
const PATH = "v1/me/sport/events";
const queue = createSportQueue();
let owner = "";
let timer: number | undefined;
let off = false;
let sending = false;
let leftAt = 0;

async function post(batch: SportEvent[], keepalive: boolean): Promise<number> {
  const body = { events: batch.map(wireEvent) };
  const token = getSession()?.token;
  if (keepalive && !isNative && token) {
    try {
      const r = await fetch(`${COMPANION_BASE}/${PATH}`, { method: "POST", keepalive: true, headers: { Authorization: `MediaBrowser Token="${token}"`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
      return r.status;
    } catch {
      return 0;
    }
  }
  return (await companionCall("POST", PATH, body)).status;
}

/** Send what is queued. `leaving`: the page is going away, use a request that survives it. */
export async function flushSportEvents(leaving = false): Promise<void> {
  if (sending && !leaving) return;
  if (leaving) {
    // visibilitychange(hidden) and pagehide both fire on close: one leaving flush, not the same batch twice
    if (Date.now() - leftAt < 1000) return;
    leftAt = Date.now();
  }
  if (off) return queue.clear();
  sending = true;
  try {
    for (let n = 0; n < 4; n++) {
      const batch = queue.take(SPORT_BATCH_MAX);
      if (!batch.length) return;
      const status = await post(batch, leaving);
      if (status === 0) return; // unreachable: keep them for the next flush
      queue.done(batch);
      if (status === 404 || status === 501 || status === 403) {
        off = true; // no route on this server: stop for the session
        queue.clear();
        return;
      }
      if (status === 401) return queue.clear();
    }
  } finally {
    sending = false;
  }
}

/** Queue one event for the signed-in person (`at` is now when not given). */
export function logSport(e: Omit<SportEvent, "at"> & { at?: number }): void {
  const u = getSession()?.userId ?? "";
  if (!u || off) return;
  if (owner !== u) {
    queue.clear();
    owner = u;
  }
  if (queue.add({ ...e, at: e.at ?? Math.floor(Date.now() / 1000) }) && timer == null) timer = window.setInterval(() => void flushSportEvents(), FLUSH_MS);
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void flushSportEvents(true);
  });
  window.addEventListener("pagehide", () => void flushSportEvents(true));
  window.addEventListener("jellytip-session", () => {
    queue.clear();
    owner = "";
    off = false;
  });
}

/** Where a fixture sits, for every event about it. */
export interface SportPlace {
  fid: string;
  surface: SportSurface;
  section?: string;
  pos?: number;
  listId?: string | null;
}

/** An event about the fixture at [p] (listId null → omitted). */
export function logSportAt(type: SportEventType, p: SportPlace): void {
  logSport({ type, fid: p.fid, surface: p.surface, section: p.section, pos: p.pos, listId: p.listId ?? undefined });
}

/**
 * 'shown' once the element has been at least half on screen for a second, with the tab visible. Counted once per list load
 * (listId) by the queue; [p] null logs nothing.
 */
export function useSportShown(el: RefObject<HTMLElement | null>, p: SportPlace | null): void {
  const place = useRef(p);
  place.current = p;
  const key = p ? `${p.listId ?? "-"}|${p.surface}|${p.fid}|${p.section ?? ""}|${p.pos ?? ""}` : "";
  useEffect(() => {
    const node = el.current;
    if (!key || !node || typeof IntersectionObserver === "undefined") return;
    let t: number | undefined;
    let inView = false;
    const stop = () => {
      if (t != null) window.clearTimeout(t);
      t = undefined;
    };
    const start = () => {
      if (t != null || !inView || document.hidden) return;
      t = window.setTimeout(() => {
        t = undefined;
        if (inView && !document.hidden && place.current) logSportAt("shown", place.current);
      }, SHOWN_AFTER_MS);
    };
    const io = new IntersectionObserver(
      (es) => {
        inView = es.some((e) => e.isIntersecting && e.intersectionRatio >= 0.5);
        if (inView) start();
        else stop();
      },
      { threshold: [0, 0.5, 1] },
    );
    const vis = () => (document.hidden ? stop() : start());
    io.observe(node);
    document.addEventListener("visibilitychange", vis);
    return () => {
      stop();
      io.disconnect();
      document.removeEventListener("visibilitychange", vis);
    };
  }, [key, el]);
}
