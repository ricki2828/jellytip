// IPTV (companion /v1/iptv/*; contract: ~/ai/plans/jellytip-iptv.md). Needs the person's own Jellyfin token; providers
// are private to their owner. Credentials go up once and never come back (the server shows the host only).
import { getSession } from "./jellyfin";
import { COMPANION_BASE } from "./companion";
import { isNative, nativeGetJson, nativePostJson } from "../lib/native";
import { parseVodDetail, type VodDetail } from "../lib/iptvVod";
import { parseOnNow, type OnNow } from "../lib/onNow";
import { parseQualitySummary, parseSuggest, type Quality, type QualitySummary, type Suggestion } from "../lib/sport";

export interface IptvError {
  error?: string;
  code?: string;
}

export interface Answer<T> {
  status: number;
  data: (T & IptvError) | null;
}

async function call<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<Answer<T>> {
  const token = getSession()?.token;
  if (!token) return { status: 401, data: { code: "signed_out", error: "Signed out" } as T & IptvError };
  const url = `${COMPANION_BASE}/${path.replace(/^\//, "")}`;
  const headers = { Authorization: `MediaBrowser Token="${token}"` };
  try {
    if (isNative) {
      const r = method === "GET" ? await nativeGetJson<T & IptvError>(url, headers, 15000) : await nativePostJson<T & IptvError>(url, headers, body ?? {});
      return { status: r.status, data: r.data };
    }
    const res = await fetch(url, {
      method,
      headers: method === "POST" ? { ...headers, "Content-Type": "application/json" } : headers,
      body: method === "POST" ? JSON.stringify(body ?? {}) : undefined,
    });
    return { status: res.status, data: ((await res.json().catch(() => null)) as (T & IptvError) | null) ?? null };
  } catch (e) {
    return { status: 0, data: { code: "network", error: String((e as Error).message ?? e) } as T & IptvError };
  }
}

export const iptvGet = <T>(path: string) => call<T>("GET", path);
export const iptvPost = <T>(path: string, body?: unknown) => call<T>("POST", path, body);

/** Human text for a failed answer. */
export function errorText(a: Answer<unknown>): string {
  if (a.data?.error) return a.data.error;
  if (a.status === 0) return "The server can't be reached.";
  return `Server said ${a.status}`;
}

// ---------- types ----------

export interface Holder {
  kind: "watch" | "recording";
  user?: string;
  userName?: string;
  label?: string;
  since?: number;
}

export interface SyncStage {
  at?: number | null;
  secs?: number | null;
  ok?: boolean | null;
  error?: string | null;
  requests?: number;
  bytes?: number;
  programmes?: number;
  matched?: number;
  none?: number;
  foreign?: number;
  mode?: string;
}

export interface Provider {
  id: number | string;
  name: string;
  kind: "xtream" | "m3u";
  owner: boolean;
  englishOnly: boolean;
  server?: string;
  sharedWith?: string[];
  status: {
    ok?: boolean;
    state?: string;
    expDate?: number | string | null;
    maxConnections?: number | null;
    activeCons?: number | null;
    isTrial?: boolean;
    timezone?: string;
    checkedAt?: number;
    message?: string | null;
    authFailed?: boolean;
  };
  sync: Partial<Record<"lists" | "optin" | "match" | "epg", SyncStage>>;
  syncing: boolean;
  counts: Partial<Record<"channels" | "movies" | "series" | "programmes" | "opted_live" | "opted_vod" | "opted_series", number>>;
  connections?: { max?: number; active?: Holder[] };
}

export type CatType = "live" | "vod" | "series";

export interface Category {
  id: string | number;
  type: CatType;
  name: string;
  count: number;
  opted: boolean;
  lang: "en" | "foreign" | "unknown";
  english: boolean;
}

export interface CategoryList {
  providerId: number | string;
  englishOnly: boolean;
  listsSyncedAt?: number | null;
  categories: Category[];
  totals: Partial<Record<CatType, { categories: number; items: number; optedCategories: number; optedItems: number; englishCategories: number }>>;
}

export interface Preview {
  live?: { categories: number; items: number; englishItems?: number; stored?: number };
  vod?: { categories: number; items: number; englishItems?: number; stored?: { matched?: number; foreign?: number; none?: number; pending?: number }; inLibrary?: number };
  series?: { categories: number; items: number; englishItems?: number; stored?: { matched?: number; foreign?: number; none?: number; pending?: number } };
  epgEstimate?: { channels: number; programmes: number; requestsPerRefresh: number };
  englishOnly?: boolean;
  willFetch?: unknown;
}

export interface Recording {
  id: number | string;
  channelId: string;
  channel: string;
  title: string;
  desc?: string;
  start: number;
  stop: number;
  state: "scheduled" | "recording" | "done" | "failed" | "cancelled";
  bytes?: number;
  error?: string | null;
  watched?: boolean;
  playable?: boolean;
  deleteAt?: number | null;
  progress?: { position: number; duration: number; played?: boolean } | null;
}

export interface Space {
  usedGb: number;
  capGb: number;
  freeGb?: number;
  minFreeGb?: number;
  canRecord: boolean;
  reason?: string | null;
}

export interface JfUser {
  id: string;
  name: string;
}

// ---------- calls ----------

export const providers = () => iptvGet<{ providers: Provider[] }>("v1/iptv/providers");
export const provider = (id: Provider["id"]) => iptvGet<Provider>(`v1/iptv/providers/${id}`);
export const addProvider = (body: Record<string, unknown>) => iptvPost<Provider>("v1/iptv/providers", body);
export const editProvider = (id: Provider["id"], body: Record<string, unknown>) => iptvPost<Provider>(`v1/iptv/providers/${id}`, body);
export const deleteProvider = (id: Provider["id"]) => iptvPost<{ ok: boolean }>(`v1/iptv/providers/${id}/delete`);
export const refreshProvider = (id: Provider["id"], what: "all" | "lists" | "categories" | "epg" | "match" = "all") =>
  iptvPost<{ started: string[] }>(`v1/iptv/providers/${id}/refresh`, { what });
export const categories = (id: Provider["id"], type: CatType, q = "") =>
  iptvGet<CategoryList>(`v1/iptv/providers/${id}/categories?type=${type}${q ? `&q=${encodeURIComponent(q)}` : ""}`);
export const optCategories = (id: Provider["id"], body: { type: CatType; opt?: Record<string, boolean>; all?: boolean; englishOnly?: boolean }) =>
  iptvPost<Preview>(`v1/iptv/providers/${id}/categories`, body);
export const preview = (id: Provider["id"]) => iptvGet<Preview>(`v1/iptv/providers/${id}/preview`);
export const users = () => iptvGet<{ users: JfUser[] }>("v1/iptv/users");
export const recordings = () => iptvGet<{ space: Space; recordings: Recording[] }>("v1/iptv/recordings");
export const cancelRecording = (id: Recording["id"]) => iptvPost<{ ok: boolean }>(`v1/iptv/recordings/${id}/cancel`);
export const deleteRecording = (id: Recording["id"]) => iptvPost<{ ok: boolean }>(`v1/iptv/recordings/${id}/delete`);

// ---------- live TV ----------

export interface LiveGroup {
  id: string;
  providerId?: number | string | null;
  providerName?: string;
  name: string;
  count?: number;
  /** The "Guide" view: every opted channel in group order, dark event feeds hidden. */
  default?: boolean;
  region?: string | null;
  events?: number;
  darkCount?: number;
  /** Admin-only "Hidden: not working" view. */
  hidden?: boolean;
  deadCount?: number;
}

export interface Channel {
  id: string;
  providerId: number | string;
  providerName?: string;
  streamId: string;
  num?: number | null;
  name: string;
  logo?: string | null;
  group: string;
  epgId?: string | null;
  catchupDays: number;
  favourite: boolean;
  /** PPV / event feed. */
  event?: boolean;
  guide?: "epg" | "name" | "event" | "none";
  guideNote?: string | null;
  region?: string | null;
  /** Stream check result (live-sport smarts); missing until the server has probed it. */
  quality?: (Quality & { checkedAt?: number }) | null;
  /** Favourite team / league playing on this channel now. */
  match?: { team?: string | null; league?: string | null } | null;
}

export interface Connection {
  max?: number;
  inUse?: number;
  active?: Holder[];
}

export interface PlayAnswer {
  url: string;
  mime?: string;
  kind: "live" | "cu" | "movie" | "ep" | "recording";
  expires?: number;
  growing?: boolean;
  connection?: Connection;
}

export const liveGroups = () => iptvGet<{ groups: LiveGroup[]; defaultGroup?: string }>("v1/iptv/live/groups");
export const liveChannels = (group: string, offset = 0, limit = 500) =>
  iptvGet<{ total: number; offset: number; channels: Channel[] }>(`v1/iptv/live/channels?group=${encodeURIComponent(group)}&offset=${offset}&limit=${limit}`);
export const epg = (channels: string[], from: number, to: number) =>
  iptvGet<{ from: number; to: number; now: number; channels: Record<string, import("../lib/epg").Prog[]> }>(
    `v1/iptv/epg?channels=${channels.map(encodeURIComponent).join(",")}&from=${from}&to=${to}`,
  );
export const setFavourite = (channel: string, on: boolean) => iptvPost<{ ok: boolean }>("v1/iptv/favourites", { channel, on });
export const play = (query: string) => iptvGet<PlayAnswer & { connection?: Connection }>(query);
export const addRecording = (channel: string, programme: number) =>
  iptvPost<{ recording: Recording; warning: string | null }>("v1/iptv/recordings", { channel, programme });
export const saveProgress = (item: string, position: number, duration: number) => iptvPost<{ ok: boolean }>("v1/iptv/progress", { item, position, duration });
/** Absolute address of a stream link (links are relative to the companion base). */
export const streamUrl = (rel: string) => `${COMPANION_BASE}/${rel.replace(/^\//, "")}`;

// ---------- "Live TV" in the nav: only when the person has opted-in channels ----------

let liveCheck: Promise<boolean> | null = null;
/** True when some provider the person can use has opted-in live channels (asked once; reset by iptvChanged()). */
export function hasLiveTv(): Promise<boolean> {
  return (liveCheck ??= liveGroups().then((a) => a.status === 200 && !!a.data?.groups.some((g) => g.id !== "fav" && !g.default && (g.count ?? 0) > 0)));
}
/** Settings changed what's opted in: ask again and tell the nav. */
export function iptvChanged() {
  liveCheck = null;
  window.dispatchEvent(new Event("jellytip-iptv"));
}

/** Channel logo / provider poster the page may load: only companion-proxied (relative `v1/iptv/img/…`) paths; raw
 * provider URLs are refused by the CSP and would hand the viewer's address to the panel. */
export const logoSrc = (logo?: string | null, size?: 96 | 192) =>
  logo && !/^[a-z][a-z0-9+.-]*:/i.test(logo) && !logo.startsWith("//") ? streamUrl(size ? `${logo}${logo.includes("?") ? "&" : "?"}s=${size}` : logo) : null;

// ---------- IPTV films / series ----------

export async function vodItem(id: string): Promise<Answer<VodDetail>> {
  const a = await iptvGet<unknown>(`v1/iptv/vod/${encodeURIComponent(id)}`);
  if (a.status !== 200) return a as Answer<VodDetail>;
  const d = parseVodDetail(a.data);
  return d ? { status: 200, data: d } : { status: 502, data: { error: "The server sent something unexpected." } as VodDetail & IptvError };
}

/** Add or remove an IPTV film / series from My list; the answer says where it stands, status 404 = the server has no My list. */
export const vodFavourite = (id: string, on: boolean) => iptvPost<{ favourite?: boolean }>(`v1/iptv/vod/${encodeURIComponent(id)}/favourite`, { on });

/** Channels with something on now (live sport first); [] when none or IPTV isn't set up. */
export async function onNow(limit = 40, sportOnly = false): Promise<OnNow[]> {
  const a = await iptvGet<unknown>(`v1/iptv/live/onnow?limit=${limit}${sportOnly ? "&sport=1" : ""}`);
  if (a.status !== 200) return [];
  return parseOnNow(a.data, Math.floor(Date.now() / 1000));
}

// ---------- live sport (contract: ~/ai/plans/jellytip-live-sport.md) ----------

/** Team and league names matching what was typed; [] when the server has no sport routes. */
export async function sportSuggest(q: string): Promise<Suggestion[]> {
  const a = await iptvGet<unknown>(`v1/iptv/sport/suggest?q=${encodeURIComponent(q)}`);
  return a.status === 200 ? parseSuggest(a.data) : [];
}

/** Counts per quality bucket and the onboarding pass; null when the server doesn't have them. */
export async function qualitySummary(): Promise<QualitySummary | null> {
  const a = await iptvGet<unknown>("v1/iptv/quality");
  return a.status === 200 ? parseQualitySummary(a.data) : null;
}

/** Admin: check every opted channel now (the server only probes when nobody is watching). */
export const probeNow = () => iptvPost<{ queued?: number }>("v1/iptv/quality/probe", {});
