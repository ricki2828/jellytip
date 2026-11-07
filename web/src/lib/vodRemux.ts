// IPTV VOD played through the server's compat remux (AC-3/DTS films → fMP4 + AAC): the stream is not byte-range seekable and
// begins at `startAt`, so the element's currentTime is relative to that. These pure helpers map between the two clocks.

export interface RemuxAnswer { remux?: boolean; startAt?: number; duration?: number | null }

/** Seconds into the title the stream began (0 when the answer isn't a remux). */
export const remuxOffset = (a: RemuxAnswer | null | undefined): number => (a?.remux ? Math.max(0, Math.floor(a.startAt ?? 0)) : 0);

/** Element time → position in the title. */
export const toTimeline = (elementTime: number, offset: number): number => Math.max(0, elementTime) + offset;

export type SeekPlan = { kind: "local"; t: number } | { kind: "reload"; start: number };

/**
 * Where a seek (to a title position) is served: inside what the element has buffered it's a plain currentTime move (element
 * time = target - offset); anywhere else the stream is reloaded to begin at the target (start=<sec>). [length] clamps to the
 * title (0 = unknown: only the lower bound).
 */
export function planSeek(to: number, offset: number, buffered: ReadonlyArray<readonly [number, number]>, length = 0): SeekPlan {
  const max = length > 0 ? Math.max(0, length - 1) : Infinity;
  const target = Math.floor(Math.min(Math.max(0, to), max));
  const el = target - offset;
  if (el >= 0 && buffered.some(([s, e]) => el >= s && el <= e - 1)) return { kind: "local", t: el };
  return { kind: "reload", start: target };
}

/** TimeRanges → plain pairs. */
export function rangesOf(r: { length: number; start(i: number): number; end(i: number): number }): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (let i = 0; i < r.length; i++) out.push([r.start(i), r.end(i)]);
  return out;
}

/** What to save as watch progress: the title position and the provider length (the element's duration is the fragment, not the film). */
export function progressFor(elementTime: number, offset: number, elementDuration: number, length: number, remux: boolean): { pos: number; dur: number } {
  const pos = remux ? toTimeline(elementTime, offset) : elementTime;
  const ed = isFinite(elementDuration) && elementDuration > 0 ? elementDuration : 0;
  return { pos, dur: remux ? length : ed || length };
}
