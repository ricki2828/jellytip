// Skip intro / recap / credits (pure, tested in test/skipParts.test.ts). Two sources, one button:
// - IPTV films / episodes: GET v1/iptv/chapters (TV parity §5, d10d8492 + ee16540e): the file's own named chapters, read by the
//   server from the first bytes it relays. chapters null = not probed yet (ask again), [] = the file has none.
// - Library: Jellyfin MediaSegments (the TV library player's SkipSegmentButton), else the item's chapter names.
// Times are seconds on the title's timeline (a remux's offset already added).

export type SkipKind = "intro" | "recap" | "credits" | "preview" | "commercial";

export interface SkipSeg {
  kind: SkipKind;
  s: number;
  e: number;
}

export interface IptvChapter {
  s: number;
  e: number;
  t: string | null;
}

export interface IptvChapters {
  /** null = the server hasn't probed the file yet (ask again later); [] = it has none. */
  chapters: IptvChapter[] | null;
  skip: SkipSeg[];
}

/** After open: ask at these delays while the server says "not probed yet" (a file played before is known at the first ask). */
export const POLL_DELAYS_MS = [2_000, 25_000, 60_000, 150_000];
/** The button shows for this long (seconds of play) from where the part was first reached (the library player's 10 s). */
export const SHOW_SEC = 10;
/** No button in the last seconds of a part: the skip would land almost where the player already is. */
export const TAIL_SEC = 3;
/** A skip to the very end stops this far short of it (a remux reopened at end of file is untested ground). */
export const END_MARGIN_SEC = 1.5;

const IPTV_KINDS = new Set(["intro", "recap", "credits"]);
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : NaN);

/** The chapters answer; null when the body isn't one. */
export function parseIptvChapters(body: unknown): IptvChapters | null {
  if (!body || typeof body !== "object") return null;
  const o = body as { chapters?: unknown; skip?: unknown };
  const chapters = Array.isArray(o.chapters)
    ? o.chapters.flatMap((c): IptvChapter[] => {
        if (!c || typeof c !== "object") return [];
        const r = c as { s?: unknown; e?: unknown; t?: unknown };
        const s = num(r.s);
        const e = num(r.e);
        if (Number.isNaN(s) || Number.isNaN(e) || e <= s) return [];
        return [{ s, e, t: typeof r.t === "string" && r.t.trim() ? r.t : null }];
      })
    : null;
  const skip = Array.isArray(o.skip)
    ? o.skip.flatMap((c): SkipSeg[] => {
        if (!c || typeof c !== "object") return [];
        const r = c as { kind?: unknown; s?: unknown; e?: unknown };
        const s = num(r.s);
        const e = num(r.e);
        if (typeof r.kind !== "string" || !IPTV_KINDS.has(r.kind) || Number.isNaN(s) || Number.isNaN(e) || e <= s) return [];
        return [{ kind: r.kind as SkipKind, s, e }];
      })
    : [];
  return { chapters, skip };
}

/** What one ask means: use it, ask again at the next delay, or stop (only a 404: no such route / no such title). */
export function chaptersOutcome(status: number, body: unknown): { kind: "ok"; value: IptvChapters } | { kind: "again" } | { kind: "stop" } {
  if (status === 404) return { kind: "stop" };
  if (status !== 200) return { kind: "again" };
  const c = parseIptvChapters(body);
  return c && c.chapters !== null ? { kind: "ok", value: c } : { kind: "again" };
}

/** The part [pos] is in (and not in its last [TAIL_SEC]), or null. */
export function activeSeg(skip: SkipSeg[], pos: number): SkipSeg | null {
  return skip.find((x) => pos >= x.s && pos < x.e - TAIL_SEC) ?? null;
}

/** Shown: in the part, within [SHOW_SEC] of where it was first reached ([firstSeen], null on the first tick), not dismissed. */
export function skipVisible(seg: SkipSeg | null, pos: number, firstSeen: number | null, dismissed: boolean): boolean {
  if (!seg || dismissed) return false;
  if (firstSeen == null) return true;
  const d = pos - firstSeen;
  return d >= 0 && d < SHOW_SEC;
}

export function skipLabel(kind: SkipKind): string {
  switch (kind) {
    case "intro":
      return "Skip intro";
    case "recap":
      return "Skip recap";
    case "credits":
      return "Skip credits";
    case "preview":
      return "Skip preview";
    case "commercial":
      return "Skip ad";
  }
}

export type SkipAction = { kind: "next" } | { kind: "seek"; to: number };

/** Credits with a next episode: play it; otherwise to the part's end, stopping [END_MARGIN_SEC] short of the file's end. */
export function skipAction(seg: SkipSeg, hasNext: boolean, duration = 0): SkipAction {
  if (seg.kind === "credits" && hasNext) return { kind: "next" };
  return { kind: "seek", to: duration > END_MARGIN_SEC ? Math.min(seg.e, duration - END_MARGIN_SEC) : seg.e };
}

/** Chapter starts for the scrub bar (the Scrubber's marks), inside the bar only. */
export function iptvChapterMarks(chapters: IptvChapter[] | null | undefined, duration: number): { at: number; name: string }[] {
  if (!chapters?.length || !(duration > 0)) return [];
  const seen = new Set<number>();
  return chapters.flatMap((c) => {
    const f = c.s / duration;
    if (f <= 0.005 || f >= 0.995 || seen.has(c.s)) return [];
    seen.add(c.s);
    return [{ at: c.s, name: c.t ?? "" }];
  });
}

// ---------- library (Jellyfin) ----------

const TICKS = 10_000_000;
const SEG_KIND: Record<string, SkipKind> = { Intro: "intro", Recap: "recap", Outro: "credits", Preview: "preview", Commercial: "commercial" };

/** GET /MediaSegments/{id} -> {Items:[{Type, StartTicks, EndTicks}]}; Unknown and broken ones dropped. */
export function parseMediaSegments(body: unknown): SkipSeg[] {
  const items = body && typeof body === "object" ? (body as { Items?: unknown }).Items : null;
  if (!Array.isArray(items)) return [];
  return items
    .flatMap((x): SkipSeg[] => {
      if (!x || typeof x !== "object") return [];
      const r = x as { Type?: unknown; StartTicks?: unknown; EndTicks?: unknown };
      const kind = typeof r.Type === "string" ? SEG_KIND[r.Type] : undefined;
      const s = num(r.StartTicks) / TICKS;
      const e = num(r.EndTicks) / TICKS;
      if (!kind || Number.isNaN(s) || Number.isNaN(e) || e <= s) return [];
      return [{ kind, s, e }];
    })
    .sort((a, b) => a.s - b.s);
}

// The server's chapters.segments (jellytip_api/iptv/chapters.py): qualified names only, so a Blu-ray "Ending" scene never
// becomes a credits skip over the film's real ending.
const INTRO = /^(?:intro(?:duction)?|opening\s+(?:titles|credits|theme|song|sequence)|op|main\s+titles|title\s+sequence|theme\s+song)$/i;
const RECAP = /^(?:recap|previously(?:\s+on\b.*)?|previous(?:ly)?)$/i;
const CREDITS = /^(?:(?:end(?:ing)?|closing)\s+credits|credits|outro|ed|end\s+titles|closing\s+titles)$/i;
const MIN_SEG = 5;
const MAX_INTRO = 300;

function chapterKind(title: string): SkipKind | null {
  const t = title.replace(/[\s_.:-]+/g, " ").trim();
  if (INTRO.test(t)) return "intro";
  if (RECAP.test(t)) return "recap";
  if (CREDITS.test(t)) return "credits";
  return null;
}

/** Skips named by chapters ({at, name} starts, as lib/chapters' marks): one per kind (the first intro / recap, the last credits). */
export function chapterSegments(marks: { at: number; name: string }[], duration: number): SkipSeg[] {
  const found = new Map<SkipKind, SkipSeg>();
  marks.forEach((m, i) => {
    const kind = chapterKind(m.name);
    if (!kind) return;
    const e = i + 1 < marks.length ? marks[i + 1].at : duration > 0 ? duration : NaN;
    if (Number.isNaN(e) || e - m.at < MIN_SEG || (kind !== "credits" && e - m.at > MAX_INTRO)) return;
    if (kind === "credits" || !found.has(kind)) found.set(kind, { kind, s: m.at, e });
  });
  return [...found.values()].sort((a, b) => a.s - b.s);
}

/** Retry delay for a failed library segments ask (never remembered): 5 s doubling, at most 5 min. Null = stop (404). */
export function segmentsRetryMs(status: number, attempt: number): number | null {
  if (status === 404) return null;
  return Math.min(300_000, 5_000 * 2 ** Math.max(0, attempt));
}

/** The chapters query for a film / episode target; null for live, catch-up and recordings (no chapters). */
export function chaptersQuery(t: { vod?: string; episode?: string; series?: string; channel?: string; start?: number; recording?: string }): string | null {
  if (t.vod) return `vod=${encodeURIComponent(t.vod)}`;
  if (t.episode && t.series) return `episode=${encodeURIComponent(t.episode)}&series=${encodeURIComponent(t.series)}`;
  return null;
}

/** The phone's native player payload (PlayerPlugin.chapters): chapter starts in ms (not 0) and the IPTV skips. */
export function nativeChapters(c: IptvChapters, hasNext = false): { marksMs: number[]; skip: { kind: string; s: number; e: number }[]; hasNext: boolean } {
  const marksMs = [...new Set((c.chapters ?? []).map((x) => Math.round(x.s * 1000)).filter((ms) => ms > 0))];
  return { marksMs, skip: c.skip.map((x) => ({ kind: x.kind, s: x.s, e: x.e })), hasNext };
}
