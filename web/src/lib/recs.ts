// Recordings on Home and in the nav (pure; tested in web/test/recs.test.ts). Order: recording now, ready and unwatched
// (NEW), scheduled by start time, then watched. Failed and cancelled ones are not shown.

export interface Rec {
  id: number | string;
  channelId: string;
  channel: string;
  title: string;
  desc?: string;
  start: number;
  stop: number;
  stopPadded?: number;
  state: "scheduled" | "recording" | "done" | "failed" | "cancelled";
  watched?: boolean;
  playable?: boolean;
  progress?: { position: number; duration: number; played?: boolean } | null;
}

const rank = (r: Rec) => (r.state === "recording" ? 0 : r.state === "done" && !r.watched ? 1 : r.state === "scheduled" ? 2 : 3);

export const shown = (rs: Rec[]) => rs.filter((r) => r.state === "recording" || r.state === "scheduled" || r.state === "done");

export function orderRecs(rs: Rec[]): Rec[] {
  return shown(rs)
    .map((r, i) => ({ r, i }))
    .sort((a, b) => {
      const ra = rank(a.r);
      const rb = rank(b.r);
      if (ra !== rb) return ra - rb;
      // scheduled: soonest first; the rest: newest first
      const d = ra === 2 ? a.r.start - b.r.start : b.r.stop - a.r.stop;
      return d || a.i - b.i;
    })
    .map((x) => x.r);
}

/** Ready (done, playable) and not yet watched: the nav badge. */
export const unwatchedCount = (rs: Rec[]) => rs.filter((r) => r.state === "done" && r.playable !== false && !r.watched).length;

/** True while something is scheduled or recording (Home keeps refreshing then). */
export const hasPending = (rs: Rec[]) => rs.some((r) => r.state === "scheduled" || r.state === "recording");

export type Chip = { text: string; tone: "rec" | "new" | "time" | "dim" };

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();
const hm = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

/** Chip for a tile: "● REC", "NEW", "Today 12:14" / "Sat 19:30", "Watched". */
export function chipFor(r: Rec, now: Date = new Date()): Chip {
  if (r.state === "recording") return { text: "● REC", tone: "rec" };
  if (r.state === "done") return r.watched ? { text: "Watched", tone: "dim" } : { text: "NEW", tone: "new" };
  const s = new Date(r.start * 1000);
  const tomorrow = new Date(now.getTime() + 86_400_000);
  const day = sameDay(s, now) ? "Today" : sameDay(s, tomorrow) ? "Tomorrow" : s.toLocaleDateString("en-GB", { weekday: "short" });
  return { text: `${day} ${hm(s)}`, tone: "time" };
}

/** "1h 30m" / "45m" for a programme's length. */
export function lengthText(r: Pick<Rec, "start" | "stop">): string {
  const m = Math.max(0, Math.round((r.stop - r.start) / 60));
  return m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `${m}m`;
}
