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
