// PlaybackInfo negotiation and session reporting (start / progress / stop) so resume and Continue Watching
// stay in sync with the TV app.

import { authHeader, deviceId, getSession, jf, JF_BASE, qs, uid, type MediaSource } from "./jellyfin";
import { deviceProfile } from "../lib/profile";

export type PlayMethod = "DirectPlay" | "DirectStream" | "Transcode";

export interface PlayPlan {
  url: string;
  hls: boolean;
  method: PlayMethod;
  source: MediaSource;
  playSessionId: string;
}

const MAX_TRANSCODE_W = 1920;
const MAX_TRANSCODE_H = 1080;
/** Reasons that leave the video stream untouched (remux / audio-only transcode); anything else re-encodes video. */
const COPY_SAFE_REASONS = new Set([
  "ContainerNotSupported",
  "ContainerBitrateExceedsLimit",
  "AudioCodecNotSupported",
  "AudioChannelsNotSupported",
  "AudioBitrateNotSupported",
  "AudioSampleRateNotSupported",
  "AudioProfileNotSupported",
  "AudioBitDepthNotSupported",
  "AudioIsExternal",
  "SecondaryAudioNotSupported",
]);

export interface NegotiateOptions {
  /** Fallback after a failed attempt: everything transcodes to H.264/AAC. */
  h264Only?: boolean;
  audioStreamIndex?: number;
  /** Off when a non-default audio track is chosen: the browser can't switch tracks in a direct-played file. */
  allowDirectPlay?: boolean;
}

export async function negotiate(itemId: string, startTicks: number, opts: NegotiateOptions = {}): Promise<PlayPlan> {
  const { h264Only = false, audioStreamIndex } = opts;
  const directPlay = !h264Only && opts.allowDirectPlay !== false;
  const info = await jf<{ MediaSources: MediaSource[]; PlaySessionId: string; ErrorCode?: string }>(
    `/Items/${itemId}/PlaybackInfo${qs({ UserId: uid() })}`,
    {
      method: "POST",
      json: {
        UserId: uid(),
        DeviceProfile: deviceProfile(h264Only),
        StartTimeTicks: startTicks,
        AutoOpenLiveStream: true,
        EnableDirectPlay: directPlay,
        EnableDirectStream: !h264Only,
        EnableTranscoding: true,
        AllowVideoStreamCopy: !h264Only,
        AllowAudioStreamCopy: true,
        MaxStreamingBitrate: 120_000_000,
        AudioStreamIndex: audioStreamIndex,
      },
    },
  );
  if (info.ErrorCode) throw new Error(`Jellyfin: ${info.ErrorCode}`);
  const source = info.MediaSources?.[0];
  if (!source) throw new Error("No playable source");
  const token = getSession()!.token;
  if (source.SupportsDirectPlay && directPlay) {
    const url =
      `${JF_BASE}/Videos/${itemId}/stream.${(source.Container ?? "mp4").split(",")[0]}` +
      qs({ Static: true, MediaSourceId: source.Id, PlaySessionId: info.PlaySessionId, DeviceId: deviceId(), Tag: source.ETag, api_key: token });
    return { url, hls: false, method: "DirectPlay", source, playSessionId: info.PlaySessionId };
  }
  if (!source.TranscodingUrl) throw new Error("Server offered no stream for this browser");
  const u = new URL(JF_BASE + source.TranscodingUrl, location.origin); // absolute in the app (JF_BASE has the origin)
  const reasons = (u.searchParams.get("TranscodeReasons") ?? "").split(",").filter(Boolean);
  // Anything that isn't a pure container/audio change re-encodes the video (codec, profile, level, anamorphic, interlaced,
  // ref frames, bitrate, ...): those runs get the 1080p cap
  const videoTranscode = h264Only || reasons.some((r) => !COPY_SAFE_REASONS.has(r));
  if (videoTranscode) {
    // Transcodes run on the seedbox CPU: never ask for more than 1080p
    u.searchParams.set("maxWidth", String(MAX_TRANSCODE_W));
    u.searchParams.set("maxHeight", String(MAX_TRANSCODE_H));
  }
  if (!u.searchParams.has("api_key")) u.searchParams.set("api_key", token);
  return { url: u.origin === location.origin ? u.pathname + u.search : u.href, hls: true, method: videoTranscode ? "Transcode" : "DirectStream", source, playSessionId: info.PlaySessionId };
}

interface Report {
  itemId: string;
  plan: PlayPlan;
  positionTicks: number;
  paused?: boolean;
  audioStreamIndex?: number;
  subtitleStreamIndex?: number;
}

function body(r: Report) {
  return {
    ItemId: r.itemId,
    MediaSourceId: r.plan.source.Id,
    PlaySessionId: r.plan.playSessionId,
    PositionTicks: Math.max(0, Math.round(r.positionTicks)),
    PlayMethod: r.plan.method,
    CanSeek: true,
    IsPaused: !!r.paused,
    IsMuted: false,
    AudioStreamIndex: r.audioStreamIndex,
    SubtitleStreamIndex: r.subtitleStreamIndex ?? -1,
  };
}

export const reportStart = (r: Report) => jf("/Sessions/Playing", { method: "POST", json: body(r) }).catch(() => undefined);
export const reportProgress = (r: Report) =>
  jf("/Sessions/Playing/Progress", { method: "POST", json: { ...body(r), EventName: "timeupdate" } }).catch(() => undefined);

/** keepalive so a stop sent while the tab closes still lands. */
export function reportStopped(r: Report) {
  const p = jf("/Sessions/Playing/Stopped", { method: "POST", json: body(r), keepalive: true }).catch(() => undefined);
  if (r.plan.hls) {
    jf(`/Videos/ActiveEncodings${qs({ DeviceId: deviceId(), PlaySessionId: r.plan.playSessionId })}`, { method: "DELETE", keepalive: true }).catch(
      () => undefined,
    );
  }
  return p;
}

/** Stop the server-side ffmpeg of an HLS plan that never reported start (abandoned while loading). */
export function stopEncoding(plan: PlayPlan) {
  if (!plan.hls) return;
  jf(`/Videos/ActiveEncodings${qs({ DeviceId: deviceId(), PlaySessionId: plan.playSessionId })}`, { method: "DELETE", keepalive: true }).catch(() => undefined);
}

/** WebVTT text for a text subtitle stream (Jellyfin converts SRT/ASS on the fly); token in the header, not the URL. */
export async function fetchSubtitles(itemId: string, sourceId: string, index: number, signal?: AbortSignal): Promise<string> {
  const res = await fetch(`${JF_BASE}/Videos/${itemId}/${sourceId}/Subtitles/${index}/0/Stream.vtt`, { headers: { Authorization: authHeader() }, signal });
  if (!res.ok) throw new Error(String(res.status));
  return res.text();
}

export function trailerStreamUrl(trailerId: string): string {
  return `${JF_BASE}/Videos/${trailerId}/stream${qs({ Static: true, api_key: getSession()!.token })}`;
}
