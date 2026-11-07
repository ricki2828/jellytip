// Subtitle timing: the per-title nudge (client-side display shift) and the "Auto-match timing" job, pure helpers.
// Contract: POST v1/subs/sync {itemId, streamIndex} -> {jobId} | {cached: result}; GET v1/subs/sync/{jobId} -> {state, progress?, etaSec?, result?, error?}.

export const NUDGE_STEP = 0.5;
export const NUDGE_MAX = 60;
const NUDGE_PREFIX = "jellytip.subNudge.";

type ReadStore = Pick<Storage, "getItem">;
type WriteStore = Pick<Storage, "setItem" | "removeItem">;

export const clampNudge = (n: number) => (Number.isFinite(n) ? Math.min(NUDGE_MAX, Math.max(-NUDGE_MAX, Math.round(n * 10) / 10)) : 0);
/** One nudge step (dir -1 earlier / +1 later) from the current value. */
export const stepNudge = (cur: number, dir: 1 | -1) => clampNudge(cur + dir * NUDGE_STEP);
/** Short stable hash (djb2) so a long identity string makes a compact storage key. */
const hash = (t: string) => {
  let h = 5381;
  for (let i = 0; i < t.length; i++) h = ((h * 33) ^ t.charCodeAt(i)) >>> 0;
  return h.toString(36);
};
export const nudgeKey = (itemId: string, identity: string) => `${NUDGE_PREFIX}${itemId}.${hash(identity)}`;

type IdentStream = { Index: number; Type?: string; Codec?: string; Language?: string; DisplayTitle?: string; Title?: string; Path?: string; IsExternal?: boolean };
const base = (p?: string) => (p ?? "").split(/[\\/]/).pop() ?? "";

/** Who a subtitle track IS, independent of its index (Jellyfin renumbers when an external file is added): kind, language, codec, title,
 * the external file's name, plus a count to tell apart identical-looking tracks. null = no such subtitle. */
export function trackIdentity(streams: readonly IdentStream[], index: number): string | null {
  const subs = streams.filter((s) => s.Type === "Subtitle").sort((a, b) => a.Index - b.Index);
  const key = (s: IdentStream) => [s.IsExternal ? "x" : "e", s.Language ?? "", s.Codec ?? "", s.DisplayTitle ?? s.Title ?? "", s.IsExternal ? base(s.Path) : ""].join("|");
  const cur = subs.find((s) => s.Index === index);
  if (!cur) return null;
  const k = key(cur);
  return `${k}|${subs.filter((s) => key(s) === k).indexOf(cur)}`;
}
export const nudgeLabel = (n: number) => `${n > 0 ? "+" : n < 0 ? "−" : ""}${Math.abs(n).toFixed(1)} s`;

export function loadNudge(itemId: string, identity: string, store?: ReadStore): number {
  try {
    const s = store ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
    const raw = s?.getItem(nudgeKey(itemId, identity));
    return raw == null ? 0 : clampNudge(Number(raw));
  } catch {
    return 0;
  }
}

export function saveNudge(itemId: string, identity: string, value: number, store?: WriteStore): void {
  try {
    const s = store ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
    const v = clampNudge(value);
    if (v === 0) s?.removeItem(nudgeKey(itemId, identity));
    else s?.setItem(nudgeKey(itemId, identity), String(v));
  } catch {
    /* storage blocked: the shift still applies for this session */
  }
}

export interface SyncResult {
  offsetSec: number;
  speed: number;
  confidence: number;
  label: string;
  newStreamIndex: number | null;
  /** the new track's file name (cached hits: the existing track), to find it by name when indices have shifted */
  fileName: string | null;
  alreadySynced: boolean;
}
export type SyncPoll = { state: "queued" | "running"; etaSec: number | null } | { state: "done"; result: SyncResult } | { state: "failed"; error: string };

const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export function parseResult(raw: unknown): SyncResult | null {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : null;
  const offsetSec = num(o?.offsetSec);
  if (!o || offsetSec == null) return null;
  return { offsetSec, speed: num(o.speed) ?? 1, confidence: num(o.confidence) ?? 0, label: typeof o.label === "string" ? o.label : "", newStreamIndex: num(o.newStreamIndex), fileName: typeof o.fileName === "string" && o.fileName ? o.fileName : null, alreadySynced: o.alreadySynced === true };
}

/** POST reply -> jobId to poll, a cached result, or a message to show. */
export function parseStart(status: number, data: unknown): { jobId: string } | { result: SyncResult } | { error: string } {
  const o = data && typeof data === "object" ? (data as Record<string, unknown>) : {};
  if (status === 200 || status === 202) {
    if (typeof o.jobId === "string" && /^[0-9a-f]{12}$/.test(o.jobId)) return { jobId: o.jobId };
    const r = parseResult(o.cached);
    if (r) return { result: r };
  }
  if (status === 429) return { error: "Matching is busy, try again in a minute" };
  if (status === 401) return { error: "Sign in again to match subtitles" };
  if (status === 0 || status === 404 && typeof o.error !== "string") return { error: "Auto-match isn't available right now" };
  return { error: typeof o.error === "string" && o.error ? o.error : "Couldn't match this one" };
}

export function parsePoll(status: number, data: unknown): SyncPoll {
  const o = data && typeof data === "object" ? (data as Record<string, unknown>) : {};
  if (status === 200 && o.state === "done") {
    const r = parseResult(o.result);
    if (r) return { state: "done", result: r };
  }
  if (status === 200 && (o.state === "queued" || o.state === "running")) return { state: o.state, etaSec: num(o.etaSec) };
  if (status === 200 && o.state === "failed") return { state: "failed", error: typeof o.error === "string" && o.error ? o.error : "Couldn't match this one" };
  if (status === 404) return { state: "failed", error: "The match was lost, try again" };
  return { state: "failed", error: "Couldn't check the match" };
}

/** Known frame-rate conversions, by ffsubsync's scale factor. Anything else shows the raw factor. */
const RATES: Array<[number, string]> = [
  [25 / 23.976, "25→23.976 fps"], [23.976 / 25, "23.976→25 fps"], [24 / 23.976, "24→23.976 fps"], [23.976 / 24, "23.976→24 fps"],
  [25 / 24, "25→24 fps"], [24 / 25, "24→25 fps"], [30 / 29.97, "30→29.97 fps"], [29.97 / 30, "29.97→30 fps"],
];
export function speedText(speed: number): string | null {
  if (!Number.isFinite(speed) || Math.abs(speed - 1) < 0.0005) return null;
  const hit = RATES.find(([r]) => Math.abs(speed - r) < 0.0006);
  return `speed ${hit ? hit[1] : `×${speed.toFixed(4)}`}`;
}

/** flashNote text for a finished match, e.g. "Moved +2.4 s · speed 25→23.976 fps". */
export function doneNote(r: SyncResult): string {
  if (r.alreadySynced) return "Already in sync";
  const off = `Moved ${r.offsetSec > 0 ? "+" : r.offsetSec < 0 ? "−" : ""}${Math.abs(r.offsetSec).toFixed(1)} s`;
  const sp = speedText(r.speed);
  return sp ? `${off} · ${sp}` : off;
}

export const progressText = (etaSec: number | null): string => (etaSec != null && etaSec > 0 ? `Matching… about ${Math.max(5, Math.round(etaSec / 5) * 5)} s` : "Matching…");

/** After the server added (or already had) a track: found by its file name when given (works for a cached hit and after Jellyfin renumbered),
 * else by the hinted index if that is new, else the one subtitle that is new since `before`; null = not listed yet. */
export function findNewTrack(before: ReadonlyArray<IdentStream>, after: ReadonlyArray<IdentStream>, hint: number | null, fileName: string | null = null): number | null {
  const subs = after.filter((s) => s.Type === "Subtitle");
  if (fileName) return subs.find((s) => s.IsExternal && base(s.Path) === fileName)?.Index ?? null;
  if (hint != null && subs.some((s) => s.Index === hint) && !before.some((s) => s.Index === hint && s.Type === "Subtitle")) return hint;
  const had = new Set(before.filter((s) => s.Type === "Subtitle").map((s) => s.Index));
  const fresh = subs.filter((s) => !had.has(s.Index));
  return fresh.length ? fresh[fresh.length - 1].Index : null;
}

/** Tracks the server wrote itself: matching one again would only repeat it. */
export const isAutoMatched = (s: { Path?: string }) => /\.automatch\.srt$/i.test(s.Path ?? "");
