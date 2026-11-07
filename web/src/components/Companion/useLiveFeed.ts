// The player's own light feed of the live payload, so the scorebug and the panel's auto-open policy work while the panel is closed
// (the drawer polls only while open). Polls every 15 s (paused while the tab is hidden); a 1 Hz tick re-asks the reveal clock which
// snapshot may be shown and publishes a new view ONLY when something visible changed, so the player root never re-renders per tick.
import { useEffect, useRef, useState } from "react";
import { liveStats, matchDetailCached } from "../../api/matchInfo";
import { applyReveal, mayShow, type LiveStats, type Moment } from "../../lib/liveStats";
import { gameStateOf, type GameStateKind } from "../../lib/gameState";
import type { Fixture } from "../../lib/matchInfo";

const POLL_MS = 15_000;
const KEEP = 8;

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
}

const EMPTY: FeedView = { score: null, clock: null, period: null, kind: null, home: "HOME", away: "AWAY", changedAtMs: null, momentN: 0 };
const same = (a: FeedView, b: FeedView) =>
  a.score?.home === b.score?.home && a.score?.away === b.score?.away && a.clock === b.clock && a.period === b.period && a.kind === b.kind && a.home === b.home && a.away === b.away && a.changedAtMs === b.changedAtMs && a.momentN === b.momentN;

export function useLiveFeed(o: { matchId: string | null | undefined; enabled: boolean; liveNow: boolean; spoilers: boolean; reveal: (atSec?: number) => boolean }): FeedView {
  const { matchId, enabled, liveNow, spoilers } = o;
  const [view, setView] = useState<FeedView>(EMPTY);
  const revealRef = useRef(o.reveal);
  revealRef.current = o.reveal;
  const allowed = enabled && !!matchId && mayShow(liveNow, spoilers);

  useEffect(() => {
    setView(EMPTY);
    if (!allowed || !matchId) return;
    let alive = true;
    let timer: number | undefined;
    let waiting = false;
    const snaps: LiveStats[] = [];
    let fixture: Fixture | null = null;
    const seen = new Set<string>();
    let baseline = true;
    let momentN = 0;
    let changedAt: number | null = null;
    let prevKey = "";
    let cur = EMPTY;

    const derive = () => {
      const reveal = revealRef.current;
      if (!reveal()) return; // replay / reveal off: nothing new is shown (what was shown stays until the player leaves replay)
      const snap = [...snaps].reverse().find((s) => reveal(s.updatedAt ?? undefined));
      if (!snap) return;
      const d = applyReveal(snap, reveal);
      // key moments across the snapshots kept, each past the delay on its own time
      let fresh = false;
      for (const s of snaps)
        for (const m of s.moments as Moment[]) {
          const k = `${m.at}|${m.kind}|${m.team}`;
          if (seen.has(k) || !reveal(m.at)) continue;
          seen.add(k);
          if (!baseline) fresh = true;
        }
      if (fresh) momentN++;
      const names = { home: fixture?.home.short ?? fixture?.home.name ?? "HOME", away: fixture?.away.short ?? fixture?.away.name ?? "AWAY" };
      const key = `${d.score?.home}-${d.score?.away}|${d.period}`;
      if (key !== prevKey) {
        if (prevKey && !baseline) changedAt = Date.now();
        prevKey = key;
      }
      if (fresh) changedAt = Date.now();
      const kind = d.games.length > 0 ? null : gameStateOf(d, fixture, spoilers).kind;
      const next: FeedView = { score: d.score, clock: d.clock, period: d.period, kind, home: names.home.toUpperCase(), away: names.away.toUpperCase(), changedAtMs: changedAt, momentN };
      if (!same(cur, next)) {
        cur = next;
        setView(next);
      }
    };

    const load = async () => {
      const a = await liveStats(matchId);
      if (!alive) return;
      if (a && a !== "missing") {
        snaps.push(a);
        if (snaps.length > KEEP) snaps.shift();
        derive();
        // the first batch only sets the baseline: moments already on it are not "new"
        if (baseline) {
          baseline = false;
          changedAt = changedAt ?? Date.now(); // show the pill once when the player opens on a live game
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
      if (alive && m && m !== "missing") fixture = m.fixture;
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
  }, [allowed, matchId, spoilers]);

  return view;
}
