// Spoiler-delay maths (pure; tested in web/test/delay.test.ts). Plan: ~/ai/plans/jellytip-sport-companion.md "Delay model".
//   reveal_at = event_time + delaySec + (liveEdge - currentTime)
// event_time is the source's wallclock (else the server's first-observed time); delaySec is how far the stream runs behind that
// clock; the (liveEdge - currentTime) term is how far the viewer's playhead sits behind the stream's live edge.

export const DEFAULT_DELAY_SEC = 30;
export const MAX_DELAY_SEC = 300;
/** A playhead further behind the live edge than this is a replay (catch-up, start-over, a recording): nothing is revealed. */
export const REPLAY_LAG_SEC = 120;
/** Once in replay, the playhead must come back inside this before live reveals resume (no flapping at the boundary). */
export const REPLAY_EXIT_LAG_SEC = 100;

export const clampDelay = (n: unknown, fallback = DEFAULT_DELAY_SEC): number =>
  typeof n === "number" && isFinite(n) ? Math.min(MAX_DELAY_SEC, Math.max(0, Math.round(n))) : fallback;

interface Ranges {
  length: number;
  end(i: number): number;
}
/** The stream's live edge in media seconds: end of the seekable range, else of the buffer, else the playhead itself. */
export function liveEdge(v: { currentTime: number; seekable?: Ranges | null; buffered?: Ranges | null }): number {
  for (const r of [v.seekable, v.buffered]) {
    if (r && r.length > 0) {
      const e = r.end(r.length - 1);
      if (isFinite(e)) return Math.max(e, v.currentTime);
    }
  }
  return v.currentTime;
}

/** Seconds the playhead sits behind the live edge (never negative). */
export const lagSec = (currentTime: number, edge: number): number => (isFinite(currentTime) && isFinite(edge) ? Math.max(0, edge - currentTime) : 0);

/** Replay with hysteresis: enters past REPLAY_LAG_SEC, leaves under REPLAY_EXIT_LAG_SEC. `forced` (recording, not live) always wins. */
export function replayState(prev: boolean, lag: number, forced = false): boolean {
  if (forced) return true;
  return prev ? lag >= REPLAY_EXIT_LAG_SEC : lag > REPLAY_LAG_SEC;
}

/** Wall-clock second (epoch) at which an event is shown to this viewer. */
export const revealAt = (eventSec: number, delaySec: number, lag: number): number => eventSec + delaySec + lag;

/** Whether an event is revealed now. No event time = "is live reveal on at all". Replay reveals nothing. */
export function isRevealed(eventSec: number | undefined, nowSec: number, delaySec: number, lag: number, replay: boolean): boolean {
  if (replay) return false;
  if (eventSec == null || !isFinite(eventSec)) return true;
  return nowSec >= revealAt(eventSec, delaySec, lag);
}

/**
 * "That just happened": the delay at which the latest event is revealed right now. Null when there is no event to anchor on or
 * the tap lands before the event (a clock skew too large to trust).
 */
export function calibratedDelay(latestEventSec: number | null, nowSec: number, lag: number): number | null {
  if (latestEventSec == null || !isFinite(latestEventSec)) return null;
  const d = nowSec - latestEventSec - lag;
  // Past the cap the tap is not about a recent event (the latest key moment is stale), so there is nothing honest to line up
  return d < -5 || d > MAX_DELAY_SEC ? null : clampDelay(d);
}

/** The latest key-moment time seen so far (epoch s), ignoring times from the future: the only anchor "that just happened" may use. */
export function noteEvent(latest: number | null, atSec: number, nowSec: number): number | null {
  if (!isFinite(atSec) || atSec > nowSec + 5) return latest;
  return latest == null || atSec > latest ? atSec : latest;
}

export type CalibrateResult = { kind: "set"; sec: number } | { kind: "none" } | { kind: "replay" };

/** What to tell the viewer after tapping Adjust. */
export function calibrateMessage(r: CalibrateResult): string {
  if (r.kind === "set") return `Delay set to ${r.sec} s`;
  if (r.kind === "replay") return "Updates are off while you watch a replay";
  return "Nothing to line up yet. Tap Adjust right after something happens on screen.";
}

/** A channel is "pid:sid" (provider, stream); a bare key gets provider "_". */
export function parseChannelKey(key: string): { pid: string; sid: string } {
  const i = key.indexOf(":");
  return i < 0 ? { pid: "_", sid: key } : { pid: key.slice(0, i), sid: key.slice(i + 1) };
}

/** Steppers in the Adjust popover: current delay plus a signed step, kept in range. */
export const stepDelay = (cur: number, by: number): number => clampDelay(cur + by);

/** Saves a channel's delay: the browser copy first, then the server (a server without the route keeps the browser copy). One path for calibrate and the popover. */
export function persistChannelDelay(
  channelKey: string | null,
  sec: number,
  io: { writeLocal: (k: string, s: number) => void; put: (pid: string, sid: string, s: number) => unknown },
): number {
  const d = clampDelay(sec);
  if (!channelKey) return d;
  io.writeLocal(channelKey, d);
  const { pid, sid } = parseChannelKey(channelKey);
  void io.put(pid, sid, d);
  return d;
}

/**
 * Debounced, ordered server save: bursts of stepper taps send one trailing PUT with the last value, PUTs go one at a time in
 * order, and a value superseded before it was sent is dropped. The caller writes the browser copy at once.
 */
export function makeTrailingPut(
  put: (pid: string, sid: string, s: number) => Promise<unknown> | unknown,
  o: { ms?: number; schedule?: (f: () => void, ms: number) => unknown; cancel?: (h: unknown) => void } = {},
): (pid: string, sid: string, s: number) => void {
  const ms = o.ms ?? 600;
  const schedule = o.schedule ?? ((f, m) => setTimeout(f, m));
  const cancel = o.cancel ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  let seq = 0;
  let timer: unknown = null;
  let chain: Promise<unknown> = Promise.resolve();
  return (pid, sid, s) => {
    const mine = ++seq;
    if (timer != null) cancel(timer);
    timer = schedule(() => {
      timer = null;
      chain = chain.then(() => (mine === seq ? put(pid, sid, s) : undefined)).catch(() => undefined);
    }, ms);
  };
}
