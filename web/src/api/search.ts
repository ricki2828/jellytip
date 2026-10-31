// Unified search on the companion (contract "Search API", ~/ai/plans/jellytip-10x.md). Fails soft: `null` result =
// the route isn't there (older server) or failed, and the Search screen falls back to plain Jellyfin search.
import { companionCall as call } from "./companionCall";
import { parseSearch, type SearchEntry, type SearchResult } from "../lib/search";
import { registerLogoStyles } from "../lib/logoStyle";
import { registerTechs } from "../lib/techBadges";

const TYPES = "films,shows,people,collections,lists,iptv,discover,live,channels";

// A server without the route (404) isn't asked again for a while: every keystroke would pay for the miss
const RETRY_MS = 5 * 60_000;
let missingUntil = 0;

/**
 * Grouped results; null when unified search isn't available (fall back to Jellyfin search); "blocked" when the server
 * couldn't identify the person (503) — then nothing unfiltered may be shown.
 */
export async function searchAll(q: string, signal?: AbortSignal, types = TYPES): Promise<SearchResult | null | "blocked"> {
  if (Date.now() < missingUntil) return null;
  const a = await call("GET", `v1/search?q=${encodeURIComponent(q)}&limit=8&types=${types}`, undefined, { signal });
  if (a.status === 404) missingUntil = Date.now() + RETRY_MS;
  if (a.status === 503) return "blocked";
  if (a.status !== 200) return null;
  const r = parseSearch(a.data);
  if (r) {
    registerTechs(r.techs);
    registerLogoStyles(r.logoStyles);
  }
  return r;
}

export interface Suggestions {
  recent: string[];
  suggested: string[];
}

export async function suggestions(): Promise<Suggestions | null> {
  const a = await call("GET", "v1/search/suggest");
  if (a.status !== 200 || !a.data || typeof a.data !== "object") return null;
  const d = a.data as { recent?: unknown; suggested?: unknown };
  const strs = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && !!x.trim()).slice(0, 12) : []);
  return { recent: strs(d.recent), suggested: strs(d.suggested) };
}

/** A result was opened: remember the query (fire and forget). */
export const recordRecent = (q: string) => void (q.trim().length >= 2 && call("POST", "v1/search/recent", { q: q.trim() }));
/** The companion has no DELETE: `{clear: true}` empties the list. */
export const clearRecent = () => call("POST", "v1/search/recent", { clear: true });

/** A ranked result was opened (fire and forget; never blocks navigation). `pos` is 1-based, the top hit being 1. */
export function recordClick(q: string, e: SearchEntry, pos: number) {
  const id = e.kind === "person" ? e.personId : e.kind === "collection" ? e.tmdbCollectionId : e.source === "library" ? e.id : e.source === "iptv" ? e.iptvId : e.tmdbId;
  if (!q.trim() || id == null || pos < 1) return;
  void call("POST", "v1/search/click", { q: q.trim(), kind: e.kind, id, pos, source: e.source, ...(e.tmdbId != null ? { tmdbId: e.tmdbId } : {}) }).catch(() => undefined);
}
