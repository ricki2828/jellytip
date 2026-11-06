// Chapter helpers for the seek bar (pure). Jellyfin chapters: StartPositionTicks (10,000,000 per second).
export interface Chapter {
  Name?: string;
  StartPositionTicks?: number;
}
export interface ChapterMark {
  at: number; // seconds
  name: string;
}
const TICKS = 10_000_000;
const RESTART_WINDOW = 3; // "previous" within this many seconds of a chapter start goes to the one before

export function chapterMarks(raw: Chapter[] | undefined | null, duration = Infinity): ChapterMark[] {
  return (raw ?? [])
    .filter((c) => typeof c.StartPositionTicks === "number")
    .map((c) => ({ at: c.StartPositionTicks! / TICKS, name: c.Name ?? "" }))
    .filter((c) => c.at >= 0 && c.at < duration)
    .sort((a, b) => a.at - b.at);
}

/** Start of the next chapter after t, or null (last chapter / no chapters). */
export function nextChapter(marks: ChapterMark[], t: number): number | null {
  const m = marks.find((c) => c.at > t + 0.5);
  return m ? m.at : null;
}

/** Start of the current chapter, or of the previous one when just after a start. Null with no chapter before t. */
export function prevChapter(marks: ChapterMark[], t: number): number | null {
  let cur = -1;
  for (let i = 0; i < marks.length; i++) if (marks[i].at <= t) cur = i;
  if (cur < 0) return null;
  if (t - marks[cur].at > RESTART_WINDOW) return marks[cur].at;
  return cur > 0 ? marks[cur - 1].at : 0;
}

export function chapterAt(marks: ChapterMark[], t: number): ChapterMark | null {
  let cur: ChapterMark | null = null;
  for (const m of marks) if (m.at <= t) cur = m;
  return cur;
}
