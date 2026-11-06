// Keyboard / remote seeking: a press adds a step to a pending target, repeated or held presses ramp up
// (10 -> 30 -> 60 s), Shift jumps further, and the real seek is committed once presses stop for a moment.
export const SETTLE_MS = 450; // quiet time before the pending target is committed
export const BURST_GAP_MS = 900; // presses closer than this belong to one burst
export const REPEAT_THROTTLE_MS = 150; // a held key repeats ~30/s: apply one step per this many ms

const TIERS = [10, 30, 60];
const SHIFT_TIERS = [60, 180, 300];

export interface SeekRamp {
  target: number;
  dir: number;
  presses: number; // presses in this burst
  burstStart: number; // when the burst (or the hold) began
  lastAt: number; // last time a step was applied
}

export interface SeekInput {
  dir: -1 | 1;
  now: number;
  repeat: boolean; // KeyboardEvent.repeat
  shift: boolean;
  base: number; // current playback time, used when nothing is pending
  duration: number;
}

const clamp = (t: number, duration: number) => Math.min(Math.max(t, 0), Math.max(0, duration - 1));

/** Step size for the n-th press of a burst (discrete presses), or for a hold of holdMs. */
export function stepFor(presses: number, holdMs: number, repeat: boolean, shift: boolean): number {
  const tiers = shift ? SHIFT_TIERS : TIERS;
  if (repeat) return tiers[holdMs < 500 ? 0 : holdMs < 1500 ? 1 : 2];
  return tiers[presses <= 2 ? 0 : presses <= 4 ? 1 : 2];
}

/** Applies a key press. Returns the same object when a held-key repeat is throttled. */
export function advanceSeek(prev: SeekRamp | null, i: SeekInput): SeekRamp {
  const live = !!prev && i.now - prev.lastAt <= BURST_GAP_MS; // a pending target still exists
  const sameRun = live && prev!.dir === i.dir;
  if (sameRun && i.repeat && i.now - prev!.lastAt < REPEAT_THROTTLE_MS) return prev!;
  const presses = sameRun ? prev!.presses + 1 : 1;
  const burstStart = sameRun ? prev!.burstStart : i.now;
  const from = live ? prev!.target : i.base; // reversing keeps the pending target, only the ramp restarts
  const step = stepFor(presses, i.now - burstStart, i.repeat, i.shift);
  return { target: clamp(from + i.dir * step, i.duration), dir: i.dir, presses, burstStart, lastAt: i.now };
}

/** True once presses have stopped long enough to fire the seek. */
export function settled(s: SeekRamp | null, now: number): boolean {
  return !!s && now - s.lastAt >= SETTLE_MS;
}
