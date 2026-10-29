// IPTV playback rules (pure, tested): what to ask /play for, which engine plays the answer, and what a failed stream
// link means. Contract: ~/ai/plans/jellytip-iptv.md (Stream links, Playback).

export interface PlayTarget {
  channel?: string;
  start?: number;
  stop?: number;
  recording?: string;
  vod?: string;
  series?: string;
  episode?: string;
}

const KEYS = ["channel", "start", "stop", "recording", "vod", "series", "episode"] as const;

/** Hash-route query (#/iptv/play?…) → target; null when it names nothing playable. */
export function parseTarget(q: URLSearchParams): PlayTarget | null {
  const t: PlayTarget = {};
  for (const k of KEYS) {
    const v = q.get(k);
    if (v == null || v === "") continue;
    if (k === "start" || k === "stop") {
      const n = Number(v);
      if (Number.isFinite(n) && n > 0) t[k] = Math.floor(n);
    } else t[k] = v;
  }
  if (t.channel || t.recording || t.vod || (t.series && t.episode)) return t;
  return null;
}

/** Route path for a target (the inverse of parseTarget). */
export function targetPath(t: PlayTarget): string {
  const q = new URLSearchParams();
  for (const k of KEYS) if (t[k] != null) q.set(k, String(t[k]));
  return `/iptv/play?${q}`;
}

/** /play query for a target; live TV goes as TS unless the browser can only do HLS. */
export function playQuery(t: PlayTarget, device: string, format: "ts" | "m3u8" = "ts"): string {
  const q = new URLSearchParams();
  for (const k of KEYS) if (t[k] != null) q.set(k, String(t[k]));
  if (t.channel && t.start == null) q.set("format", format);
  q.set("device", device);
  return `v1/iptv/play?${q}`;
}

export type Engine = "mpegts" | "hls" | "native";

/** Stream link → player engine: .m3u8 via hls.js, .ts via mpegts.js, files (mp4/mkv…) straight into <video>. */
export function engineFor(url: string, mime = ""): Engine {
  const path = url.split("?")[0].toLowerCase();
  if (path.endsWith(".m3u8") || /mpegurl/i.test(mime)) return "hls";
  if (path.endsWith(".ts") || /mp2t/i.test(mime)) return "mpegts";
  return "native";
}

export type LinkFailure = "renew" | "full" | "fatal";

/**
 * A stream link (or /play) answered `status` with `code` (X-Iptv-Error or the JSON code): 410 stale and 403 expired
 * mean "call /play again and retry once"; 409 means every connection is taken; anything else is final.
 */
export function linkFailure(status: number, code?: string | null): LinkFailure {
  if (status === 410 || (status === 403 && code === "expired")) return "renew";
  if (status === 409 || code === "max_connections") return "full";
  return "fatal";
}

/** Human text for a refusal code. */
export function failureText(code: string | null | undefined, fallback: string): string {
  switch (code) {
    case "provider_auth":
      return "The provider refused the login. Check it in Settings → Live TV / IPTV.";
    case "provider_unreachable":
      return "The provider isn't answering right now.";
    case "provider_refused":
    case "provider_error":
      return "The provider couldn't start this stream.";
    case "no_catchup":
      return "This channel has no catch-up for that time.";
    case "bad_token":
    case "expired":
    case "stale":
      return "The stream link is no longer valid.";
    default:
      return fallback;
  }
}
