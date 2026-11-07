// Companion panel + scorebug policy (pure; tested in web/test/companion.test.ts). Plan: ~/ai/plans/jellytip-sport-companion.md section 3.
// Spike decision (~/ai/state/jellytip-m2spike-20261107.md): squeeze-back with ONE layout change per open/close; opaque overlay over
// 28% of the frame when the phone is in portrait or the measured drop rate after an open is over 2%.
import type { GameStateKind, Sport } from "./gameState.ts";

export const MOMENT_OPEN_MS = 6_000;
export const USER_CLOSE_QUIET_MS = 120_000;
export const SCOREBUG_HIDE_MS = 8_000;
export const DROP_LIMIT = 0.02;
export const DROP_WINDOW_MS = 30_000;
/** Too few frames to judge a rate from. */
export const DROP_MIN_FRAMES = 60;

export type PanelMode = "squeeze" | "overlay";
export type OpenReason = "user" | "break" | "moment";

export interface AutoOpenIn {
  nowMs: number;
  replay: boolean;
  panelOpen: boolean;
  /** When the viewer last closed the panel themselves (ms), null if never. */
  userClosedAtMs: number | null;
  kind: GameStateKind | null;
  /** A revealed key moment that arrived since the last check (already past the delay). */
  freshMoment: boolean;
}

/** Should the panel open by itself right now, and why. Never in replay, never straight after the viewer closed it, never in a blowout for a moment. */
export function autoOpenDecision(i: AutoOpenIn): OpenReason | null {
  if (i.replay || i.panelOpen) return null;
  if (i.userClosedAtMs != null && i.nowMs - i.userClosedAtMs < USER_CLOSE_QUIET_MS) return null;
  if (i.kind === "break") return "break";
  if (i.freshMoment && i.kind !== "blowout" && i.kind !== "final" && i.kind !== "pre") return "moment";
  return null;
}

export interface AutoCloseIn {
  nowMs: number;
  openedBy: OpenReason;
  openedAtMs: number;
  kind: GameStateKind | null;
  /** The viewer interacted with the panel (focus inside, tab change): then it stays until they close it. */
  touched: boolean;
  replay: boolean;
}

/** Tuck an automatically opened panel away: a moment after 6 s, a break when play resumes. User-opened or touched panels never auto-close. */
export function autoCloseDecision(i: AutoCloseIn): boolean {
  if (i.openedBy === "user" || i.touched) return false;
  if (i.replay) return true;
  if (i.openedBy === "moment") return i.nowMs - i.openedAtMs >= MOMENT_OPEN_MS;
  return i.kind !== "break";
}

/** Index of the newest snapshot the delay has released (-1 if none). Snapshots are oldest first; each is asked with its own time. */
export function newestRevealedIndex(snaps: readonly { updatedAt: number | null }[], reveal: (atSec?: number) => boolean): number {
  for (let i = snaps.length - 1; i >= 0; i--) if (reveal(snaps[i].updatedAt ?? undefined)) return i;
  return -1;
}

export const DROP_KEY = "jellytip.companionPanelMode";

/** Which presentation to use: the phone in portrait is always the overlay (bottom sheet); otherwise what this device remembers; else squeeze. */
export function panelModeFor(phonePortrait: boolean, remembered: string | null): PanelMode {
  if (phonePortrait) return "overlay";
  return remembered === "overlay" ? "overlay" : "squeeze";
}

/** True when playback dropped too many frames while the panel was squeezing the picture (counters are the deltas since the open). */
export function dropsTooHigh(totalFrames: number, droppedFrames: number): boolean {
  if (!(totalFrames >= DROP_MIN_FRAMES) || !(droppedFrames >= 0)) return false;
  return droppedFrames / totalFrames > DROP_LIMIT;
}

export type PanelKeyAction = "toggle-panel" | "channels" | "tab-prev" | "tab-next" | "close-panel";

export interface PanelKeyIn {
  key: string;
  panelOpen: boolean;
  /** A control has focus (or a menu is open): focus navigation keeps the arrow keys, as today. */
  controlsInUse: boolean;
  /** Only a live match with a panel available. */
  available: boolean;
  /** Other channels for this match: Down is ours only when there are some. */
  alternates: number;
}

/**
 * Hidden controls: Up = panel toggle, Down = channels, Left/Right = panel tabs (only while the panel is open), Back closes the panel
 * first. With controls in use the arrows are focus navigation. Back always closes an open panel, controls or not. Null = not ours.
 */
export function panelKeyAction(i: PanelKeyIn): PanelKeyAction | null {
  // Back closes an open panel whatever is playing (a recording's panel too)
  if ((i.key === "Escape" || i.key === "Backspace" || i.key === "GoBack") && i.panelOpen) return "close-panel";
  if (!i.available) return null;
  if (i.controlsInUse) return null;
  switch (i.key) {
    case "ArrowUp":
      return "toggle-panel";
    case "ArrowDown":
      return i.panelOpen || i.alternates < 1 ? null : "channels";
    case "ArrowLeft":
      return i.panelOpen ? "tab-prev" : null;
    case "ArrowRight":
      return i.panelOpen ? "tab-next" : null;
    default:
      return null;
  }
}

/** Index of the neighbouring tab, wrapping nowhere (the ends stay put). */
export const stepTab = (cur: number, count: number, dir: 1 | -1): number => (count <= 0 ? 0 : Math.max(0, Math.min(count - 1, cur + dir)));

export interface BugIn {
  score: { home: number; away: number } | null;
  clock: string | null;
  period: string | null;
  home: string;
  away: string;
}

/** "NZL 21 – 18 AUS · 2nd half 62:10"; null when nothing may be shown (no revealed score). */
export function scorebugText(i: BugIn): { teams: [string, string]; score: [number, number]; when: string } | null {
  if (!i.score) return null;
  const when = [i.period, i.clock].filter((x): x is string => !!x).join(" ");
  return { teams: [i.home, i.away], score: [i.score.home, i.score.away], when };
}

export interface BugShowIn {
  nowMs: number;
  /** When the revealed score / period / a moment last changed. */
  changedAtMs: number | null;
  pinned: boolean;
  panelOpen: boolean;
}

/** The pill shows for 8 s after a change, always when pinned, and never while the panel (which has the score strip) is open. */
export function scorebugVisible(i: BugShowIn): boolean {
  if (i.panelOpen) return false;
  if (i.pinned) return true;
  return i.changedAtMs != null && i.nowMs - i.changedAtMs < SCOREBUG_HIDE_MS;
}

/** The longest a clock is run on past its snapshot: a poll is 15 s, so a late one never lets the pill drift far. */
export const MAX_RUN_ON_SEC = 20;

/**
 * A running "mm:ss" clock moved on by `elapsedSec` since it was revealed: NFL / NBA count down (stops at 0:00), NRL / rugby / football
 * count up. Minute clocks ("63'"), cricket, tennis, anything stopped (break, timeout, final) or unknown stay as the payload says.
 */
export function tickClock(clock: string | null, sport: Sport | null, running: boolean, elapsedSec: number): string | null {
  if (!clock || !running || !sport || !(elapsedSec >= 1)) return clock;
  const m = /^(\d{1,2}):(\d{2})$/.exec(clock.trim());
  if (!m) return clock;
  const down = sport === "nfl" || sport === "nba";
  const up = sport === "nrl" || sport === "rugby" || sport === "football";
  if (!down && !up) return clock;
  const e = Math.min(Math.floor(elapsedSec), MAX_RUN_ON_SEC);
  const total = Number(m[1]) * 60 + Number(m[2]);
  const t = down ? Math.max(0, total - e) : total + e;
  const mins = Math.floor(t / 60);
  return `${m[1].length === 2 ? String(mins).padStart(2, "0") : mins}:${String(t % 60).padStart(2, "0")}`;
}

/** The clock is stopped when play is paused (break, final, pre) or the last two snapshots, 10+ s apart, showed the same clock and period. */
export function clockRunning(kind: GameStateKind | null, prev: { clock: string | null; period: string | null; atMs: number } | null, cur: { clock: string | null; period: string | null; atMs: number }): boolean {
  if (kind === "break" || kind === "final" || kind === "pre") return false;
  if (prev && cur.atMs - prev.atMs >= 10_000 && prev.clock === cur.clock && prev.period === cur.period) return false;
  return true;
}

/** Extras for the channel switched to: this one joins the alternates, and the match, replay and no-spoil state all carry over. */
export function switchExtras(
  extras: { alternates?: { id: string; name: string }[]; startsAt?: number | null; eventKey?: string | null; matchId?: string | null; replay?: boolean; nospoil?: boolean },
  pickId: string,
  current: { id: string; name: string },
) {
  return {
    alternates: [...(extras.alternates ?? []).filter((c) => c.id !== pickId && c.id !== current.id), current],
    startsAt: extras.startsAt,
    eventKey: extras.eventKey,
    matchId: extras.matchId,
    replay: extras.replay,
    nospoil: extras.nospoil,
  };
}

/**
 * Whole seconds until the answer stamped `updatedAt` is released by the spoiler delay (0 = already), found by bisecting the reveal
 * function (true for event times up to now - delay - lag). Null when nothing is released at all (replay) or there is no time.
 */
export function holdSecs(updatedAt: number | null, reveal: (atSec?: number) => boolean, nowSec: number): number | null {
  if (updatedAt == null || !isFinite(updatedAt)) return null;
  if (reveal(updatedAt)) return 0;
  let lo = nowSec - 1200; // released for sure, unless replay
  if (!reveal(lo)) return null;
  let hi = nowSec + 10;
  for (let i = 0; i < 14; i++) {
    const mid = (lo + hi) / 2;
    if (reveal(mid)) lo = mid;
    else hi = mid;
  }
  return Math.max(1, Math.round(updatedAt - lo)); // the bisection lands within 0.1 s
}
