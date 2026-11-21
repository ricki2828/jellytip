// Fixture-first sport list (GET v1/iptv/sport/fixtures; contract in lib/gameCentre.ts). One fetch shared by the screens
// that use it, kept 30 s. Only a 404 (server without the route) marks it unavailable for the session, so Game Centre falls
// back to the legacy upcoming path. A network error or any other status is a load failure: retried with backoff (5 s doubling
// to 5 min), never remembered (fixturesOutcome). "Degraded" answers are shown as they are.
import { useEffect, useState } from "react";
import { iptvGet } from "./iptv";
import { fixturesOutcome, fixturesRetryMs, parseFixtureList, parseMore, type FxItem, type FxMore } from "../lib/gameCentre";

const EVENT = "jellytip-fixtures";
const FRESH_MS = 25_000; // polled every 30 s, so the list is never older than ~30 s
const PAST_S = 2 * 86400;
const AHEAD_S = 4 * 86400;

export interface FixtureState {
  items: FxItem[];
  degraded: boolean;
  more: FxMore[];
  expanded: ReadonlySet<string>;
}
const tz = (): string => {
  try {
    return encodeURIComponent(Intl.DateTimeFormat().resolvedOptions().timeZone || "");
  } catch {
    return "";
  }
};
const url = (now: number, cap0: boolean) => `v1/iptv/sport/fixtures?from=${now - PAST_S}&to=${now + AHEAD_S}&limit=120&tz=${tz()}${cap0 ? "&cap=0" : ""}`;
/** Leagues the person asked "Show all" for, and the cap-off answer their games come from. */
const expanded = new Set<string>();
let full: FxItem[] = [];
let base: { items: FxItem[]; degraded: boolean; more: FxMore[] } | null = null;
const merge = () => {
  if (!base) return;
  const key = (f: FxItem) => f.leagueKey ?? f.league ?? "";
  const items = expanded.size ? [...base.items.filter((f) => !expanded.has(key(f))), ...full.filter((f) => expanded.has(key(f)))] : base.items;
  state = { items, degraded: base.degraded, more: base.more, expanded };
};
let state: FixtureState | null = null;
let unavailable = false;
/** Failed answers in a row (reset by a good one); while > 0 a retry is scheduled. */
let failures = 0;
let retryTimer: number | undefined;
/** Mounted useFixtures(enabled) hooks: retries run only while someone is listening (none = the next mount fetches). */
let subs = 0;
let at = 0;
let inflight: Promise<void> | null = null;
/** Bumped on a session switch: an answer fetched for the old person is dropped. */
let generation = 0;

export function refreshFixtures(force = false): Promise<void> {
  if (inflight) return inflight;
  if (unavailable || (!force && state && Date.now() - at < FRESH_MS)) return Promise.resolve();
  // Failing: the backoff decides when to ask again, not each screen's own poll ("Try now" and the retry pass force)
  if (!force && retryTimer !== undefined) return Promise.resolve();
  const now = Math.floor(Date.now() / 1000);
  const gen = generation;
  // thrown = a network fault (status 0): retried like any failure; each request on its own, so a failed "Show all" call
  // never throws away a good main answer
  const get = (u: string) => iptvGet<unknown>(u).catch(() => ({ status: 0, data: null as unknown }));
  inflight = Promise.all([get(url(now, false)), expanded.size ? get(url(now, true)) : Promise.resolve(null)])
    .then(([a, b]) => {
      if (gen !== generation) return;
      const outcome = fixturesOutcome(a.status);
      if (outcome === "ok") {
        try {
          base = { ...parseFixtureList(a.data), more: parseMore(a.data) };
          if (b && b.status === 200) full = parseFixtureList(b.data).items;
          merge();
          at = Date.now();
          failures = 0;
          window.clearTimeout(retryTimer);
          retryTimer = undefined;
        } catch {
          scheduleRetry(gen); // an unreadable answer is a failure too, not "missing"
        }
      } else if (outcome === "missing") unavailable = true;
      else scheduleRetry(gen); // what we had (if anything) stays on screen meanwhile
      window.dispatchEvent(new Event(EVENT));
    })
    .finally(() => {
      if (gen === generation) inflight = null;
    });
  return inflight;
}

/** One pending retry at a time, doubling from 5 s to 5 min while answers keep failing; dropped by a session switch. */
function scheduleRetry(gen: number) {
  failures++;
  if (retryTimer !== undefined) return;
  retryTimer = window.setTimeout(function fire() {
    retryTimer = undefined;
    if (gen !== generation || subs === 0) return; // nobody listening (no sport screen, logged out): the next mount fetches
    if (document.hidden) {
      // a hidden tab waits until it is looked at again
      const back = () => {
        if (document.hidden) return;
        document.removeEventListener("visibilitychange", back);
        if (gen === generation && subs > 0) void refreshFixtures(true);
      };
      document.addEventListener("visibilitychange", back);
      return;
    }
    void refreshFixtures(true);
  }, fixturesRetryMs(failures));
}

window.addEventListener("jellytip-session", () => {
  generation++;
  failures = 0;
  window.clearTimeout(retryTimer);
  retryTimer = undefined;
  inflight = null;
  state = null;
  base = null;
  full = [];
  expanded.clear();
  unavailable = false;
  at = 0;
  window.dispatchEvent(new Event(EVENT));
  if (subs > 0) void refreshFixtures(true); // no listener: the first mount fetches for the new person
});

/** "Show all <league> games": that league comes from a cap-off request from now on (this session). */
export function showAllLeague(key: string): Promise<void> {
  expanded.add(key);
  return refreshFixtures(true);
}

/** The current list (null before the first answer), for code that has just awaited refreshFixtures. */
export const fixturesNow = (): FixtureState | null => state;

/** Fixtures: `data` null while loading; `unavailable` when the route is missing (404: use the legacy path); `failing` while
 *  the last answers failed and a retry is on its way (data, if any, is the last good list). */
export function useFixtures(enabled = true): { data: FixtureState | null; unavailable: boolean; failing: boolean } {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    const on = () => tick((x) => x + 1);
    window.addEventListener(EVENT, on);
    subs++;
    void refreshFixtures();
    return () => {
      subs--;
      window.removeEventListener(EVENT, on);
    };
  }, [enabled]);
  // No usable data at all (degraded and empty): the legacy feed is the better screen
  return { data: state, unavailable: unavailable || (!!state && state.degraded && state.items.length === 0), failing: failures > 0 };
}
