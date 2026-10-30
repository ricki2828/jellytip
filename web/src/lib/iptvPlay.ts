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

/**
 * /play query for a target; live TV goes as TS unless the browser can only do HLS. [native]: the phone's ExoPlayer
 * (`client=native`), which may get a direct-from-provider link.
 */
export function playQuery(t: PlayTarget, device: string, format: "ts" | "m3u8" = "ts", native = false): string {
  const q = new URLSearchParams();
  for (const k of KEYS) if (t[k] != null) q.set(k, String(t[k]));
  if (t.channel && t.start == null) q.set("format", format);
  // Live and catch-up on the web: the server re-encodes audio to AAC (browsers can't play AC-3 through MSE)
  if (t.channel && !native) q.set("compat", "web");
  q.set("device", device);
  if (native) q.set("client", "native");
  return `v1/iptv/play?${q}`;
}

/**
 * The link the phone's native player opens: a direct-from-provider answer's own absolute URL (plan part C), else the
 * companion's relative link made absolute by [absolute].
 */
export function nativeLink(a: { url: string; direct?: boolean }, absolute: (rel: string) => string): { url: string; direct: boolean } {
  if (a.direct && /^https?:\/\//i.test(a.url)) return { url: a.url, direct: true };
  return { url: absolute(a.url), direct: false };
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

/** One more try as HLS when live TS connected but could not be played (mpegts.js limits: AC-3/E-AC-3 audio, HEVC, odd streams). */
export function fallbackFormat(live: boolean, format: "ts" | "m3u8"): "m3u8" | null {
  return live && format === "ts" ? "m3u8" : null;
}

/** Text for a stream that connected (or was cut short) but did not play. `detail` is the engine's own wording. */
export function playbackFailureText(engine: Engine, detail: string, status: number): string {
  if (status === 0) return "The server can't be reached.";
  const d = detail ? ` (${detail})` : "";
  if (engine === "native")
    return `This phone can't play this file${d}. The server has no converted copy to offer; it may play on the TV app or on Plex.`;
  return `This stream connected but the phone couldn't play it${d}.`;
}

// ---------- live extras: start over from kick-off, and other channels showing the same event ----------

export interface LiveExtras {
  /** The programme on now, when the channel has catch-up (the server's `startOver`): /play with start= plays it from the beginning. */
  kickoff?: { start: number; stop: number } | null;
  /** Other channels with the same event, best first. */
  alternates?: { id: string; name: string }[];
}

const MAX_ALTERNATES = 4;

/** "&ko=<start>&kos=<stop>&alt=<id>~<name>…" for a live play link ("" when there is nothing to carry). */
export function liveExtrasQuery(x: LiveExtras | null | undefined): string {
  if (!x) return "";
  const q = new URLSearchParams();
  if (x.kickoff && x.kickoff.stop > x.kickoff.start) {
    q.set("ko", String(Math.floor(x.kickoff.start)));
    q.set("kos", String(Math.floor(x.kickoff.stop)));
  }
  for (const a of (x.alternates ?? []).slice(0, MAX_ALTERNATES)) q.append("alt", `${a.id}~${a.name}`);
  const s = q.toString();
  return s ? `&${s}` : "";
}

export function parseLiveExtras(q: URLSearchParams): LiveExtras {
  const start = Number(q.get("ko"));
  const stop = Number(q.get("kos"));
  const alternates = q
    .getAll("alt")
    .slice(0, MAX_ALTERNATES)
    .flatMap((v) => {
      const at = v.indexOf("~");
      const id = at < 0 ? v : v.slice(0, at);
      return /^[A-Za-z0-9_-]{1,40}:[A-Za-z0-9_.-]{1,64}$/.test(id) ? [{ id, name: at < 0 ? "Another channel" : v.slice(at + 1) || "Another channel" }] : [];
    });
  return { kickoff: start > 0 && stop > start ? { start, stop } : null, alternates };
}

/** Live play link for a channel with its extras. */
export const livePath = (channel: string, name: string, x?: LiveExtras | null) => `${targetPath({ channel })}&t=${encodeURIComponent(name)}${liveExtrasQuery(x)}`;
