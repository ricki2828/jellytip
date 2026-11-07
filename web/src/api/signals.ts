// Taste signals to the companion (POST /v1/me/signals). Batched: every 30 s, when the page is hidden, 50 a call. A server
// without the route (404) or a signed-out person just means nothing is sent; nothing here ever shows an error.
import { useCallback, useEffect, useRef } from "react";
import { getSession } from "./jellyfin";
import { companionCall } from "./companionCall";
import { COMPANION_BASE } from "./companion";
import { isNative } from "../lib/native";
import { BATCH_MAX, PlayTracker, createQueue, detailSignal, trailerSignal, watchSignal, type Signal, type WatchMeta } from "../lib/signals";

const FLUSH_MS = 30_000;
const queue = createQueue();
let owner = "";
let timer: number | undefined;
let off = false;
let sending = false;

/** One POST; the sent events leave the queue on any answer but "unreachable". */
async function post(batch: Signal[], keepalive: boolean): Promise<number> {
  const body = { events: batch.map(({ type, at, kind, tmdbId, itemId, iptvId, ms }) => ({ type, at, kind, tmdbId, itemId, iptvId, ms })) };
  const token = getSession()?.token;
  if (keepalive && !isNative && token) {
    try {
      const r = await fetch(`${COMPANION_BASE}/v1/me/signals`, { method: "POST", keepalive: true, headers: { Authorization: `MediaBrowser Token="${token}"`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
      return r.status;
    } catch {
      return 0;
    }
  }
  return (await companionCall("POST", "v1/me/signals", body)).status;
}

/** Send what is queued (at most BATCH_MAX a call). `leaving`: the page is going away, use a request that survives it. */
export async function flushSignals(leaving = false): Promise<void> {
  if (sending && !leaving) return;
  if (off) return queue.clear();
  sending = true;
  try {
    for (let n = 0; n < 4; n++) {
      const batch = queue.take(BATCH_MAX);
      if (!batch.length) return;
      const status = await post(batch, leaving);
      if (status === 0) return; // unreachable: keep them for the next flush
      queue.done(batch);
      if (status === 404 || status === 501 || status === 403) {
        off = true;
        queue.clear();
        return;
      }
      if (status === 401) return queue.clear();
    }
  } finally {
    sending = false;
  }
}

function arm() {
  if (timer != null) return;
  timer = window.setInterval(() => void flushSignals(), FLUSH_MS);
}

/** Queue one event for the signed-in person. */
export function emitSignal(s: Signal | null): void {
  const u = getSession()?.userId ?? "";
  if (!s || !u || off) return;
  if (owner !== u) {
    queue.clear();
    owner = u;
  }
  if (queue.add(s)) arm();
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") void flushSignals(true);
});
window.addEventListener("pagehide", () => void flushSignals(true));
window.addEventListener("jellytip-session", () => {
  queue.clear();
  owner = "";
  off = false;
});

// ---------- hooks ----------

type Target = Pick<Signal, "kind" | "tmdbId" | "itemId" | "iptvId">;

/** Details screen dwell: one 'detail' when the person leaves (or switches title) after 8 s or more with the tab visible. */
export function useDetailDwell(target: Target | null): void {
  const key = target ? `${target.kind}|${target.tmdbId ?? ""}|${target.itemId ?? ""}|${target.iptvId ?? ""}` : "";
  useEffect(() => {
    if (!target) return;
    let visibleMs = 0;
    let since = document.hidden ? 0 : Date.now();
    const vis = () => {
      if (document.hidden) {
        if (since) visibleMs += Date.now() - since;
        since = 0;
      } else since = Date.now();
    };
    document.addEventListener("visibilitychange", vis);
    return () => {
      document.removeEventListener("visibilitychange", vis);
      if (since) visibleMs += Date.now() - since;
      emitSignal(detailSignal(target, visibleMs, Date.now()));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
}

/** A trailer the person chose to open: 'trailer' when it closes after 5 s or more of it was watched. Returns the video's onTimeUpdate. */
export function useTrailerWatch(open: boolean, target: Target | null): (e: { currentTarget: HTMLVideoElement }) => void {
  const max = useRef(0);
  const tg = useRef(target);
  tg.current = target;
  useEffect(() => {
    if (!open) return;
    max.current = 0;
    return () => {
      if (tg.current) emitSignal(trailerSignal(tg.current, max.current, Date.now()));
      max.current = 0;
    };
  }, [open]);
  return useCallback((e) => {
    const t = e.currentTarget.currentTime;
    if (t > max.current && t - max.current < 3) max.current = t; // a scrub forward is not watching
  }, []);
}

export interface PlayWatch {
  /** From the video's timeupdate (cheap: one subtraction). */
  tick: (t: number, maxStep?: number) => void;
  seeked: () => void;
  /** Close the watch: 'ended' true when the media finished. Safe to call twice. */
  end: (ended: boolean) => void;
}

/** Play start/finish/skip for the title being watched. `meta` null (unknown yet, or a context that sends nothing) records nothing. */
export function usePlayWatch(meta: WatchMeta | null, runtimeS: () => number, id: string): PlayWatch {
  const tracker = useRef(new PlayTracker());
  const m = useRef(meta);
  const rt = useRef(runtimeS);
  m.current = meta;
  rt.current = runtimeS;
  const end = useCallback((ended: boolean) => {
    const t = tracker.current;
    const played = t.playedS;
    t.reset();
    if (m.current) emitSignal(watchSignal(m.current, played, rt.current(), ended, Date.now()));
  }, []);
  // a new title (or leaving the screen) closes the previous watch
  useEffect(() => {
    tracker.current.reset();
    return () => end(false);
  }, [id, end]);
  return {
    tick: useCallback((t: number, maxStep?: number) => tracker.current.tick(t, maxStep), []),
    seeked: useCallback(() => tracker.current.seeked(), []),
    end,
  };
}
