// Runtime differences between the website (same origin as Jellyfin) and the Android app (bundled in a Capacitor
// WebView at https://localhost, talking to the seedbox across origins).
import { Capacitor, CapacitorHttp, registerPlugin } from "@capacitor/core";

export const isNative = Capacitor.isNativePlatform();

/** "" on the website (same origin); the seedbox origin in the app (VITE_SERVER_ORIGIN, see .env.android). */
export const SERVER_ORIGIN: string = isNative ? (import.meta.env.VITE_SERVER_ORIGIN ?? "") : "";

/**
 * GET JSON through native HTTP (no CORS): the companion API sends no CORS headers, so the WebView can't call it
 * directly. Jellyfin itself allows any origin and keeps using fetch.
 */
export async function nativeGetJson<T>(url: string, headers: Record<string, string>, timeoutMs: number): Promise<{ status: number; data: T | null }> {
  const res = await CapacitorHttp.get({ url, headers, connectTimeout: timeoutMs, readTimeout: timeoutMs, responseType: "json" });
  let data = res.data as T | string | null;
  if (typeof data === "string") {
    try {
      data = JSON.parse(data) as T;
    } catch {
      data = null;
    }
  }
  return { status: res.status, data: data as T | null };
}

export async function nativePostJson<T>(url: string, headers: Record<string, string>, body: unknown): Promise<{ status: number; data: T | null }> {
  const res = await CapacitorHttp.post({ url, headers: { ...headers, "Content-Type": "application/json" }, data: body, responseType: "json" });
  return { status: res.status, data: (typeof res.data === "string" ? null : res.data) as T | null };
}

/** WebView URL for a local file (served by Capacitor's local server, with range support for <video>). */
export const localFileUrl = (path: string) => Capacitor.convertFileSrc(`file://${path}`);

export interface NativeDownloadStatus {
  downloadId: number;
  status: "pending" | "running" | "paused" | "done" | "failed";
  bytesDone: number;
  bytesTotal: number;
  reason: number;
}

/** android/app/src/main/java/com/jellytip/phone/DownloadsPlugin.java */
export interface JellytipDownloadsPlugin {
  query(o: { ids: number[] }): Promise<{ items: NativeDownloadStatus[] }>;
  remove(o: { ids: number[]; paths: string[] }): Promise<void>;
  storage(): Promise<{ usedBytes: number; freeBytes: number; dir: string }>;
  exists(o: { path: string }): Promise<{ exists: boolean; bytes: number }>;
  /** "Downloads at once": replace the waiting files (in order) and the limit; native starts what fits now. */
  setQueue(o: {
    limit: number;
    items: { key: string; url: string; headers: Record<string, string>; fileName: string; title: string; wifiOnly: boolean }[];
  }): Promise<{ started: Record<string, { downloadId?: number; path?: string; error?: string }>; active: number }>;
  takeStarted(): Promise<{ started: Record<string, { downloadId?: number; path?: string; error?: string }> }>;
  ackStarted(o: { keys: string[] }): Promise<void>;
  /** Landscape + hidden system bars for the player (the WebView can't go fullscreen by itself in Capacitor). */
  setPlayerMode(o: { fullscreen: boolean }): Promise<void>;
}

export const NativeDownloads = registerPlugin<JellytipDownloadsPlugin>("JellytipDownloads");

/** What the native IPTV player reports when it closes (android/app/src/main/java/com/jellytip/phone/PlayerPlugin.java). */
export interface NativePlayerResult {
  reason: "back" | "ended" | "error";
  /** The channel playing when it closed (after a failover it differs from the one opened). */
  channelId?: string;
  positionMs: number;
  durationMs: number;
  error?: string;
  /** Media3 error code name, e.g. ERROR_CODE_IO_BAD_HTTP_STATUS. */
  errorCode?: string;
  /** HTTP status of a refused stream link (0 = none). */
  httpStatus?: number;
  /** The companion's X-Iptv-Error on that refusal. */
  iptvError?: string;
}

export interface JellytipPlayerPlugin {
  /** Full-screen ExoPlayer for an IPTV link; resolves when it closes. */
  open(o: {
    url: string;
    title: string;
    mime?: string;
    live: boolean;
    growing?: boolean;
    resumeMs?: number;
    note?: string;
    /** Live (plan "8h Block 3"): the channel playing, for failover and From kick-off. */
    channelId?: string;
    /** The event's other channels, best first; links are asked for with "needUrl" when needed. */
    alternates?: { id: string; name: string }[];
    /** The programme on now (epoch s): enables the "From kick-off" button. */
    programmeStart?: number;
    programmeStop?: number;
    /** Pre-game (plan live-sport "Pinned 31-Oct (native phone player)"): "pre" shows the pre-game card until startsAt. */
    phase?: "pre" | "live" | "post" | null;
    /** The event's real start, ISO UTC (e.g. "2026-10-31T18:00:00Z"). */
    startsAt?: string | null;
    /** Channels offered by the card's "Other channels" (falls back to `alternates`). */
    altChannels?: { id: string; name: string }[];
    /** Match info / in-event panel (plan live-sport "In-event panel"): the fixture id (matchInfo.id). Shows ⓘ (and Live when live). */
    matchId?: string;
    /** Companion API base (COMPANION_BASE) and the Jellyfin token: the native Live drawer polls /v1/sport/match/{id}/live itself. */
    apiBase?: string;
    token?: string;
  }): Promise<NativePlayerResult>;
  /** Answer a "needUrl" event: the absolute link (from /play), or an error when there is none. */
  provideUrl(o: { requestId: number; url?: string; mime?: string; error?: string }): Promise<void>;
  addListener(event: "progress", cb: (e: { positionMs: number; durationMs: number }) => void): Promise<{ remove: () => Promise<void> }>;
  addListener(event: "playerError", cb: (e: Record<string, unknown>) => void): Promise<{ remove: () => Promise<void> }>;
  /**
   * The player needs a link: reason "failover" (channelId = the alternate to try), "kickoff" (channelId + start/stop:
   * /play catch-up of the programme on now) or "live" (channelId: back to live). Answer with provideUrl within 15 s.
   */
  addListener(
    event: "needUrl",
    cb: (e: { requestId: number; reason: "failover" | "kickoff" | "live"; channelId: string; start?: number; stop?: number }) => void,
  ): Promise<{ remove: () => Promise<void> }>;
  /**
   * Pre-game card buttons: "remind" (channelId = the channel playing) or "channel" (channelId = the one picked from
   * Other channels). The web app sets the reminder / switches (e.g. closes and reopens on that channel).
   */
  addListener(event: "pregameAction", cb: (e: { action: "remind" | "channel"; channelId?: string }) => void): Promise<{ remove: () => Promise<void> }>;
  /** ⓘ tapped: show match info (the native player stays open; show it when open() resolves, or as you see fit). */
  addListener(event: "matchInfo", cb: (e: { matchId: string; channelId?: string }) => void): Promise<{ remove: () => Promise<void> }>;
  /** Live tapped: the native drawer opened (informational; it fetches /live itself). */
  addListener(event: "liveStats", cb: (e: { matchId: string; channelId?: string }) => void): Promise<{ remove: () => Promise<void> }>;
  /** Diagnostic: the player switched channel ({from, to, why}). */
  addListener(event: "failover", cb: (e: { from: string; to: string; why: string }) => void): Promise<{ remove: () => Promise<void> }>;
}

/** The phone app plays all IPTV natively (mpegts.js can't pass AC-3 through MSE and dies on long live streams). */
export const NativePlayer = registerPlugin<JellytipPlayerPlugin>("JellytipPlayer");
export const nativeIptvPlayer = isNative && Capacitor.getPlatform() === "android";
