// ONE answer to "how can this title be watched?" for every surface (pure; tested in web/test/availability.test.ts).
// Search tiles, the season list and the Details "Ways to watch" section all take their words from here.
// Only data the client already holds is used: nothing is fetched, language/quality are never guessed.
import type { Item, MediaSource } from "../api/jellyfin.ts";
import { vodTech } from "./iptvVod.ts";
import { detailBadges, techOf } from "./techBadges.ts";

export type WayKind = "library" | "downloaded" | "iptv" | "request" | "unavailable";
export type SourceKind = "library" | "iptv" | "discover";

// ---------- wording (kept exactly as the surfaces had it before) ----------
export type Avail = "Library" | "IPTV" | "Not available";
/** Search tile tag / hero badge word for a result's source. */
export const sourceLabel = (source: SourceKind): Avail => (source === "library" ? "Library" : source === "iptv" ? "IPTV" : "Not available");
/** Season list reason for an episode with no file. */
export const EP_VIA_IPTV = "Available via IPTV";
export const EP_NOT_IN_LIBRARY = "Not in your library";
export const episodeReason = (hasIptv: boolean) => (hasIptv ? EP_VIA_IPTV : EP_NOT_IN_LIBRARY);
/** What an IPTV copy says about its language when the companion does not (it carries none today). */
export const LANGUAGE_UNCONFIRMED = "Language not confirmed";

/** Provider id of a `pid:s:sid` / `pid:m:mid` / `pid:e:eid` IPTV id. */
export const providerOf = (id: string): string => id.split(":")[0] ?? "";

// ---------- types ----------
export interface IptvCopy {
  id: string;
  providerName?: string | null;
  quality?: string[];
  tech?: string[];
  /** Only when the companion said so; absent = "Language not confirmed". */
  language?: string | null;
  /** Companion resolution word (4k | fhd | hd | sd) for copies whose provider tokens say nothing. */
  res?: string | null;
  /** False = the copy sits outside the person's switched-on IPTV groups (found in the whole provider catalogue). */
  opted?: boolean;
}

export interface AvailContext {
  /** Default true. Offline, a downloaded copy comes first. */
  online?: boolean;
  /** A finished offline copy of the library item is on this device. */
  downloaded?: boolean;
  /** IPTV copies already loaded for this title. */
  iptv?: readonly IptvCopy[];
  /** The IPTV copies arrive ranked by the companion (failures, language, quality): keep that order instead of sorting by quality. */
  iptvRanked?: boolean;
  /** The person's own + shared provider ids; copies from any other provider are dropped (session-scoped ownership). */
  allowedProviderIds?: Iterable<string | number>;
  /** The IPTV lookup has finished (so "not on IPTV" may be stated). */
  iptvChecked?: boolean;
  /** The existing Get it / Request flow is open to this person for this title. */
  requestable?: boolean;
  /** Server request state (Discover): none | requested | downloading | in_library. */
  requestState?: string | null;
  /** Server-confirmed formats for the library item; defaults to techOf(item.Id). */
  tech?: readonly string[];
}

export interface Version {
  label: string;
  quality: string[];
}

export interface Way {
  kind: WayKind;
  key: string;
  /** Short row title: "In your library", "Downloaded on this device", "IPTV · Provider", "Request this title", "Not available". */
  title: string;
  /** Quality tokens, best first (4K, 1080p, Dolby Vision, Dolby Atmos …). */
  quality: string[];
  /** Honest extra line (reason, request state, versions). */
  detail: string | null;
  /** IPTV only. */
  language?: string;
  itemId?: string;
  iptvId?: string;
  /** Library only, when the item has several files (best first). */
  versions?: Version[];
  /** Small tile/row badge word. */
  badge: "Library" | "Downloaded" | "IPTV" | "Request" | null;
}

// ---------- quality ----------
const RES_SCORE: Record<string, number> = { "4K": 4, "1080p": 3, "720p": 2, SD: 1 };

function resolution(width: number | undefined): string | null {
  if (!width) return null;
  return width >= 3200 ? "4K" : width >= 1800 ? "1080p" : width >= 1200 ? "720p" : "SD";
}

/** Quality of one file; `tech` (server-confirmed formats) only augments the primary file. */
export function sourceQuality(ms: MediaSource, tech: readonly string[] = []): string[] {
  const v = ms.MediaStreams?.find((s) => s.Type === "Video");
  const out: string[] = [];
  let res = resolution(v?.Width);
  if (!res && tech.includes("4K")) res = "4K";
  if (res) out.push(res);
  if (tech.length) {
    for (const b of detailBadges(tech)) if (b !== "4K" && !out.includes(b)) out.push(b);
  } else if (v?.VideoRangeType && v.VideoRangeType !== "SDR") out.push(v.VideoRangeType.startsWith("DOVI") ? "Dolby Vision" : "HDR");
  return out;
}

const score = (q: readonly string[]) => (RES_SCORE[q[0] ?? ""] ?? 0) * 10 + (q.some((b) => b === "Dolby Vision" || b === "HDR" || b === "HDR10+") ? 1 : 0);

/** IPTV copies only carry provider tokens ("4k", "hdr", maybe "1080p"); map them to the same words, nothing invented. */
export function iptvQuality(c: Pick<IptvCopy, "quality" | "tech" | "res">): string[] {
  const toks = new Set((c.quality ?? []).map((x) => x.toLowerCase()));
  const t = vodTech({ tech: c.tech, quality: c.quality });
  const out: string[] = [];
  if (t.includes("4K") || toks.has("4k") || toks.has("uhd")) out.push("4K");
  else if (toks.has("1080p") || toks.has("fhd")) out.push("1080p");
  else if (toks.has("720p") || toks.has("hd")) out.push("720p");
  if (!out.length && c.res) out.push(RES_WORD[c.res] ?? c.res);
  for (const b of detailBadges(t)) if (b !== "4K" && !out.includes(b)) out.push(b);
  return out;
}
const RES_WORD: Record<string, string> = { "4k": "4K", fhd: "1080p", hd: "720p", sd: "SD" };
export const OUTSIDE_GROUPS = "Outside your switched-on groups";

// ---------- the function ----------
/**
 * Every way to watch `item` (the Jellyfin item, or null when the title is not in the library), best first:
 * online  = library (best file) > IPTV (best quality first) > downloaded > request
 * offline = downloaded > library > IPTV > request
 * Nothing at all = a single "unavailable" way carrying an honest reason.
 */
export function availabilityOf(item: Item | null | undefined, ctx: AvailContext = {}): Way[] {
  const online = ctx.online !== false;
  const ways: Way[] = [];
  const inLibrary = !!item && item.LocationType !== "Virtual";

  let library: Way | null = null;
  if (inLibrary && item) {
    const tech = ctx.tech ?? techOf(item.Id);
    const srcs = (item.MediaSources ?? []).map((ms, i) => ({ name: (ms as MediaSource & { Name?: string }).Name?.trim() || `Version ${i + 1}`, q: sourceQuality(ms, i === 0 ? tech : []) }));
    const sorted = srcs.map((s, i) => ({ ...s, i })).sort((a, b) => score(b.q) - score(a.q) || a.i - b.i);
    const best = sorted[0];
    library = {
      kind: "library",
      key: `library:${item.Id}`,
      title: "In your library",
      quality: best?.q ?? (tech.length ? detailBadges(tech) : []),
      detail: sorted.length > 1 ? `${sorted.length} versions: ${sorted.map((s) => s.q[0] ?? s.name).join(", ")}` : null,
      itemId: item.Id,
      ...(sorted.length > 1 ? { versions: sorted.map((s) => ({ label: s.name, quality: s.q })) } : {}),
      badge: "Library",
    };
  }

  const allowed = ctx.allowedProviderIds ? new Set([...ctx.allowedProviderIds].map(String)) : null;
  const seen = new Set<string>();
  const iptv: Way[] = [];
  for (const c of ctx.iptv ?? []) {
    if (seen.has(c.id) || (allowed && !allowed.has(providerOf(c.id)))) continue;
    seen.add(c.id);
    const lang = c.language?.trim();
    iptv.push({
      kind: "iptv",
      key: `iptv:${c.id}`,
      title: c.providerName ? `IPTV · ${c.providerName}` : "IPTV",
      quality: iptvQuality(c),
      detail: c.opted === false ? OUTSIDE_GROUPS : null,
      language: lang || LANGUAGE_UNCONFIRMED,
      iptvId: c.id,
      badge: "IPTV",
    });
  }
  if (!ctx.iptvRanked) iptv.sort((a, b) => score(b.quality) - score(a.quality)); // stable: equal quality keeps the server's order

  const downloaded: Way | null =
    inLibrary && item && ctx.downloaded
      ? { kind: "downloaded", key: `downloaded:${item.Id}`, title: "Downloaded on this device", quality: [], detail: online ? null : "Plays without a connection", itemId: item.Id, badge: "Downloaded" }
      : null;

  if (!online && downloaded) ways.push(downloaded);
  if (library) ways.push(library);
  ways.push(...iptv);
  if (online && downloaded) ways.push(downloaded);

  const state = ctx.requestState ?? null;
  if (!inLibrary && state !== "in_library" && (ctx.requestable || state === "requested" || state === "downloading")) {
    ways.push({
      kind: "request",
      key: "request",
      title: state === "requested" ? "Requested" : state === "downloading" ? "Downloading" : "Request this title",
      quality: [],
      detail: state === "requested" || state === "downloading" ? "Already on its way" : null,
      badge: "Request",
    });
  } else if (inLibrary && ctx.requestable) {
    ways.push({ kind: "request", key: "request", title: "Request what's missing", quality: [], detail: null, badge: "Request" });
  }

  if (!ways.length) {
    ways.push({
      kind: "unavailable",
      key: "unavailable",
      title: "Not available",
      quality: [],
      detail: ctx.iptvChecked ? "Not in your library or on your IPTV providers" : "Not in your library",
      badge: null,
    });
  }
  return ways;
}

/** The single tile word for a set of ways: Library, Downloaded (offline only), IPTV, Request, or null. */
export const primaryBadge = (ways: readonly Way[]): Way["badge"] => ways.find((w) => w.badge)?.badge ?? null;

/** True when there is something to press play on. */
export const isWatchable = (ways: readonly Way[]) => ways.some((w) => w.kind === "library" || w.kind === "downloaded" || w.kind === "iptv");
