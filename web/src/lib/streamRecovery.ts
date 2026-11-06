export interface AlternateChannel {
  id: string;
  name: string;
}

/** Only surface a live stall while playback is active and the page is foregrounded. */
export function shouldSurfaceLiveStall(now: number, lastMove: number, threshold: number, paused: boolean, hidden: boolean, quietUntil: number): boolean {
  return !paused && !hidden && now - lastMove >= threshold && now > quietUntil;
}

/** When switching, retain the other candidates and put the channel we left last. */
export function alternatesAfterSwitch(alternates: AlternateChannel[], current: AlternateChannel, selectedId: string): AlternateChannel[] {
  return [...alternates.filter((channel) => channel.id !== selectedId && channel.id !== current.id), current];
}

// ---------- recovery state machine (pure; tested in web/test/streamRecovery.test.ts) ----------
// playing -> stalled -> reconnecting (attempt k of max, backoff) -> switched (HLS) -> failed.
// The machine never talks to the network: step() returns effects the player runs. The single-connection guarantee lives
// here: an `open` effect is only emitted while conn === "closed", i.e. after the player confirmed `released`.

export const MAX_ATTEMPTS = 4;
export const SWITCH_ATTEMPTS = 2;
export const BACKOFF_BASE_MS = 1000;
export const BACKOFF_CAP_MS = 8000;
export const HIDDEN_STOP_MS = 60_000;
/** Continuous playback needed before earlier attempts are forgiven. */
export const STABLE_MS = 20_000;
/** "Keep waiting" silences stall escalation this long. */
export const QUIET_MS = 60_000;
/** VOD / catch-up: escalate only after this long with no movement and no network/seek activity. */
export const VOD_IDLE_MS = 30_000;

/**
 * Every start that is not a recovery open goes through this first: it invalidates any queued/in-flight open (gen), cancels
 * the pending open timer and tears down the stream we hold, so two streams never exist at once.
 */
export function beginExclusive(gen: { current: number }, clearOpenTimer: () => void, teardown: () => void): number {
  gen.current += 1;
  clearOpenTimer();
  teardown();
  return gen.current;
}

/** Replace the engine held in a slot: the old one is destroyed before the new one is stored. */
export function claimEngine<T>(slot: { current: T | null }, destroy: (old: T) => void, next: T): void {
  const old = slot.current;
  slot.current = null;
  if (old) destroy(old);
  slot.current = next;
}

export const backoffMs = (attempt: number): number => Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, attempt - 1));

export type Phase = "playing" | "stalled" | "reconnecting" | "switched" | "failed" | "denied" | "blocked" | "ended";
/** Why a stream will not be retried automatically (denied phase). Manual Retry now stays available. */
export type DeniedReason = "auth" | "denied" | "missing" | "nocatchup" | "link" | "rate";
export const RETRY_DEBOUNCE_MS = 1500;
export type Conn = "held" | "releasing" | "closed" | "opening";
export type StreamFormat = "ts" | "m3u8";

export interface RecoveryState {
  phase: Phase;
  live: boolean;
  attempt: number;
  max: number;
  format: StreamFormat;
  switched: boolean;
  conn: Conn;
  offline: boolean;
  /** Tab hidden longer than HIDDEN_STOP_MS: retries are stopped until it is visible again. */
  /** Recovered but not yet stable: a new fault keeps counting attempts instead of starting over. */
  unstable: boolean;
  reason: DeniedReason | null;
  lastRetryAt: number | null;
  hiddenStop: boolean;
  hiddenSince: number | null;
  /** A (re)open waiting for the lease to be released, the network, or the tab. */
  want: { delayMs: number } | null;
  /** VOD / catch-up position to resume at; null for live (live edge). */
  resumeAt: number | null;
}

export type RecoveryEvent =
  | { t: "stall"; pos: number; canHls: boolean }
  | { t: "fault"; pos: number; canHls: boolean }
  | { t: "retryNow"; pos: number; canHls: boolean; now?: number }
  | { t: "fatal"; reason: DeniedReason }
  | { t: "opened" }
  | { t: "recovered" }
  | { t: "stable" }
  | { t: "released" }
  | { t: "offline" }
  | { t: "online" }
  | { t: "hidden"; now: number }
  | { t: "hiddenTimeout" }
  | { t: "visible"; now: number }
  | { t: "blocked" }
  | { t: "cancel" };

export type RecoveryEffect =
  | { do: "release" }
  | { do: "cancelTimers" }
  | { do: "open"; delayMs: number; resumeAt: number | null; format: StreamFormat }
  | { do: "exit" };

export interface Step {
  state: RecoveryState;
  effects: RecoveryEffect[];
}

export function initialRecovery(live: boolean, format: StreamFormat = "ts"): RecoveryState {
  return { phase: "playing", live, attempt: 0, max: MAX_ATTEMPTS, format, switched: false, conn: "held", offline: false, unstable: false, reason: null, lastRetryAt: null, hiddenStop: false, hiddenSince: null, want: null, resumeAt: null };
}

const paused = (s: RecoveryState) => s.offline || s.hiddenStop;
const active = (s: RecoveryState) => s.phase === "stalled" || s.phase === "reconnecting" || s.phase === "switched";

function maybeOpen(s: RecoveryState, fx: RecoveryEffect[]): void {
  if (s.conn !== "closed" || !s.want || paused(s) || s.phase === "failed" || s.phase === "ended" || s.phase === "blocked") return;
  fx.push({ do: "open", delayMs: s.want.delayMs, resumeAt: s.live ? null : s.resumeAt, format: s.format });
  s.conn = "opening";
  s.want = null;
}

function release(s: RecoveryState, fx: RecoveryEffect[]): void {
  if (s.conn === "held" || s.conn === "opening") {
    fx.push({ do: "release" });
    s.conn = "releasing";
  }
}

/** Queue an open after `delayMs`; the old stream is released first. */
function reopen(s: RecoveryState, fx: RecoveryEffect[], delayMs: number): void {
  s.want = { delayMs };
  release(s, fx);
  maybeOpen(s, fx);
}

function giveUp(s: RecoveryState, fx: RecoveryEffect[]): void {
  s.phase = "failed";
  s.want = null;
  fx.push({ do: "cancelTimers" });
  release(s, fx);
}

function nextAttempt(s: RecoveryState, fx: RecoveryEffect[], canHls: boolean): void {
  // Offline failures do not use up attempts: wait for the network and try the same one again.
  if (s.offline) {
    if (s.attempt < 1) s.attempt = 1;
    s.phase = s.switched ? "switched" : "reconnecting";
    return reopen(s, fx, 0);
  }
  if (s.attempt < s.max) {
    s.attempt += 1;
    s.phase = s.switched ? "switched" : "reconnecting";
    return reopen(s, fx, backoffMs(s.attempt));
  }
  if (!s.switched && s.live && s.format === "ts" && canHls) {
    s.switched = true;
    s.format = "m3u8";
    s.attempt = 1;
    s.max = SWITCH_ATTEMPTS;
    s.phase = "switched";
    return reopen(s, fx, 0);
  }
  giveUp(s, fx);
}

function begin(s: RecoveryState, fx: RecoveryEffect[], e: { pos: number; canHls: boolean }): void {
  s.resumeAt = s.live ? null : s.phase === "stalled" && s.resumeAt != null ? s.resumeAt : e.pos > 5 ? e.pos : null;
  s.attempt = 0;
  s.max = MAX_ATTEMPTS;
  s.switched = false;
  nextAttempt(s, fx, e.canHls);
}

export function step(prev: RecoveryState, e: RecoveryEvent): Step {
  const s: RecoveryState = { ...prev, want: prev.want && { ...prev.want } };
  const fx: RecoveryEffect[] = [];
  if (s.phase === "ended" || s.phase === "blocked") {
    if (e.t === "released" && s.conn === "releasing") s.conn = "closed";
    return { state: s, effects: fx };
  }
  switch (e.t) {
    case "stall":
      if (s.phase === "playing") {
        s.phase = "stalled";
        if (!s.live && e.pos > 5) s.resumeAt = e.pos;
      }
      break;
    case "fault":
      if ((s.phase === "playing" || s.phase === "stalled") && s.unstable) {
        if (!s.live && e.pos > 5) s.resumeAt = e.pos;
        s.unstable = false;
        nextAttempt(s, fx, e.canHls);
      } else if (s.phase === "playing" || s.phase === "stalled") begin(s, fx, e);
      else if (s.phase === "reconnecting" || s.phase === "switched") {
        // a failure while an attempt is already being released/queued is the same failure
        if (s.conn === "releasing" || s.conn === "closed") break;
        nextAttempt(s, fx, e.canHls);
      }
      break;
    case "retryNow":
      // mashing Retry must not flood /play: ignore while an open is in flight and within the debounce window
      if (s.conn === "opening" || (e.now != null && s.lastRetryAt != null && e.now - s.lastRetryAt < RETRY_DEBOUNCE_MS)) break;
      if (e.now != null) s.lastRetryAt = e.now;
      s.reason = null;
      s.hiddenStop = false;
      s.resumeAt = s.live ? null : e.pos > 5 ? e.pos : s.resumeAt;
      s.attempt = 1;
      s.max = MAX_ATTEMPTS;
      s.switched = false;
      s.unstable = false;
      s.phase = "reconnecting";
      fx.push({ do: "cancelTimers" });
      reopen(s, fx, 0);
      break;
    case "opened":
      if (s.conn === "opening") s.conn = "held";
      break;
    case "recovered":
      if (active(s) && s.conn === "held") {
        s.phase = "playing";
        // attempts are only forgiven after STABLE_MS of continuous playback ("stable")
        s.unstable = s.attempt > 0;
        s.resumeAt = null;
      }
      break;
    case "stable":
      if (s.phase === "playing" && s.unstable) {
        s.unstable = false;
        s.attempt = 0;
        s.max = MAX_ATTEMPTS;
      }
      break;
    case "released":
      // only the release we asked for counts; a stale one must never close a stream that is held
      if (s.conn !== "releasing") break;
      s.conn = "closed";
      maybeOpen(s, fx);
      break;
    case "offline":
      s.offline = true;
      fx.push({ do: "cancelTimers" });
      // a queued open that has not started yet waits for the network, with no extra backoff when it returns
      if (s.want) s.want = { delayMs: 0 };
      // an open already scheduled or in flight is rolled back (its timer was just cancelled)
      else if (s.conn === "opening") {
        s.want = { delayMs: 0 };
        release(s, fx);
      }
      break;
    case "online":
      if (!s.offline) break;
      s.offline = false;
      if (s.want) s.want = { delayMs: 0 };
      maybeOpen(s, fx);
      break;
    case "hidden":
      s.hiddenSince = e.now;
      break;
    case "hiddenTimeout":
      // Deliberate: a healthy stream in a background tab is left alone (people listen with the tab hidden); we only stop an active recovery.
      if (active(s) && s.hiddenSince != null) {
        s.hiddenStop = true;
        s.want = null;
        fx.push({ do: "cancelTimers" });
        release(s, fx);
      }
      break;
    case "visible": {
      const long = s.hiddenStop || (s.hiddenSince != null && e.now - s.hiddenSince >= HIDDEN_STOP_MS);
      s.hiddenSince = null;
      s.hiddenStop = false;
      if (long && active(s)) {
        // fresh attempt on return
        s.unstable = false;
        s.attempt = 1;
        s.max = s.switched ? SWITCH_ATTEMPTS : MAX_ATTEMPTS;
        s.phase = s.switched ? "switched" : "reconnecting";
        reopen(s, fx, 0);
      } else maybeOpen(s, fx);
      break;
    }
    case "fatal":
      s.phase = "denied";
      s.reason = e.reason;
      s.want = null;
      fx.push({ do: "cancelTimers" });
      release(s, fx);
      break;
    case "blocked":
      s.phase = "blocked";
      s.want = null;
      fx.push({ do: "cancelTimers" });
      release(s, fx);
      break;
    case "cancel":
      s.phase = "ended";
      s.want = null;
      fx.push({ do: "cancelTimers" });
      release(s, fx);
      fx.push({ do: "exit" });
      break;
  }
  return { state: s, effects: fx };
}

/** Calm user-facing line for the state; null while playing normally. Never contains error codes. */
export function recoveryCopy(s: RecoveryState): string | null {
  switch (s.phase) {
    case "stalled":
      return "Stream is buffering…";
    case "reconnecting":
    case "switched":
      if (s.offline) return "You're offline. Waiting for a connection…";
      if (s.phase === "switched" && s.attempt <= 1 && s.conn !== "held") return "Switched to a backup stream";
      return `Reconnecting… (${s.attempt} of ${s.max})`;
    case "denied":
      switch (s.reason) {
        case "auth": return "The provider refused the login. Check it in Settings → Live TV / IPTV.";
        case "denied": return "This stream isn't available to this account.";
        case "missing": return "This stream couldn't be found.";
        case "nocatchup": return "This channel has no catch-up for that time.";
        case "link": return "The stream link is no longer valid. Retry to get a fresh one.";
        case "rate": return "The provider is limiting connections; try again in a minute.";
        default: return "This stream can't be played right now.";
      }
    case "failed":
      return s.live ? "This channel isn't responding. Try another channel or retry." : "This stream isn't responding. Try again or go back.";
    default:
      return null;
  }
}

/** Final refusals: never put on the backoff. null = not a permanent refusal. */
export function permanentReason(status: number, code?: string | null): DeniedReason | null {
  if (code === "provider_auth") return "auth";
  if (code === "no_catchup") return "nocatchup";
  if (code === "bad_token" || code === "expired" || code === "stale" || status === 410) return "link";
  if (status === 429) return "rate";
  if (status === 401 || status === 403) return "denied";
  if (status === 404) return "missing";
  return null;
}

/** Whether a failed stream link is worth retrying (network, server and playback faults) rather than final. */
export function isRetryable(status: number, code?: string | null): boolean {
  if (permanentReason(status, code)) return false;
  if (status === 0 || status === 408) return true;
  if (status >= 500) return true;
  return status >= 200 && status < 300;
}
