// Guide grid maths (pure, tested): the time window, programme blocks clipped to it, ruler ticks, and which channel rows
// are on screen. Times are epoch seconds; positions are px from the window start.

export interface Prog {
  start: number;
  stop: number;
  title: string;
  desc?: string;
  catchup?: boolean;
  /** Live-sport smarts from the server (may be missing). */
  kind?: "live" | "replay" | "highlights" | "other";
  sport?: boolean;
}

export interface Block {
  prog: Prog;
  left: number;
  width: number;
  /** Started before the window (its left edge is cut). */
  clipped: boolean;
  /** Airing at `now`. */
  live: boolean;
  past: boolean;
}

export const HALF_HOUR = 1800;

/** Window start: the half hour at or before now - 30 min, so the now-line sits a little in from the left. */
export const windowStart = (now: number, lead = HALF_HOUR) => Math.floor((now - lead) / HALF_HOUR) * HALF_HOUR;

/** Programmes of one channel → blocks inside [from, to). Gaps stay gaps; overlaps are trimmed to the previous stop. */
export function layoutRow(progs: Prog[], from: number, to: number, pxPerSec: number, now: number): Block[] {
  const out: Block[] = [];
  let edge = from;
  for (const p of [...progs].sort((a, b) => a.start - b.start)) {
    if (p.stop <= from || p.start >= to || p.stop <= p.start) continue;
    const s = Math.max(p.start, from, edge);
    const e = Math.min(p.stop, to);
    if (e - s < 60) continue; // under a minute left after trimming: nothing to click
    out.push({ prog: p, left: (s - from) * pxPerSec, width: (e - s) * pxPerSec, clipped: p.start < from, live: p.start <= now && now < p.stop, past: p.stop <= now });
    edge = e;
  }
  return out;
}

/** Half-hour ticks in [from, to). */
export function ticks(from: number, to: number): number[] {
  const out: number[] = [];
  for (let t = Math.ceil(from / HALF_HOUR) * HALF_HOUR; t < to; t += HALF_HOUR) out.push(t);
  return out;
}

/** Rows to render for a scroll position (plus `overscan` either side). */
export function visibleRows(scrollTop: number, viewport: number, rowH: number, total: number, overscan = 4): [number, number] {
  const first = Math.max(0, Math.floor(scrollTop / rowH) - overscan);
  const last = Math.min(total, Math.ceil((scrollTop + viewport) / rowH) + overscan);
  return [first, Math.max(first, last)];
}

/** Chunks of channel ids to fetch the guide for (the server takes at most 300 per call). */
export function chunks<T>(xs: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < xs.length; i += size) out.push(xs.slice(i, i + size));
  return out;
}
