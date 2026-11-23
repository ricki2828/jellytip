// One companion call with any method, for the per-user routes that need a status (search, /v1/me/*): GET / POST /
// PATCH / DELETE with the person's Jellyfin token; native HTTP in the app (the JSON routes send no CORS headers).
import { CapacitorHttp } from "@capacitor/core";
import { getSession } from "./jellyfin";
import { COMPANION_BASE } from "./companion";
import { isNative } from "../lib/native";

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface Reply {
  /** 0 = unreachable, 401 = signed out. */
  status: number;
  data: unknown;
}

export async function companionCall(method: Method, path: string, body?: unknown, opts: { signal?: AbortSignal; headers?: Record<string, string>; timeoutMs?: number } = {}): Promise<Reply> {
  const token = getSession()?.token;
  if (!token) return { status: 401, data: null };
  const url = `${COMPANION_BASE}/${path.replace(/^\//, "")}`;
  const headers: Record<string, string> = { Authorization: `MediaBrowser Token="${token}"`, ...opts.headers };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  try {
    if (isNative) {
      const r = await CapacitorHttp.request({ url, method, headers, data: body, responseType: "json", connectTimeout: 8000, readTimeout: opts.timeoutMs ?? 8000 });
      return { status: r.status, data: typeof r.data === "string" ? null : r.data };
    }
    // timeoutMs bounds the browser path too (a hung call must not hold a page's busy state until the browser gives up). An
    // AbortController + setTimeout, not AbortSignal.timeout: older WebViews and Safari < 16 lack it (as lib/askChat.ts)
    let ctl: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    if (!opts.signal && opts.timeoutMs) {
      ctl = new AbortController();
      const c = ctl;
      timer = setTimeout(() => c.abort(), opts.timeoutMs);
    }
    try {
      const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: opts.signal ?? ctl?.signal });
      return { status: res.status, data: await res.json().catch(() => null) };
    } finally {
      clearTimeout(timer);
    }
  } catch {
    return { status: 0, data: null };
  }
}
