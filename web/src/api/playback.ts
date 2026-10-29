// PlaybackInfo negotiation and session reporting (start / progress / stop) so resume and Continue Watching
// stay in sync with the TV app.

import { deviceId, getSession, jf, JF_BASE, qs, uid, type MediaSource } from "./jellyfin";
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

export async function negotiate(itemId: string, startTicks: number, h264Only = false, audioStreamIndex?: number): Promise<PlayPlan> {
  const info = await jf<{ MediaSources: MediaSource[]; PlaySessionId: string; ErrorCode?: string }>(
    `/Items/${itemId}/PlaybackInfo${qs({ UserId: uid() })}`,
    {
      method: "POST",
      json: {
        UserId: uid(),
        DeviceProfile: deviceProfile(h264Only),
        StartTimeTicks: startTicks,
        AutoOpenLiveStream: true,
        EnableDirectPlay: !h264Only,
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
  if (source.SupportsDirectPlay && !h264Only) {
    const url =
      `${JF_BASE}/Videos/${itemId}/stream.${(source.Container ?? "mp4").split(",")[0]}` +
      qs({ Static: true, MediaSourceId: source.Id, PlaySessionId: info.PlaySessionId, DeviceId: deviceId(), Tag: source.ETag, api_key: token });
    return { url, hls: false, method: "DirectPlay", source, playSessionId: info.PlaySessionId };
  }
  if (!source.TranscodingUrl) throw new Error("Server offered no stream for this browser");
  const u = new URL(JF_BASE + source.TranscodingUrl, location.origin);
  const reasons = (u.searchParams.get("TranscodeReasons") ?? "").split(",").filter(Boolean);
  const videoTranscode = h264Only || reasons.some((r) => r.startsWith("Video") || r === "DirectPlayError");
  if (videoTranscode) {
    // Transcodes run on the seedbox CPU: never ask for more than 1080p
    u.searchParams.set("maxWidth", String(MAX_TRANSCODE_W));
    u.searchParams.set("maxHeight", String(MAX_TRANSCODE_H));
  }
  if (!u.searchParams.has("api_key")) u.searchParams.set("api_key", token);
  return { url: u.pathname + u.search, hls: true, method: videoTranscode ? "Transcode" : "DirectStream", source, playSessionId: info.PlaySessionId };
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

/** WebVTT for a text subtitle stream (Jellyfin converts SRT/ASS on the fly). */
export function subtitleUrl(itemId: string, sourceId: string, index: number): string {
  return `${JF_BASE}/Videos/${itemId}/${sourceId}/Subtitles/${index}/0/Stream.vtt${qs({ api_key: getSession()!.token })}`;
}

export function trailerStreamUrl(trailerId: string): string {
  return `${JF_BASE}/Videos/${trailerId}/stream${qs({ Static: true, api_key: getSession()!.token })}`;
}
