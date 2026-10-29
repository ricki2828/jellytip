// Browser DeviceProfile for Jellyfin PlaybackInfo: direct play what this browser decodes natively,
// otherwise HLS (remux when only the container is the problem, else transcode to H.264 capped at 1080p by the player).

export interface Caps {
  hevc: boolean;
  av1: boolean;
  vp9: boolean;
  ac3: boolean;
  eac3: boolean;
  opus: boolean;
  flac: boolean;
  hevcMse: boolean;
  av1Mse: boolean;
}

const video = document.createElement("video");
const can = (t: string) => {
  try {
    return video.canPlayType(t) !== "" || (typeof MediaSource !== "undefined" && MediaSource.isTypeSupported(t));
  } catch {
    return false;
  }
};
const mse = (t: string) => typeof MediaSource !== "undefined" && MediaSource.isTypeSupported(t);

export function caps(): Caps {
  return {
    hevc: can('video/mp4; codecs="hvc1.1.6.L120.90"') || can('video/mp4; codecs="hev1.1.6.L120.90"'),
    hevcMse: mse('video/mp4; codecs="hvc1.1.6.L120.90"'),
    av1: can('video/mp4; codecs="av01.0.08M.08"'),
    av1Mse: mse('video/mp4; codecs="av01.0.08M.08"'),
    vp9: can('video/webm; codecs="vp9"'),
    ac3: can('audio/mp4; codecs="ac-3"'),
    eac3: can('audio/mp4; codecs="ec-3"'),
    opus: can('audio/mp4; codecs="opus"') || can('audio/webm; codecs="opus"'),
    flac: can('audio/mp4; codecs="flac"'),
  };
}

/** @param h264Only fallback profile used after a failed attempt: everything transcodes to H.264/AAC. */
export function deviceProfile(h264Only = false) {
  const c = caps();
  const mp4Video = ["h264", ...(!h264Only && c.hevc ? ["hevc"] : []), ...(!h264Only && c.av1 ? ["av1"] : [])];
  const audio = ["aac", "mp3", ...(c.opus ? ["opus"] : []), ...(c.flac ? ["flac"] : []), ...(c.ac3 ? ["ac3"] : []), ...(c.eac3 ? ["eac3"] : [])];
  // fMP4 HLS can carry HEVC/AV1 copies when MSE decodes them; TS HLS is the H.264 path.
  const hlsVideo = ["h264", ...(!h264Only && c.hevcMse ? ["hevc"] : []), ...(!h264Only && c.av1Mse ? ["av1"] : [])];
  const hlsAudio = ["aac", "mp3", ...(c.ac3 ? ["ac3"] : []), ...(c.eac3 ? ["eac3"] : [])];
  return {
    MaxStreamingBitrate: 120_000_000,
    MaxStaticBitrate: 120_000_000,
    MusicStreamingTranscodingBitrate: 384_000,
    DirectPlayProfiles: h264Only
      ? []
      : [
          { Container: "mp4,m4v,mov", Type: "Video", VideoCodec: mp4Video.join(","), AudioCodec: audio.join(",") },
          ...(c.vp9 ? [{ Container: "webm", Type: "Video", VideoCodec: ["vp8", "vp9", ...(c.av1 ? ["av1"] : [])].join(","), AudioCodec: "vorbis,opus" }] : []),
        ],
    TranscodingProfiles: [
      {
        Container: "mp4",
        Type: "Video",
        Protocol: "hls",
        VideoCodec: hlsVideo.join(","),
        AudioCodec: hlsAudio.join(","),
        Context: "Streaming",
        MaxAudioChannels: "6",
        MinSegments: "1",
        BreakOnNonKeyFrames: true,
      },
    ],
    ContainerProfiles: [],
    CodecProfiles: [
      {
        Type: "Video",
        Codec: "h264",
        Conditions: [
          { Condition: "LessThanEqual", Property: "VideoLevel", Value: "52", IsRequired: false },
          { Condition: "NotEquals", Property: "IsAnamorphic", Value: "true", IsRequired: false },
          { Condition: "EqualsAny", Property: "VideoProfile", Value: "high|main|baseline|constrained baseline", IsRequired: false },
          { Condition: "EqualsAny", Property: "VideoRangeType", Value: "SDR", IsRequired: false },
        ],
      },
      {
        Type: "Video",
        Codec: "hevc",
        Conditions: [
          { Condition: "EqualsAny", Property: "VideoProfile", Value: "main|main 10", IsRequired: false },
          { Condition: "EqualsAny", Property: "VideoRangeType", Value: "SDR|HDR10|HLG", IsRequired: false },
        ],
      },
    ],
    SubtitleProfiles: [
      { Format: "vtt", Method: "External" },
      { Format: "srt", Method: "External" },
      { Format: "subrip", Method: "External" },
      { Format: "ass", Method: "External" },
      { Format: "ssa", Method: "External" },
    ],
    ResponseProfiles: [{ Type: "Video", Container: "m4v", MimeType: "video/mp4" }],
  };
}
