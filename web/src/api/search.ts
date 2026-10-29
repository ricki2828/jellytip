// Unified search on the companion (contract "Search API", ~/ai/plans/jellytip-10x.md). Fails soft: `null` result =
// the route isn't there (older server) or failed, and the Search screen falls back to plain Jellyfin search.
import { CapacitorHttp } from "@capacitor/core";
import { getSession } from "./jellyfin";
import { COMPANION_BASE } from "./companion";
import { isNative } from "../lib/native";
import { parseSearch, type SearchResult } from "../lib/search";
import { registerLogoStyles } from "../lib/logoStyle";
import { registerTechs } from "../lib/techBadges";

type Method = "GET" | "POST" | "DELETE";

const TYPES = "films,shows,people,collections,lists,iptv,discover";

async function call(method: Method, path: string, body?: unknown, signal?: AbortSignal): Promise<{ status: number; data: unknown }> {
  const token = getSession()?.token;
  if (!token) return { status: 401, data: null };
  const url = `${COMPANION_BASE}/${path}`;
  const headers: Record<string, string> = { Authorization: `MediaBrowser Token="${token}"` };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  try {
    if (isNative) {
      // The companion sends no CORS headers for JSON routes: native HTTP in the app
      const r = await CapacitorHttp.request({ url, method, headers, data: body, responseType: "json", connectTimeout: 8000, readTimeout: 8000 });
      return { status: r.status, data: typeof r.data === "string" ? null : r.data };
    }
    const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal });
    return { status: res.status, data: await res.json().catch(() => null) };
  } catch {
    return { status: 0, data: null };
  }
}

// A server without the route (404) isn't asked again for a while: every keystroke would pay for the miss
const RETRY_MS = 5 * 60_000;
let missingUntil = 0;

/** Grouped results, or null when unified search isn't available (fall back to Jellyfin search). */
export async function searchAll(q: string, signal?: AbortSignal): Promise<SearchResult | null> {
  if (Date.now() < missingUntil) return null;
  const a = await call("GET", `v1/search?q=${encodeURIComponent(q)}&limit=8&types=${TYPES}`, undefined, signal);
  if (a.status === 404) missingUntil = Date.now() + RETRY_MS;
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
export const clearRecent = () => call("DELETE", "v1/search/recent");
