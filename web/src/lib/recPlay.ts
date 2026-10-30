// Recording playback helpers (pure; tested in web/test/iptv.test.ts). A recording is a raw ffmpeg copy of the stream, so the
// player often can't read its length from the file (MPEG-TS has no index): the length comes from the recording's own
// times instead, and an in-progress one grows with the wall clock.

export interface RecMeta {
  state: string;
  start: number;
  stop: number;
  stopPadded?: number | null;
  began?: number | null;
  ended?: number | null;
  progressDuration?: number | null;
}

/** Seconds of recording playable now. A usable length from the player wins; else worked out from the recording. */
export function recordingLength(m: RecMeta | null, now: number, mediaDur: number): number {
  if (isFinite(mediaDur) && mediaDur > 0) return mediaDur;
  if (!m) return 0;
  if (m.state === "recording") return Math.max(0, now - (m.began ?? m.start));
  if (m.progressDuration && m.progressDuration > 0) return m.progressDuration;
  if (m.began && m.ended && m.ended > m.began) return m.ended - m.began;
  return Math.max(0, (m.stopPadded ?? m.stop) - m.start);
}

/** Where a seek may land: inside what's there (in-progress: a few seconds behind the end), never negative. */
export function clampSeek(to: number, length: number, growing: boolean): number {
  if (!(length > 0)) return Math.max(0, to);
  return Math.min(Math.max(0, to), Math.max(0, length - (growing ? 3 : 1)));
}

/** "1:02:03" / "02:03". */
export function clockText(s: number): string {
  const t = Math.max(0, Math.floor(s));
  const h = Math.floor(t / 3600);
  return `${h ? `${h}:` : ""}${String(Math.floor((t % 3600) / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
}
