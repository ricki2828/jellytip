// Live-sport fields the companion adds to on-now, guide and channel objects (contract: ~/ai/plans/jellytip-live-sport.md
// §2-§4). Everything is optional: a server without them gives no badges and the old behaviour. Pure; tested in
// web/test/sport.test.ts. The server decides the order; nothing here sorts.

export type Kind = "live" | "replay" | "highlights" | "other";
export type Bucket = "uhd" | "fhd50" | "fhd" | "hd" | "sd" | "slow" | "dead" | "unknown";

export interface Quality {
  bucket: Bucket;
  height: number | null;
  fps: number | null;
  /** The server's own warning ("may buffer"), also set for bucket slow. */
  hint: string | null;
  /** Best audio the stream carries; stereo and unprobed show nothing. */
  audio: "atmos" | "5.1" | "stereo" | null;
}

export interface SportInfo {
  kind: Kind | null;
  sport: boolean;
  quality: Quality | null;
  /** Other channels showing the same event, best first. */
  alternates: string[];
  /** The same, with names and stream quality (newer servers); empty when only ids came. */
  alternateChannels: { id: string; name: string; num: number | null; logo: string | null; quality: Quality | null }[];
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
  const hint = typeof q.hint === "string" && q.hint ? q.hint : null;
  const a = typeof q.audio === "string" ? q.audio.toLowerCase() : "";
  return { bucket: q.bucket as Bucket, height: num(q.height), fps: num(q.fps), hint, audio: a === "atmos" ? "atmos" : a === "5.1" ? "5.1" : a === "stereo" ? "stereo" : null };
}

/** Reads the sport fields off an on-now / guide / channel object (`programme` is where kind and sport live). */
export function parseSport(raw: unknown, programme?: unknown): SportInfo {
  const r = obj(raw) ?? {};
  const p = obj(programme) ?? obj(r.programme) ?? obj(r.now) ?? {};
  const kind = KINDS.includes(p.kind as Kind) ? (p.kind as Kind) : KINDS.includes(r.kind as Kind) ? (r.kind as Kind) : null;
  // `eventGroup` (Channel.event is the PPV-feed flag, a boolean)
  const ev = obj(r.eventGroup);
  const m = obj(r.match);
  return {
    kind,
    sport: p.sport === true || r.sport === true,
    quality: parseQuality(r.quality),
    alternateChannels: (Array.isArray(ev?.alternateChannels) ? (ev!.alternateChannels as unknown[]) : []).flatMap((x) => {
      const c = obj(x);
      const id = str(c?.id);
      return c && id ? [{ id, name: str(c.name) ?? "Another channel", num: num(c.num), logo: str(c.logo), quality: parseQuality(c.quality) }] : [];
    }),
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

export const mayBuffer = (q: Quality | null) => q?.bucket === "slow" || !!q?.hint;

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

export interface ProviderQuality {
  name: string;
  counts: { bucket: Bucket; n: number }[];
  /** Channels checked of all opted channels. */
  progress: { done: number; total: number } | null;
  running: boolean;
  /** Epoch seconds: the first automatic check window / a back-off after the provider refused. */
  autoAfter: number | null;
  backoffUntil: number | null;
  onboarded: boolean;
}

export interface QualitySummary {
  providers: ProviderQuality[];
  /** All providers together. */
  counts: { bucket: Bucket; n: number }[];
  progress: { done: number; total: number } | null;
}

const countsOf = (b: Record<string, unknown>) => BUCKETS.map((bucket) => ({ bucket, n: num(b[bucket]) ?? 0 })).filter((c) => c.n > 0);

/** GET /v1/iptv/quality → {providers:[{name, buckets, progress:{total,probed,fresh}, running, autoAfter, …}]}; summed and per provider. */
export function parseQualitySummary(raw: unknown): QualitySummary | null {
  const r = obj(raw);
  if (!r || !Array.isArray(r.providers)) return null;
  const providers: ProviderQuality[] = [];
  const sum = new Map<Bucket, number>();
  let done = 0;
  let total = 0;
  for (const x of r.providers) {
    const p = obj(x);
    const b = obj(p?.buckets);
    if (!p || !b) continue;
    const counts = countsOf(b);
    counts.forEach((c) => sum.set(c.bucket, (sum.get(c.bucket) ?? 0) + c.n));
    const pr = obj(p.progress);
    const t = num(pr?.total);
    const d = num(pr?.probed);
    if (t != null && d != null) {
      total += t;
      done += d;
    }
    providers.push({
      name: str(p.name) ?? "Provider",
      counts,
      progress: t != null && d != null && t > 0 ? { done: d, total: t } : null,
      running: p.running === true,
      autoAfter: num(p.autoAfter),
      backoffUntil: num(p.backoffUntil),
      onboarded: num(p.onboardedAt) != null,
    });
  }
  if (!providers.length) return null;
  return { providers, counts: BUCKETS.map((bucket) => ({ bucket, n: sum.get(bucket) ?? 0 })).filter((c) => c.n > 0), progress: total > 0 ? { done, total } : null };
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
