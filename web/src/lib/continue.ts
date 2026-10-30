// IPTV in Continue Watching / Next Up (pure; tested in web/test/iptv.test.ts). Contract: ~/ai/plans/jellytip-8h-oct30.md "Block 1".
// GET /v1/me/continue → {items:[{source:"iptv", kind, id, seriesId?, title, seriesTitle?, season?, episode?, pos, dur, at, poster, backdrop, tech}]}.

export interface ContinueEntry {
  kind: "movie" | "episode" | "recording";
  id: string;
  seriesId: string | null;
  title: string;
  seriesTitle: string | null;
  season: number | null;
  episode: number | null;
  /** Seconds in, and the length (0 when unknown). */
  pos: number;
  dur: number;
  /** Last played, epoch seconds. */
  at: number;
  poster: string | null;
  backdrop: string | null;
  tech: string[];
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);

export function parseContinue(raw: unknown): ContinueEntry[] {
  const r = obj(raw);
  const list = Array.isArray(r?.items) ? (r!.items as unknown[]) : Array.isArray(raw) ? raw : [];
  const out: ContinueEntry[] = [];
  const seen = new Set<string>();
  for (const x of list) {
    const o = obj(x);
    const id = str(o?.id);
    const kind = o?.kind === "movie" || o?.kind === "episode" || o?.kind === "recording" ? o.kind : null;
    const title = str(o?.title);
    if (!o || !id || !kind || !title || (o.source != null && o.source !== "iptv") || seen.has(`${kind}:${id}`)) continue;
    seen.add(`${kind}:${id}`);
    out.push({
      kind,
      id,
      seriesId: str(o.seriesId),
      title,
      seriesTitle: str(o.seriesTitle),
      season: num(o.season),
      episode: num(o.episode),
      pos: Math.max(0, num(o.pos) ?? 0),
      dur: Math.max(0, num(o.dur) ?? 0),
      at: num(o.at) ?? 0,
      poster: str(o.poster),
      backdrop: str(o.backdrop),
      tech: Array.isArray(o.tech) ? (o.tech as unknown[]).filter((t): t is string => typeof t === "string") : [],
    });
  }
  return out;
}

/** Tile title: films and recordings by name, episodes as "Show · S1 E2 · Name". */
export function continueTitle(e: ContinueEntry): string {
  if (e.kind !== "episode") return e.title;
  const se = e.season != null && e.episode != null ? `S${e.season} E${e.episode}` : null;
  return [e.seriesTitle, se, e.title].filter(Boolean).join(" · ");
}

/** The IPTV player route for an entry, resuming at its position (a position under 30 s starts from the beginning). */
export function continuePath(e: ContinueEntry): string {
  const q = new URLSearchParams();
  if (e.kind === "movie") q.set("vod", e.id);
  else if (e.kind === "recording") q.set("recording", e.id.replace(/^rec:/, ""));
  else {
    q.set("series", e.seriesId ?? e.id.replace(/:e:.*/, ":s:") + "");
    q.set("episode", e.id);
  }
  const resume = e.pos > 30 ? Math.floor(e.pos) : 0;
  return `/iptv/play?${q}&t=${encodeURIComponent(continueTitle(e))}${resume ? `&resume=${resume}` : ""}`;
}

export interface LibLike {
  Id: string;
  SeriesId?: string;
  UserData?: { LastPlayedDate?: string };
}
export type Merged<L> = { kind: "lib"; item: L } | { kind: "iptv"; entry: ContinueEntry };

const stamp = (i: LibLike) => {
  const t = i.UserData?.LastPlayedDate ? Date.parse(i.UserData.LastPlayedDate) / 1000 : NaN;
  return isFinite(t) ? t : null;
};

/**
 * Continue Watching: in-progress library and IPTV titles by last played (library items without a date keep Jellyfin's
 * order, placed by their neighbours). Then Next Up: library episodes of shows not already above, then IPTV ones (also
 * only for series not above), each in the server's order.
 */
export function mergeContinue<L extends LibLike>(resume: L[], next: L[], iptvResume: ContinueEntry[], iptvNext: ContinueEntry[]): Merged<L>[] {
  // Jellyfin returns resume newest first: an undated one is no newer than the one before it
  let prev = Infinity;
  const lib = resume.map((item, i) => {
    const t = stamp(item) ?? (prev === Infinity ? Number.MAX_SAFE_INTEGER - i : prev - 1);
    prev = t;
    return { m: { kind: "lib", item } as Merged<L>, t };
  });
  const tier1 = [...lib, ...iptvResume.map((entry) => ({ m: { kind: "iptv", entry } as Merged<L>, t: entry.at }))].sort((a, b) => b.t - a.t).map((x) => x.m);
  const series = new Set<string>([
    ...resume.map((i) => i.SeriesId ?? i.Id),
    ...iptvResume.filter((e) => e.kind === "episode").map((e) => e.seriesId ?? e.id),
  ]);
  const tier2: Merged<L>[] = [
    ...next.filter((n) => !series.has(n.SeriesId ?? n.Id)).map((item) => ({ kind: "lib", item }) as Merged<L>),
    ...iptvNext.filter((e) => !series.has(e.seriesId ?? e.id)).map((entry) => ({ kind: "iptv", entry }) as Merged<L>),
  ];
  return [...tier1, ...tier2];
}
