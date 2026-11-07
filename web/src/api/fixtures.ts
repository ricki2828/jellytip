// Fixture-first sport list (GET v1/iptv/sport/fixtures; contract in lib/gameCentre.ts). One fetch shared by the screens
// that use it, kept 30 s. A 404 (server without the route) or a failed first answer marks it unavailable for the session
// so Game Centre falls back to the legacy upcoming path; "degraded" answers are shown as they are.
import { useEffect, useState } from "react";
import { iptvGet } from "./iptv";
import { parseFixtureList, parseMore, type FxItem, type FxMore } from "../lib/gameCentre";

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
let at = 0;
let inflight: Promise<void> | null = null;
/** Bumped on a session switch: an answer fetched for the old person is dropped. */
let generation = 0;

export function refreshFixtures(force = false): Promise<void> {
  if (inflight) return inflight;
  if (unavailable || (!force && state && Date.now() - at < FRESH_MS)) return Promise.resolve();
  const now = Math.floor(Date.now() / 1000);
  const gen = generation;
  inflight = Promise.all([iptvGet<unknown>(url(now, false)), expanded.size ? iptvGet<unknown>(url(now, true)) : Promise.resolve(null)])
    .then(([a, b]) => {
      if (gen !== generation) return;
      if (a.status === 200) {
        base = { ...parseFixtureList(a.data), more: parseMore(a.data) };
        if (b && b.status === 200) full = parseFixtureList(b.data).items;
        merge();
        at = Date.now();
      } else if (a.status === 404 || !state) unavailable = true;
      window.dispatchEvent(new Event(EVENT));
    })
    .finally(() => {
      if (gen === generation) inflight = null;
    });
  return inflight;
}

window.addEventListener("jellytip-session", () => {
  generation++;
  inflight = null;
  state = null;
  base = null;
  full = [];
  expanded.clear();
  unavailable = false;
  at = 0;
  window.dispatchEvent(new Event(EVENT));
  void refreshFixtures(true);
});

/** "Show all <league> games": that league comes from a cap-off request from now on (this session). */
export function showAllLeague(key: string): Promise<void> {
  expanded.add(key);
  return refreshFixtures(true);
}

/** Fixtures: `data` null while loading, `unavailable` when the route is missing or failed (use the legacy path). */
export function useFixtures(enabled = true): { data: FixtureState | null; unavailable: boolean } {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    const on = () => tick((x) => x + 1);
    window.addEventListener(EVENT, on);
    void refreshFixtures();
    return () => window.removeEventListener(EVENT, on);
  }, [enabled]);
  // No usable data at all (degraded and empty): the legacy feed is the better screen
  return { data: state, unavailable: unavailable || (!!state && state.degraded && state.items.length === 0) };
}
