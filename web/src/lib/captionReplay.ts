// "What did they say?": jump back a few seconds, show captions for that span, then put the subtitle state back.
export const REPLAY_BACK_SECONDS = 10;
/** Less room than this to seek back and the control is disabled instead of jumping nowhere. */
export const REPLAY_MIN_SECONDS = 2;

/** A subtitle selection: the track index (player specific), or -1 for off. */
export type Replay = { restore: number; until: number; from: number };

export type ReplayStart = {
  seekTo: number;
  /** track to show for the replay (null = already showing one, leave it) */
  show: number | null;
  /** state to track from now on (null = nothing to restore) */
  replay: Replay | null;
};

/** Where a replay would jump to, or null when there is not enough seekable media behind the playhead. */
export function replayTarget(time: number, seekableStart = 0, back = REPLAY_BACK_SECONDS): number | null {
  if (!Number.isFinite(time)) return null;
  const to = Math.max(seekableStart, time - back);
  return time - to >= REPLAY_MIN_SECONDS ? to : null;
}

/**
 * @param current  subtitle selection now (-1 = off)
 * @param fallback track to switch on when subtitles are off (first caption track); null = none available
 * @param active   a replay already running: pressing again keeps the ORIGINAL restore state
 */
export function startReplay(time: number, current: number, fallback: number | null, seekableStart: number, active: Replay | null): ReplayStart | null {
  const seekTo = replayTarget(time, seekableStart);
  if (seekTo == null) return null;
  const restore = active ? active.restore : current;
  if (restore >= 0) return { seekTo, show: null, replay: null }; // captions were already on: just rewind
  if (fallback == null) return null; // nothing to show
  return { seekTo, show: current >= 0 ? null : fallback, replay: { restore: -1, until: active ? Math.max(active.until, time) : time, from: seekTo } };
}

/** True once the replayed span has played out, or the viewer has moved away from it (a seek elsewhere). */
export function replayDone(r: Replay, time: number): boolean {
  return time >= r.until || time < r.from - 1;
}
