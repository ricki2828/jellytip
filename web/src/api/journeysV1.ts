// Journeys v1 calls (contract ~/ai/state/fleet/jellytip-journeys-v1-spec.md). Every route needs the person's own Jellyfin token,
// so they all go through companionCall (native HTTP in the phone app: the JSON routes send no CORS headers).
//
// Session memo, per person: the list and each opened journey are kept so Back paints the same page with no request and no
// re-rank (Ricki 21-Nov). They go stale after 10 min, when something was played ("jellytip-watched") and after this person's own
// start / remove / drop; a stale memo still paints at once and the refetch is merged with stable() so cards keep their places.
// Only a 404 is remembered (as "not there"); every other failure retries with backoff (5 s doubling to 5 min) while a screen is
// listening, paused in a hidden tab, and is never remembered.
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { companionCall } from "./companionCall";
import { COMPANION_BASE } from "./companion";
import { uid } from "./jellyfin";
import {
  activeSummary, apiId, askFromReply, getBody, loadOutcome, nowIndex, parseHome, parseJourney, parseSummary, retryMs, stableHome, statusAfter, withSkipped,
  type JvAsk, type JvHome, type JvJourney, type JvStep,
} from "../lib/journeysV1";

export type Load<T> = { kind: "ok"; value: T } | { kind: "missing" } | { kind: "failed"; status: number };

const TTL_MS = 10 * 60_000;
const base = () => COMPANION_BASE;
const me = () => uid();

// ---------- memo ----------

interface Memo<T> {
  uid: string;
  at: number;
  stale: boolean;
  value: T | null;
  /** A 404 for this person (kept for the session). */
  missing: boolean;
}

let homeMemo: Memo<JvHome> | null = null;
const journeyMemo = new Map<string, Memo<JvJourney>>();
const listeners = new Set<() => void>();
let version = 0;
const changed = () => {
  version++;
  for (const l of [...listeners]) l();
};
export const onJourneysChange = (cb: () => void) => {
  listeners.add(cb);
  return () => void listeners.delete(cb);
};

if (typeof window !== "undefined") {
  window.addEventListener("jellytip-session", () => {
    homeMemo = null;
    journeyMemo.clear();
    changed();
  });
  // Something was played or finished: progress may have moved. Keep what is there (Back paints it), refetch behind it.
  window.addEventListener("jellytip-watched", () => {
    if (homeMemo) homeMemo.stale = true;
    for (const m of journeyMemo.values()) m.stale = true;
  });
}

const fresh = (m: Memo<unknown> | null | undefined, u: string) => !!m && m.uid === u && !m.stale && Date.now() - m.at < TTL_MS;
const mine = <T,>(m: Memo<T> | null | undefined, u: string): Memo<T> | null => (m && m.uid === u ? m : null);

export const peekHome = (): Memo<JvHome> | null => mine(homeMemo, me());
export const peekJourney = (id: string): Memo<JvJourney> | null => mine(journeyMemo.get(id), me());
/** This person's memo of a journey, if it is fresh (not marked stale, younger than the window). */
export const freshJourney = (id: string): JvJourney | null => {
  const m = journeyMemo.get(id);
  return fresh(m, me()) ? m!.value : null;
};

// ---------- reads ----------

export async function fetchHome(): Promise<Load<JvHome>> {
  const u = me();
  const r = await companionCall("GET", "v1/journeys", undefined, { timeoutMs: 12_000 });
  if (u !== me()) return { kind: "failed", status: 0 }; // signed out / switched meanwhile
  const outcome = loadOutcome(r.status);
  if (outcome === "missing") {
    homeMemo = { uid: u, at: Date.now(), stale: false, value: null, missing: true };
    changed();
    return { kind: "missing" };
  }
  const h = outcome === "ok" ? parseHome(r.data, base()) : null;
  if (!h) return { kind: "failed", status: r.status === 200 ? 0 : r.status }; // an unreadable answer is a failure, not "missing"
  const old = mine(homeMemo, u)?.value ?? null;
  const merged = stableHome(old, h);
  homeMemo = { uid: u, at: Date.now(), stale: false, value: merged, missing: false };
  changed();
  return { kind: "ok", value: merged };
}

export async function fetchJourney(id: string, timeoutMs = 12_000): Promise<Load<JvJourney>> {
  const u = me();
  const r = await companionCall("GET", `v1/journeys/${apiId(id)}`, undefined, { timeoutMs });
  if (u !== me()) return { kind: "failed", status: 0 };
  const outcome = loadOutcome(r.status);
  if (outcome === "missing") {
    journeyMemo.set(id, { uid: u, at: Date.now(), stale: false, value: null, missing: true });
    changed();
    return { kind: "missing" };
  }
  const parsed = outcome === "ok" ? parseJourney(r.data, base()) : null;
  if (!parsed) return { kind: "failed", status: r.status === 200 ? 0 : r.status };
  // Guard by id (case aside): an answer naming another journey is never shown as this one, and it is not worth asking again
  // forever either: it reads as "not there"
  if (parsed.summary.id.toLowerCase() !== id.toLowerCase()) {
    journeyMemo.set(id, { uid: u, at: Date.now(), stale: false, value: null, missing: true });
    changed();
    return { kind: "missing" };
  }
  const j = { ...parsed, summary: { ...parsed.summary, id } };
  journeyMemo.set(id, { uid: u, at: Date.now(), stale: false, value: j, missing: false });
  changed();
  return { kind: "ok", value: j };
}

/** Load state for a screen: the memo at once, a fetch when there is none or it is stale, retries with backoff on failure. */
export interface LoadState<T> {
  value: T | null;
  missing: boolean;
  /** Failures in a row (0 = none); the screen says "Couldn't load" while > 0 and nothing is shown. */
  failures: number;
  retryNow: () => void;
}

function useBackoff<T>(key: string | null, peek: () => Memo<T> | null, fetcher: () => Promise<Load<T>>): LoadState<T> {
  // Re-read the memo whenever it changes (own actions, a refetch, a session switch)
  const v = useSyncExternalStore(onJourneysChange, () => version);
  const [failures, setFailures] = useState(0);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    let timer: number | undefined;
    let n = 0;
    setFailures(0);
    if (key == null) return;
    const m = peek();
    if (m && fresh(m, me())) return; // Back within the window: what is there is what was there
    const onVisible = () => {
      if (document.hidden) return;
      document.removeEventListener("visibilitychange", onVisible);
      if (alive) run();
    };
    const fire = () => {
      if (!alive) return;
      if (document.hidden) return document.addEventListener("visibilitychange", onVisible);
      run();
    };
    function run() {
      void fetcher().then((r) => {
        if (!alive) return;
        if (r.kind === "failed") {
          n++;
          setFailures(n);
          timer = window.setTimeout(fire, retryMs(n));
        } else setFailures(0);
      });
    }
    run();
    return () => {
      alive = false;
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, tick]);
  void v;
  const m = key == null ? null : peek();
  // A real stale mark while the screen is up (own start / remove / drop, a skip that didn't save): ask again. Back never
  // lands here with a stale mark of its own making: a fresh memo paints as it is.
  // Only a mark that arrives while mounted: on mount, a stale memo is already being fetched by the load effect above.
  const staleNow = !!m?.stale;
  const wasStale = useRef(staleNow);
  useEffect(() => {
    if (staleNow && !wasStale.current && key != null) setTick((t) => t + 1);
    wasStale.current = staleNow;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [staleNow]);
  return { value: m?.value ?? null, missing: !!m?.missing, failures, retryNow: () => setTick((t) => t + 1) };
}

export const useJourneysHome = (): LoadState<JvHome> => useBackoff(`home:${me()}`, peekHome, fetchHome);
/** One journey; null = nothing to load (no request). */
export const useJourney = (id: string | null): LoadState<JvJourney> =>
  useBackoff(id == null ? null : `j:${me()}:${id}`, () => (id == null ? null : peekJourney(id)), () => fetchJourney(id ?? ""));

// ---------- Home hero slide: the active journey ----------

/**
 * Home: the active journey of whoever is signed in, with its detail (tonight's step, art, what to play). Quiet: a failure
 * keeps what was there for this person; no retry loop (Home asks again next time it shows).
 */
export async function refreshActive(): Promise<void> {
  const u = me();
  if (!u) return;
  if (!fresh(homeMemo, u)) await fetchHome().catch(() => undefined);
  const a = activeSummary(mine(homeMemo, u)?.value ?? null);
  if (a && a.nowIndex != null && !fresh(journeyMemo.get(a.id), u)) await fetchJourney(a.id).catch(() => undefined);
}

export interface ActiveJourney {
  journey: JvJourney;
  now: number;
  step: JvStep;
}

/** The slide's journey: active, with a playable step tonight (recomputed from the steps). Null = no slide. */
export function activeJourney(): ActiveJourney | null {
  const h = peekHome();
  const a = activeSummary(h?.value ?? null);
  if (!a || a.status !== "active") return null;
  const j = peekJourney(a.id)?.value;
  if (!j || j.summary.status !== "active") return null;
  const now = nowIndex(j.steps);
  return now == null ? null : { journey: j, now, step: j.steps[now] };
}

export function useActiveJourney(): ActiveJourney | null {
  useSyncExternalStore(onJourneysChange, () => version);
  useEffect(() => {
    void refreshActive();
  }, []);
  return activeJourney();
}

// ---------- writes (fire-and-forget; the page shows the result at once) ----------

function patchStatus(id: string, status: string) {
  const u = me();
  const jm = mine(journeyMemo.get(id), u);
  if (jm?.value) jm.value = { ...jm.value, summary: { ...jm.value.summary, status } };
  const hm = mine(homeMemo, u);
  if (hm?.value?.journeys[id]) {
    const h = hm.value;
    hm.value = { ...h, journeys: { ...h.journeys, [id]: { ...h.journeys[id], status } }, active: status === "active" ? id : h.active === id ? null : h.active };
    hm.stale = true; // rows move (start -> yours): the list refetches behind what it shows
  }
  changed();
}

/** start | pause | drop. Applied at once; the server's answer (a summary) replaces it when it comes. Resolves with the status. */
export async function journeyAction(id: string, what: "start" | "pause" | "drop", current: string): Promise<number> {
  patchStatus(id, statusAfter(current, what));
  const r = await companionCall("POST", `v1/journeys/${apiId(id)}/${what}`, {});
  const s = r.status === 200 ? parseSummary(r.data, base()) : null;
  if (s && s.id.toLowerCase() === id.toLowerCase()) {
    patchStatus(id, s.status);
    markStale(id); // done / skips cleared by start: the steps refetch (now, if the page is up)
  } else {
    // Not saved (offline, 5xx, 409 "only from active"…): put the status back and ask the server what is true
    patchStatus(id, current);
    markStale(id);
  }
  return r.status;
}

/** The journey and the list no longer show what the server has: refetch (now where a screen listens, else on the next visit). */
function markStale(id: string) {
  const u = me();
  const jm = mine(journeyMemo.get(id), u);
  if (jm) jm.stale = true;
  const hm = mine(homeMemo, u);
  if (hm) hm.stale = true;
  changed();
}

/** play | skip of a step. A skip is shown at once (tonight's moves on). */
export async function journeyEvent(id: string, type: "play" | "skip", tmdbId: number | null): Promise<number> {
  if (!tmdbId) return 0;
  if (type === "skip") {
    const jm = mine(journeyMemo.get(id), me());
    const i = jm?.value?.steps.findIndex((s) => s.tmdbId === tmdbId) ?? -1;
    if (jm?.value && i >= 0) {
      jm.value = { ...jm.value, steps: withSkipped(jm.value.steps, i) };
      changed();
    }
  }
  const status = (await companionCall("POST", `v1/journeys/${apiId(id)}/event`, { type, tmdbId })).status;
  if (status !== 200) markStale(id); // a skip that didn't save must not stay painted as skipped
  return status;
}

/** Get it for one step (admin; the server only marks a step gettable for an admin). */
export async function getStep(id: string, steps: JvStep[], index: number): Promise<JvAsk> {
  const r = await companionCall("POST", `v1/journeys/${apiId(id)}/get`, getBody(steps, index), { timeoutMs: 25_000 });
  return askFromReply(r.status, r.data);
}
