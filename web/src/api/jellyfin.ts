// Jellyfin REST client. The token is only ever sent to the Jellyfin server and its companion API (same origin on the
// website; the seedbox origin in the Android app).

import { SERVER_ORIGIN } from "../lib/native";

export const JF_BASE = `${SERVER_ORIGIN}/jellyfin`;
const CLIENT = "Jellytip Web";
const VERSION = "0.1.0";
const STORE_KEY = "jellytip.session";
const DEVICE_KEY = "jellytip.deviceId";

export interface Session {
  token: string;
  userId: string;
  userName?: string;
  isAdmin?: boolean;
}

export interface UserData {
  PlaybackPositionTicks?: number;
  PlayedPercentage?: number;
  Played?: boolean;
  IsFavorite?: boolean;
  UnplayedItemCount?: number;
  LastPlayedDate?: string;
}

export interface Person {
  Id: string;
  Name: string;
  Role?: string;
  Type?: string;
  PrimaryImageTag?: string;
}

export interface MediaStream {
  Index: number;
  Type: "Video" | "Audio" | "Subtitle" | "EmbeddedImage" | string;
  Codec?: string;
  Language?: string;
  DisplayTitle?: string;
  Title?: string;
  IsDefault?: boolean;
  IsForced?: boolean;
  IsExternal?: boolean;
  IsTextSubtitleStream?: boolean;
  Width?: number;
  Height?: number;
  Channels?: number;
  VideoRangeType?: string;
  DeliveryMethod?: string;
  DeliveryUrl?: string;
}

export interface MediaSource {
  Id: string;
  Container?: string;
  Path?: string;
  ETag?: string;
  SupportsDirectPlay?: boolean;
  SupportsDirectStream?: boolean;
  SupportsTranscoding?: boolean;
  TranscodingUrl?: string;
  MediaStreams?: MediaStream[];
  DefaultAudioStreamIndex?: number;
  DefaultSubtitleStreamIndex?: number;
  RunTimeTicks?: number;
}

export interface Item {
  Id: string;
  Name: string;
  Type: string;
  SeriesId?: string;
  SeriesName?: string;
  SeasonId?: string;
  SeasonName?: string;
  ParentId?: string;
  IndexNumber?: number;
  ParentIndexNumber?: number;
  ProductionYear?: number;
  PremiereDate?: string;
  OfficialRating?: string;
  CommunityRating?: number;
  RunTimeTicks?: number;
  Overview?: string;
  Genres?: string[];
  ProductionLocations?: string[];
  People?: Person[];
  ProviderIds?: Record<string, string>;
  ImageTags?: Record<string, string>;
  BackdropImageTags?: string[];
  ParentBackdropItemId?: string;
  ParentBackdropImageTags?: string[];
  ParentLogoItemId?: string;
  ParentLogoImageTag?: string;
  ParentThumbItemId?: string;
  ParentThumbImageTag?: string;
  SeriesPrimaryImageTag?: string;
  UserData?: UserData;
  MediaSources?: MediaSource[];
  ChildCount?: number;
  LocalTrailerCount?: number;
  Status?: string;
  EndDate?: string;
}

export interface ItemsResult {
  Items: Item[];
  TotalRecordCount?: number;
}

export function deviceId(): string {
  let id = localStorage.getItem(DEVICE_KEY);
  if (!id) {
    id = (crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`).replace(/-/g, "");
    localStorage.setItem(DEVICE_KEY, id);
  }
  return id;
}

function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}

let session: Session | null = loadSession();

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    const s = raw ? (JSON.parse(raw) as Session) : null;
    return s?.token && s?.userId ? s : null;
  } catch {
    return null;
  }
}

export function getSession(): Session | null {
  return session;
}

export function setSession(s: Session | null) {
  session = s;
  if (s) localStorage.setItem(STORE_KEY, JSON.stringify(s));
  else localStorage.removeItem(STORE_KEY);
  window.dispatchEvent(new Event("jellytip-session"));
}

export function authHeader(token = session?.token): string {
  const parts = [`Client="${CLIENT}"`, `Device="${deviceName()}"`, `DeviceId="${deviceId()}"`, `Version="${VERSION}"`];
  if (token) parts.push(`Token="${token}"`);
  return `MediaBrowser ${parts.join(", ")}`;
}

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function jf<T = unknown>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", authHeader());
  let body = init.body;
  if (init.json !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(init.json);
  }
  const res = await fetch(JF_BASE + path, { ...init, headers, body });
  if (res.status === 401 && session) {
    setSession(null); // token revoked: back to login
  }
  if (!res.ok) throw new HttpError(res.status, `${res.status} ${path.split("?")[0]}`);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") u.set(k, String(v));
  const s = u.toString();
  return s ? `?${s}` : "";
}

export function uid(): string {
  return session?.userId ?? "";
}

// ---------- auth ----------

interface AuthResult {
  AccessToken: string;
  User: { Id: string; Name: string; Policy?: { IsAdministrator?: boolean } };
}

function storeAuth(r: AuthResult) {
  setSession({ token: r.AccessToken, userId: r.User.Id, userName: r.User.Name, isAdmin: !!r.User.Policy?.IsAdministrator });
}

export async function login(username: string, password: string) {
  storeAuth(await jf<AuthResult>("/Users/AuthenticateByName", { method: "POST", json: { Username: username, Pw: password } }));
}

export async function quickConnectEnabled(): Promise<boolean> {
  try {
    return await jf<boolean>("/QuickConnect/Enabled");
  } catch {
    return false;
  }
}

export async function quickConnectInitiate(): Promise<{ Secret: string; Code: string }> {
  return jf("/QuickConnect/Initiate", { method: "POST" });
}

/** True once the code was approved on another device; then exchanges the secret for a session. */
export async function quickConnectPoll(secret: string): Promise<boolean> {
  const st = await jf<{ Authenticated: boolean }>(`/QuickConnect/Connect${qs({ Secret: secret })}`);
  if (!st.Authenticated) return false;
  storeAuth(await jf<AuthResult>("/Users/AuthenticateWithQuickConnect", { method: "POST", json: { Secret: secret } }));
  return true;
}

export async function logout() {
  try {
    await jf("/Sessions/Logout", { method: "POST" });
  } catch {
    /* token may already be gone */
  }
  setSession(null);
}

// ---------- items ----------

export const LIST_FIELDS = "Overview,Genres,ProviderIds,PrimaryImageAspectRatio";
export const DETAIL_FIELDS = "Overview,Genres,People,ProviderIds,MediaSources,ProductionLocations,Studios,Taglines,ChildCount";

export function getItem(id: string): Promise<Item> {
  return jf<Item>(`/Users/${uid()}/Items/${id}${qs({ Fields: DETAIL_FIELDS })}`);
}

export function getItems(params: Record<string, string | number | boolean | undefined>): Promise<ItemsResult> {
  return jf<ItemsResult>(`/Users/${uid()}/Items${qs({ Fields: LIST_FIELDS, EnableUserData: true, ...params })}`);
}

export async function resumeItems(): Promise<Item[]> {
  const r = await jf<ItemsResult>(`/Users/${uid()}/Items/Resume${qs({ Limit: 20, MediaTypes: "Video", Fields: LIST_FIELDS, EnableUserData: true })}`);
  return r.Items;
}

export async function nextUp(): Promise<Item[]> {
  const r = await jf<ItemsResult>(`/Shows/NextUp${qs({ UserId: uid(), Limit: 20, Fields: LIST_FIELDS, EnableUserData: true })}`);
  return r.Items;
}

export async function similar(id: string): Promise<Item[]> {
  const r = await jf<ItemsResult>(`/Items/${id}/Similar${qs({ UserId: uid(), Limit: 16, Fields: LIST_FIELDS })}`);
  return r.Items;
}

export async function seasons(seriesId: string): Promise<Item[]> {
  const r = await jf<ItemsResult>(`/Shows/${seriesId}/Seasons${qs({ UserId: uid(), EnableUserData: true })}`);
  return r.Items;
}

export async function episodes(seriesId: string, seasonId?: string): Promise<Item[]> {
  const r = await jf<ItemsResult>(
    `/Shows/${seriesId}/Episodes${qs({ UserId: uid(), SeasonId: seasonId, Fields: "Overview,MediaSources", EnableUserData: true })}`,
  );
  return r.Items;
}

export async function localTrailers(id: string): Promise<Item[]> {
  try {
    return await jf<Item[]>(`/Users/${uid()}/Items/${id}/LocalTrailers`);
  } catch {
    return [];
  }
}

export async function search(term: string): Promise<Item[]> {
  const r = await getItems({ SearchTerm: term, IncludeItemTypes: "Movie,Series,Episode", Recursive: true, Limit: 60 });
  return r.Items;
}

export async function setPlayed(id: string, played: boolean) {
  await jf(`/Users/${uid()}/PlayedItems/${id}`, { method: played ? "POST" : "DELETE" });
}

export async function setFavorite(id: string, fav: boolean) {
  await jf(`/Users/${uid()}/FavoriteItems/${id}`, { method: fav ? "POST" : "DELETE" });
}

// ---------- images ----------

export interface ImageOpts {
  maxWidth?: number;
  maxHeight?: number;
  fillWidth?: number;
  fillHeight?: number;
  tag?: string;
  index?: number;
  quality?: number;
}

export function imageUrl(id: string, type: string, o: ImageOpts = {}): string {
  const path = o.index !== undefined ? `/Items/${id}/Images/${type}/${o.index}` : `/Items/${id}/Images/${type}`;
  return JF_BASE + path + qs({ tag: o.tag, maxWidth: o.maxWidth, maxHeight: o.maxHeight, fillWidth: o.fillWidth, fillHeight: o.fillHeight, quality: o.quality ?? 90 });
}

export function personImage(p: Person, height = 360): string | null {
  return p.PrimaryImageTag ? imageUrl(p.Id, "Primary", { tag: p.PrimaryImageTag, fillHeight: height, fillWidth: Math.round(height * 0.75) }) : null;
}

export function logoUrl(item: Item, maxWidth = 760): string | null {
  if (item.ImageTags?.Logo) return imageUrl(item.Id, "Logo", { tag: item.ImageTags.Logo, maxWidth });
  if (item.ParentLogoItemId && item.ParentLogoImageTag) return imageUrl(item.ParentLogoItemId, "Logo", { tag: item.ParentLogoImageTag, maxWidth });
  return null;
}

export const TICKS_PER_SECOND = 10_000_000;

export function runtimeText(ticks?: number): string | null {
  if (!ticks) return null;
  const min = Math.round(ticks / TICKS_PER_SECOND / 60);
  if (min < 60) return `${min}m`;
  return `${Math.floor(min / 60)}h ${min % 60}m`;
}
