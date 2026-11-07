// Which live alerts show, and when (pure; tested in web/test/alertPolicy.test.ts). Plan "Alerts": key events only
// (goal / try / TD / wicket / red card / late lead change / favourite scores / fantasy swing >= 5), a 4 s lower-third, at most one
// per 30 s, never before the spoiler delay has passed; a blowout suppresses everything but the favourite's scores.

export type AlertMode = "key" | "all" | "off";
export const parseAlertMode = (v: unknown): AlertMode => (v === "all" || v === "off" ? v : "key");

export const MIN_GAP_SEC = 30;
export const SHOW_MS = 4000;
/** An event is stale, and dropped rather than shown late, this long after it was RELEASED by the spoiler delay (not after its own time: a long delay would expire everything). */
export const EXPIRE_SEC = 120;
/** Hard cap from the event's own time: the longest delay (300 s) + the replay lag (120 s) + the stale window, so a candidate that never gets released still goes. */
export const MAX_PENDING_SEC = 300 + 120 + EXPIRE_SEC;
export const QUEUE_MAX = 4;
export const FANTASY_SWING_MIN = 5;

export interface Candidate<T = unknown> {
  id: string;
  /** goal, try, touchdown, field_goal, score, wicket, red_card, lead_change, clutch, fantasy, flip, ... */
  kind: string;
  /** Event time, epoch seconds (the clock the delay maths runs on). */
  at: number;
  /** A favourite team scored. */
  favourite?: boolean;
  /** A lead change in the closing stages. */
  late?: boolean;
  /** Fantasy points swing (absolute). */
  swing?: number;
  /** Set by the gate the first tick the spoiler delay released this event (epoch s). */
  revealedAt?: number;
  payload: T;
}

const ALWAYS = new Set(["goal", "try", "touchdown", "wicket", "red_card"]);
const PRIORITY: Record<string, number> = { goal: 5, try: 5, touchdown: 5, wicket: 5, red_card: 5, field_goal: 4, score: 4, lead_change: 3, clutch: 3, fantasy: 2, flip: 1 };
const prio = (c: Candidate) => PRIORITY[c.kind] ?? 0;

/** Whether this event is alert-worthy under the person's mode and the blowout state. */
export function isKeyEvent(c: Candidate, mode: AlertMode, blowout = false): boolean {
  if (mode === "off") return false;
  if (blowout && !c.favourite) return false;
  if (mode === "all") return true;
  if (c.kind === "flip") return true; // governed by its own switch (sport.flipAlerts), still delayed and rate-limited
  if (ALWAYS.has(c.kind)) return true;
  if (c.kind === "lead_change") return !!c.late;
  if (c.kind === "fantasy") return (c.swing ?? 0) >= FANTASY_SWING_MIN;
  return !!c.favourite; // a favourite's field goal / score
}

export interface GateState {
  /** Epoch seconds the last alert went up (null = none yet). */
  lastShownAt: number | null;
}

export interface Pick<T> {
  show: Candidate<T> | null;
  pending: Candidate<T>[];
}

/**
 * One tick of the gate. Drops what is not key, what is stale and duplicates; if the 30 s gap has passed it picks the revealed
 * candidate with the highest priority (oldest first on a tie) and removes it from the queue. Everything not yet revealed waits.
 */
export function pickAlert<T>(pending: Candidate<T>[], state: GateState, nowSec: number, o: { reveal: (at: number) => boolean; mode: AlertMode; blowout?: boolean }): Pick<T> {
  const seen = new Set<string>();
  let keep = pending
    .map((c) => (c.revealedAt == null && o.reveal(c.at) ? { ...c, revealedAt: nowSec } : c))
    .filter((c) => {
      const stale = c.revealedAt != null ? nowSec - c.revealedAt > EXPIRE_SEC : false;
      if (seen.has(c.id) || stale || nowSec - c.at > MAX_PENDING_SEC || !isKeyEvent(c, o.mode, o.blowout)) return false;
      seen.add(c.id);
      return true;
    });
  if (keep.length > QUEUE_MAX) keep = keep.slice(keep.length - QUEUE_MAX);
  if (state.lastShownAt != null && nowSec - state.lastShownAt < MIN_GAP_SEC) return { show: null, pending: keep };
  const ready = keep.filter((c) => o.reveal(c.at)).sort((a, b) => prio(b) - prio(a) || a.at - b.at);
  const show = ready[0] ?? null;
  return { show, pending: show ? keep.filter((c) => c !== show) : keep };
}

/** The shared limiter: the flip/moment and fantasy alerts must not stack, so one clock serves both. */
export const sharedGate: GateState = { lastShownAt: null };

/** "Late" for a lead change: final stages by sport (clock text is "75'" / "12:34" / "4th"). Unknown layouts are never late. */
export function isLateGame(layout: string | null | undefined, period: string | number | null | undefined, clock: string | null | undefined): boolean {
  const per = typeof period === "number" ? period : parseInt(String(period ?? ""), 10);
  const mins = clock ? parseInt(clock, 10) : NaN; // "75'" -> 75; "12:34" -> 12
  if (layout === "basketball" || layout === "nfl") return per >= 4;
  if (layout === "football") return per >= 2 && mins >= 75;
  if (layout === "rugby") return per >= 2 && mins >= 65;
  return false;
}

/** Margin of this many or more with the game this late is a blowout (alerts all but favourite scores stop). */
export function isBlowout(layout: string | null | undefined, home: number | null | undefined, away: number | null | undefined, late: boolean): boolean {
  if (home == null || away == null) return false;
  const gap = Math.abs(home - away);
  const big: Record<string, number> = { basketball: 25, nfl: 24, football: 4, rugby: 28 };
  return late && gap >= (layout ? big[layout] ?? Infinity : Infinity);
}
