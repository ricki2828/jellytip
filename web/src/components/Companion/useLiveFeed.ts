// The player's own light feed of the live payload, so the scorebug and the panel's auto-open policy work while the panel is closed
// (the drawer polls only while open). Polls every 15 s (paused while the tab is hidden); a 1 Hz tick re-asks the reveal clock which
// snapshot may be shown and publishes a new view ONLY when something visible changed, so the player root never re-renders per tick.
import { useEffect, useMemo, useRef, useState } from "react";
import { liveStats, matchDetailCached } from "../../api/matchInfo";
import { applyReveal, mayShow, type LiveStats, type Moment } from "../../lib/liveStats";
import { gameStateOf, type GameStateKind, type Sport } from "../../lib/gameState";
import { clockRunning, newestRevealedIndex } from "../../lib/companion";
import { announceFlips } from "../FlipAlerts";
import type { Fixture } from "../../lib/matchInfo";

const POLL_MS = 15_000;
/** Safety cap only: snapshots are normally dropped as soon as a newer one has been revealed (so retention follows delay + lag). */
const KEEP = 80;
const MOMENTS_KEEP = 300;

export interface FeedView {
  score: { home: number; away: number } | null;
  clock: string | null;
  period: string | null;
  kind: GameStateKind | null;
  home: string;
  away: string;
  /** Epoch ms of the last revealed change worth a pill (score, period, moment); null before the first. */
  changedAtMs: number | null;
  /** Bumps on every NEW revealed key moment (not the first batch). */
  momentN: number;
  sport: Sport | null;
  /** When the snapshot behind clock/score became revealed (epoch ms): the clock is run on from here. */
  shownAtMs: number | null;
  clockRunning: boolean;
}

/** What the player's feed has fetched, for the embedded drawer to show instead of polling again. */
export interface FeedSnapshot {
  raw: LiveStats | null;
  /** The newest snapshot the spoiler delay has released, with every released moment: what the panel may show. Null until one is. */
  shown: LiveStats | null;
  /** Which raw snapshot `shown` was built from (so the feed can tell a new one). */
  shownFrom?: LiveStats | null;
  fixture: Fixture | null;
  state: "loading" | "ok" | "missing" | "error";
}
export interface LiveSource {
  get: () => FeedSnapshot;
  subscribe: (cb: () => void) => () => void;
}

const EMPTY: FeedView = { score: null, clock: null, period: null, kind: null, home: "HOME", away: "AWAY", changedAtMs: null, momentN: 0, sport: null, shownAtMs: null, clockRunning: false };
const same = (a: FeedView, b: FeedView) =>
  a.score?.home === b.score?.home && a.score?.away === b.score?.away && a.clock === b.clock && a.period === b.period && a.kind === b.kind && a.home === b.home && a.away === b.away && a.changedAtMs === b.changedAtMs && a.momentN === b.momentN && a.shownAtMs === b.shownAtMs && a.clockRunning === b.clockRunning;

export function useLiveFeed(o: { matchId: string | null | undefined; enabled: boolean; liveNow: boolean; spoilers: boolean; reveal: (atSec?: number) => boolean; noteMoment?: (atSec: number) => void }): { view: FeedView; source: LiveSource } {
  const { matchId, enabled, liveNow, spoilers } = o;
  const [view, setView] = useState<FeedView>(EMPTY);
  const store = useMemo(() => {
    let snap: FeedSnapshot = { raw: null, shown: null, fixture: null, state: "loading" };
    const subs = new Set<() => void>();
    const source: LiveSource = { get: () => snap, subscribe: (cb) => (subs.add(cb), () => void subs.delete(cb)) };
    const set = (p: Partial<FeedSnapshot>) => {
      snap = { ...snap, ...p };
      subs.forEach((f) => f());
    };
    return { source, set };
  }, []);
  const flashedFor = useRef<string | null>(null);
  const revealRef = useRef(o.reveal);
  revealRef.current = o.reveal;
  const noteRef = useRef(o.noteMoment);
  noteRef.current = o.noteMoment;
  const allowed = enabled && !!matchId && mayShow(liveNow, spoilers);

  useEffect(() => {
    setView(EMPTY);
    store.set({ raw: null, shown: null, fixture: null, state: "loading" });
    if (!allowed || !matchId) return;
    let alive = true;
    let timer: number | undefined;
    let waiting = false;
    let snaps: LiveStats[] = [];
    let fixture: Fixture | null = null;
    const momMap = new Map<string, Moment>();
    const seen = new Set<string>();
    let baseline = true;
    let momentN = 0;
    let changedAt: number | null = null;
    let prevKey = "";
    let cur = EMPTY;
    let shownSnap: LiveStats | null = null;
    let shownCount = -1;
    let shownAt: number | null = null;
    let prevClock: { clock: string | null; period: string | null; atMs: number } | null = null;
    let lastClock: { clock: string | null; period: string | null; atMs: number } | null = null;

    const derive = () => {
      const reveal = revealRef.current;
      if (!reveal()) return; // replay / reveal off: nothing new is shown (what was shown stays until the player leaves replay)
      // key moments: each released on its own time, whether or not a snapshot has been released yet
      const released: Moment[] = [];
      let fresh = false;
      for (const [k, m] of momMap) {
        if (!reveal(m.at)) continue;
        released.push(m);
        if (!seen.has(k)) {
          seen.add(k);
          if (!baseline) fresh = true; // moments already released when the player opened are the baseline, not news
        }
      }
      released.sort((a, b) => a.at - b.at);
      if (fresh) momentN++;
      const idx = newestRevealedIndex(snaps, reveal);
      if (idx < 0) return;
      const snap = snaps[idx];
      snaps = snaps.slice(idx); // older snapshots are superseded: retention follows how far the delay holds things back
      const d0 = applyReveal(snap, reveal);
      const d: LiveStats = { ...d0, moments: released };
      if (snap !== shownSnap) {
        // this snapshot has just become revealed: the clock runs on from here, and a flip in it may toast
        shownSnap = snap;
        shownAt = Date.now();
        prevClock = lastClock;
        lastClock = { clock: d.clock, period: d.period, atMs: snap.updatedAt != null ? snap.updatedAt * 1000 : shownAt };
        if (spoilers && matchId) announceFlips(matchId, d);
      }
      const count = released.length * 1000 + (d.chat?.comments.length ?? 0);
      if (snap !== store.source.get().shownFrom || count !== shownCount) {
        shownCount = count;
        store.set({ shown: d, shownFrom: snap });
      }
      const names = { home: fixture?.home.short ?? fixture?.home.name ?? "HOME", away: fixture?.away.short ?? fixture?.away.name ?? "AWAY" };
      const key = `${d.score?.home}-${d.score?.away}|${d.period}`;
      if (key !== prevKey) {
        if (prevKey && !baseline) changedAt = Date.now();
        prevKey = key;
      }
      if (fresh) changedAt = Date.now();
      const gs = d.games.length > 0 ? null : gameStateOf(d, fixture, spoilers);
      const kind = gs?.kind ?? null;
      const running = !!lastClock && clockRunning(kind, prevClock, lastClock);
      const next: FeedView = { score: d.score, clock: d.clock, period: d.period, kind, home: names.home.toUpperCase(), away: names.away.toUpperCase(), changedAtMs: changedAt, momentN, sport: gs?.sport ?? null, shownAtMs: shownAt, clockRunning: running };
      if (!same(cur, next)) {
        cur = next;
        setView(next);
      }
    };

    const load = async () => {
      const a = await liveStats(matchId);
      if (!alive) return;
      if (a === "missing") store.set({ state: "missing" });
      else if (!a) {
        if (store.source.get().state !== "ok") store.set({ state: "error" }); // keep what we have when a refresh fails
      } else {
        store.set({ raw: a, state: "ok" });
      }
      if (a && a !== "missing") {
        snaps.push(a);
        if (snaps.length > KEEP) snaps.shift();
        for (const m of a.moments) {
          const k = `${m.at}|${m.kind}|${m.team}`;
          if (momMap.has(k)) continue;
          momMap.set(k, m);
          noteRef.current?.(m.at); // the only thing "that just happened" calibrates against
        }
        if (momMap.size > MOMENTS_KEEP) for (const k of [...momMap.keys()].slice(0, momMap.size - MOMENTS_KEEP)) momMap.delete(k);
        derive();
        // the first batch only sets the baseline: moments already on it are not "new"
        if (baseline) {
          baseline = false;
          // show the pill once when the player opens on a live game (a feed reset on the same match does not flash it again)
          if (flashedFor.current !== matchId) {
            flashedFor.current = matchId;
            changedAt = changedAt ?? Date.now();
          }
          derive();
        }
      }
      timer = window.setTimeout(tick, POLL_MS);
    };
    const tick = () => {
      if (document.hidden) {
        waiting = true;
        return;
      }
      void load();
    };
    const onVisible = () => {
      if (!document.hidden && waiting && alive) {
        waiting = false;
        void load();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    void matchDetailCached(matchId).then((m) => {
      if (alive && m && m !== "missing") {
        fixture = m.fixture;
        store.set({ fixture: m.fixture });
      }
    });
    void load();
    const second = window.setInterval(() => {
      if (!document.hidden) derive();
    }, 1000);
    return () => {
      alive = false;
      document.removeEventListener("visibilitychange", onVisible);
      window.clearTimeout(timer);
      window.clearInterval(second);
    };
  }, [allowed, matchId, spoilers, store]);

  return { view, source: store.source };
}
