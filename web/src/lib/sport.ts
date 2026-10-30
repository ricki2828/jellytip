// Live-sport fields the companion adds to on-now, guide and channel objects (contract: ~/ai/plans/jellytip-live-sport.md
// §2-§4). Everything is optional: a server without them gives no badges and the old behaviour. Pure; tested in
// web/test/sport.test.ts. The server decides the order; nothing here sorts.

export type Kind = "live" | "replay" | "highlights" | "other";
export type Bucket = "uhd" | "fhd50" | "fhd" | "hd" | "sd" | "slow" | "dead" | "unknown";

export interface Quality {
  bucket: Bucket;
  height: number | null;
  fps: number | null;
  /** Best audio the stream carries; stereo and unprobed show nothing. */
  audio: "atmos" | "5.1" | "stereo" | null;
}

export interface SportInfo {
  kind: Kind | null;
  sport: boolean;
  quality: Quality | null;
  /** Other channels showing the same event, best first. */
  alternates: string[];
  eventKey: string | null;
  match: { team: string | null; league: string | null } | null;
}

const KINDS: Kind[] = ["live", "replay", "highlights", "other"];
const BUCKETS: Bucket[] = ["uhd", "fhd50", "fhd", "hd", "sd", "slow", "dead", "unknown"];

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);

export function parseQuality(raw: unknown): Quality | null {
  const q = obj(raw);
  if (!q || !BUCKETS.includes(q.bucket as Bucket)) return null;
  const a = typeof q.audio === "string" ? q.audio.toLowerCase() : "";
  return { bucket: q.bucket as Bucket, height: num(q.height), fps: num(q.fps), audio: a === "atmos" ? "atmos" : a === "5.1" ? "5.1" : a === "stereo" ? "stereo" : null };
}

/** Reads the sport fields off an on-now / guide / channel object (`programme` is where kind and sport live). */
export function parseSport(raw: unknown, programme?: unknown): SportInfo {
  const r = obj(raw) ?? {};
  const p = obj(programme) ?? obj(r.programme) ?? obj(r.now) ?? {};
  const kind = KINDS.includes(p.kind as Kind) ? (p.kind as Kind) : KINDS.includes(r.kind as Kind) ? (r.kind as Kind) : null;
  const ev = obj(r.event);
  const m = obj(r.match);
  return {
    kind,
    sport: p.sport === true || r.sport === true,
    quality: parseQuality(r.quality),
    alternates: Array.isArray(ev?.alternates) ? (ev!.alternates as unknown[]).filter((x): x is string => typeof x === "string") : [],
    eventKey: str(ev?.key),
    match: m ? { team: str(m.team), league: str(m.league) } : null,
  };
}

/** True when the server sent any of the new fields (then its order is final; otherwise the old sport-first rule applies). */
export const hasServerRanking = (s: SportInfo) => s.kind != null || s.quality != null || s.eventKey != null || s.match != null;

/** Chip text for a stream: UHD / 1080 50fps / 1080 / 720 / SD; null when unknown, dead or slow (slow has its own hint). */
export function qualityLabel(q: Quality | null): string | null {
  if (!q) return null;
  switch (q.bucket) {
    case "uhd":
      return "UHD";
    case "fhd50":
      return "1080 50fps";
    case "fhd":
      return "1080";
    case "hd":
      return "720";
    case "sd":
      return "SD";
    case "slow":
      // Resolution is still worth showing beside the hint
      return q.height ? (q.height >= 2000 ? "UHD" : q.height >= 1000 ? (q.fps && q.fps >= 48 ? "1080 50fps" : "1080") : q.height >= 700 ? "720" : "SD") : null;
    default:
      return null;
  }
}

/** Chip text for the audio: ATMOS or 5.1; nothing for stereo or unknown. */
export const audioLabel = (q: Quality | null): string | null => (q?.audio === "atmos" ? "ATMOS" : q?.audio === "5.1" ? "5.1" : null);

export const mayBuffer = (q: Quality | null) => q?.bucket === "slow";

/** Pill for a programme: red LIVE, grey REPLAY (replays and highlights), nothing for the rest. */
export function kindPill(kind: Kind | null | undefined): { text: "LIVE" | "REPLAY"; tone: "live" | "replay" } | null {
  if (kind === "live") return { text: "LIVE", tone: "live" };
  if (kind === "replay" || kind === "highlights") return { text: "REPLAY", tone: "replay" };
  return null;
}

// ---------- settings ----------

export interface Suggestion {
  name: string;
  type: "team" | "league";
}

/** GET /v1/iptv/sport/suggest?q= → {teams:[…], leagues:[…]} (strings or {name}); anything else = none. */
export function parseSuggest(raw: unknown): Suggestion[] {
  const r = obj(raw);
  const names = (v: unknown) => (Array.isArray(v) ? v : []).map((x) => (typeof x === "string" ? x : str(obj(x)?.name))).filter((x): x is string => !!x);
  const out: Suggestion[] = [];
  const seen = new Set<string>();
  for (const [key, type] of [["teams", "team"], ["leagues", "league"]] as const)
    for (const name of names(r?.[key])) {
      const k = `${type}:${name.toLowerCase()}`;
      if (!seen.has(k)) {
        seen.add(k);
        out.push({ name, type });
      }
    }
  return out;
}

export interface QualitySummary {
  counts: { bucket: Bucket; n: number }[];
  /** Onboarding pass: checked of total, or null when the server doesn't say. */
  progress: { done: number; total: number } | null;
}

/** Quality summary → counts per bucket (best first, empty ones dropped) and onboarding progress. */
export function parseQualitySummary(raw: unknown): QualitySummary | null {
  const r = obj(raw);
  const b = obj(r?.buckets) ?? obj(r?.counts);
  if (!r || !b) return null;
  const counts = BUCKETS.map((bucket) => ({ bucket, n: num(b[bucket]) ?? 0 })).filter((c) => c.n > 0);
  const p = obj(r.progress) ?? obj(r.onboarding);
  const done = num(p?.done) ?? num(p?.checked);
  const total = num(p?.total);
  return { counts, progress: done != null && total != null && total > 0 ? { done, total } : null };
}

export const BUCKET_NAME: Record<Bucket, string> = {
  uhd: "UHD",
  fhd50: "1080 50fps",
  fhd: "1080",
  hd: "720",
  sd: "SD",
  slow: "May buffer",
  dead: "Not working",
  unknown: "Not checked yet",
};

/** Add or remove a name (case-insensitive) from a list, keeping order. */
export const toggleName = (list: string[], name: string): string[] =>
  list.some((x) => x.toLowerCase() === name.toLowerCase()) ? list.filter((x) => x.toLowerCase() !== name.toLowerCase()) : [...list, name];
