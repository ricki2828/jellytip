// "Downloads at once" queue rules (pure: no I/O, unit-tested in web/test/downloadQueue.test.ts).
//
// States a download moves through:
//   serverQueued -> preparing -> waiting -> queued/downloading/paused (in DownloadManager) -> done | failed
//   waiting -> ... directly when the original (or a live transcode) can be fetched without server preparation.
// Only files in DownloadManager hold one of the N slots. Server preparation is serialised (the companion runs one
// job at a time): at most one item is "preparing", the rest wait as "serverQueued". Order is the `seq` they were added
// in, so a season downloads in episode order.

export type QState = "serverQueued" | "preparing" | "waiting" | "queued" | "downloading" | "paused" | "done" | "failed";

export interface QItem {
  id: string;
  state: QState;
  seq: number;
}

export const SLOT_STATES: QState[] = ["queued", "downloading", "paused"];
export const LIMIT_OPTIONS = [1, 2, 3, 5] as const;
export const DEFAULT_LIMIT = 2;

const bySeq = (a: QItem, b: QItem) => a.seq - b.seq;

export function normaliseLimit(n: unknown): number {
  const v = Number(n);
  return (LIMIT_OPTIONS as readonly number[]).includes(v) ? v : DEFAULT_LIMIT;
}

/** Downloads currently holding a slot. */
export const activeCount = (items: QItem[]) => items.filter((i) => SLOT_STATES.includes(i.state)).length;

/** Files ready to fetch, in the order they get slots (handed to the native queue as-is). */
export const waitingLine = (items: QItem[]) => items.filter((i) => i.state === "waiting").sort(bySeq);

/** How many waiting files should start now. Lowering the limit never cancels: it just starts nothing until below. */
export function freeSlots(items: QItem[], limit: number): number {
  return Math.max(0, normaliseLimit(limit) - activeCount(items));
}

/** The ids that would start now (what the native pump does with the same inputs). */
export const toStart = (items: QItem[], limit: number) => waitingLine(items).slice(0, freeSlots(items, limit)).map((i) => i.id);

/** Next item to send for server preparation, or null while one is already preparing. */
export function nextPrepare(items: QItem[]): string | null {
  if (items.some((i) => i.state === "preparing")) return null;
  return items.filter((i) => i.state === "serverQueued").sort(bySeq)[0]?.id ?? null;
}

/** 1-based place in its line ("Queued (2 in line)" / "Queued for server (3)"), or null when not waiting. */
export function linePosition(items: QItem[], id: string): number | null {
  const me = items.find((i) => i.id === id);
  if (!me) return null;
  if (me.state === "waiting") return waitingLine(items).findIndex((i) => i.id === id) + 1;
  if (me.state === "serverQueued") return items.filter((i) => i.state === "serverQueued").sort(bySeq).findIndex((i) => i.id === id) + 1;
  return null;
}

/** Monotonic ordering key: Date.now() can repeat within a season loop. */
let lastSeq = 0;
export function nextSeq(now = Date.now()): number {
  lastSeq = Math.max(lastSeq + 1, now * 1000);
  return lastSeq;
}
