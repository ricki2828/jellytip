// Small event log for support: every entry goes to the console and a ring buffer; sendDebugReport() posts the buffer
// to the companion (POST /v1/debug/report, contract in ~/ai/plans/jellytip-specials.md). Signed stream links
// (/s/<token>) are never written down.
import { deviceId, getSession } from "../api/jellyfin";
import { companionCall } from "../api/companionCall";
import { scrub } from "./scrub";
import { clean } from "./debugPrivacy";

const MAX_EVENTS = 200;
const events: Record<string, unknown>[] = [];

export function debugEvent(tag: string, msg: string, data: Record<string, unknown> = {}, level: "info" | "warn" | "error" = "warn") {
  const e = { t: new Date().toISOString(), level, tag, msg: scrub(msg), ...clean(data) };
  events.push(e);
  if (events.length > MAX_EVENTS) events.shift();
  console[level === "info" ? "log" : level](`[jellytip:${tag}] ${e.msg}`, clean(data));
}

let lastSent = 0;
let pending: ReturnType<typeof setTimeout> | null = null;
/** Posts the buffered events (at most one report every 6 s: the server allows 1 per 5 s). */
export async function sendDebugReport(): Promise<void> {
  if (!events.length) return;
  const remaining = 6000 - (Date.now() - lastSent);
  if (remaining > 0) {
    if (!pending) pending = setTimeout(() => { pending = null; void sendDebugReport(); }, remaining);
    return;
  }
  lastSent = Date.now();
  await companionCall("POST", "v1/debug/report", {
    app: "jellytip-web",
    version: "0.1.0",
    device: deviceId(),
    user: getSession()?.userName ?? null,
    createdAt: new Date().toISOString(),
    events,
  });
}
