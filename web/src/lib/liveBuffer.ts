// Live-TV buffer profiles and the Auto policy. Pure: the player hook (screens/iptv/useLiveBuffer.ts) applies the numbers.
//
// A profile is "how far behind the live edge we are willing to sit so a network hiccup does not freeze the picture".
//   mpegts (.ts): the library's own latency chaser is OFF; the hook runs the same direct seek itself from `chaseMax`/`chaseTo`, and
//     builds a cushion by briefly holding playback (`stallHold` after a rebuffer; `startHold` after a (re)attach only for High/Max, so zapping stays instant), all at runtime.
//   hls.js (.m3u8): liveSyncDurationCount / liveMaxLatencyDurationCount in SEGMENTS (a fixed 8 s would sit inside one segment on
//     6-10 s playlists), plus maxBufferLength. hls.config is read live, so changing profile needs no reload.

export type Profile = "low" | "normal" | "high" | "max";
export type Mode = "auto" | Profile;
export const PROFILES: readonly Profile[] = ["low", "normal", "high", "max"];
export const MODES: readonly Mode[] = ["auto", ...PROFILES];

export interface ProfileSpec {
  /** Rough distance behind the live edge the profile aims at, seconds (UI/doc only). */
  behindSec: number;
  /** mpegts: lag above this is chased back (direct seek) to `chaseTo` behind the edge. */
  chaseMax: number;
  chaseTo: number;
  /** mpegts: seconds of runway to build (by holding playback) after a (re)attach / after a rebuffer. 0 = never hold. */
  startHold: number;
  stallHold: number;
  /** mpegts IO stash (applies at next attach only; undefined = library default). */
  stashInitialSize?: number;
  /** hls.js */
  syncCount: number;
  maxLatencyCount: number;
  maxBufferLength: number;
  maxMaxBufferLength: number;
}

export const SPEC: Record<Profile, ProfileSpec> = {
  // Low = the behaviour before profiles existed (chase 6 s -> 1.5 s, library default stash), hls.js 2 segments
  low: { behindSec: 3, chaseMax: 6, chaseTo: 1.5, startHold: 0, stallHold: 0, syncCount: 2, maxLatencyCount: 4, maxBufferLength: 30, maxMaxBufferLength: 60 },
  normal: { behindSec: 8, chaseMax: 14, chaseTo: 8, startHold: 0, stallHold: 6, stashInitialSize: 384 * 1024, syncCount: 3, maxLatencyCount: 6, maxBufferLength: 30, maxMaxBufferLength: 60 },
  high: { behindSec: 15, chaseMax: 22, chaseTo: 15, startHold: 6, stallHold: 11, stashInitialSize: 768 * 1024, syncCount: 5, maxLatencyCount: 9, maxBufferLength: 45, maxMaxBufferLength: 90 },
  max: { behindSec: 30, chaseMax: 38, chaseTo: 30, startHold: 6, stallHold: 22, stashInitialSize: 1536 * 1024, syncCount: 8, maxLatencyCount: 14, maxBufferLength: 60, maxMaxBufferLength: 120 },
};

export const profileLabel = (p: Profile) => p[0].toUpperCase() + p.slice(1);
export const modeLabel = (m: Mode, effective: Profile) => (m === "auto" ? `Auto (${profileLabel(effective)})` : profileLabel(m));
export const isMode = (v: unknown): v is Mode => typeof v === "string" && (MODES as readonly string[]).includes(v);
export const isProfile = (v: unknown): v is Profile => typeof v === "string" && (PROFILES as readonly string[]).includes(v);

export function hlsConfig(p: Profile) {
  const s = SPEC[p];
  return { liveSyncDurationCount: s.syncCount, liveMaxLatencyDurationCount: s.maxLatencyCount, maxBufferLength: s.maxBufferLength, maxMaxBufferLength: s.maxMaxBufferLength };
}

/** Live window this deep is the most a profile may sit behind: the spoiler delay treats 120 s as a replay (delayMath REPLAY_LAG_SEC). */
export const HLS_SYNC_CAP_SEC = 90;
export const HLS_MAX_LATENCY_CAP_SEC = 100;

/**
 * Runtime hls.js numbers once the playlist is known: the sync distance never exceeds what the live window can hold (fragments - 2),
 * nor 90 s; max latency is set in seconds so it can never cross the 120 s replay threshold.
 */
export function hlsRuntime(p: Profile, fragments: number, targetDuration: number) {
  const s = SPEC[p];
  const td = targetDuration > 0 ? targetDuration : 0;
  let sync = s.syncCount;
  if (fragments > 0) sync = Math.min(sync, fragments - 2);
  if (td > 0) sync = Math.min(sync, Math.floor(HLS_SYNC_CAP_SEC / td));
  sync = Math.max(1, sync);
  const maxCount = Math.max(sync + 1, s.maxLatencyCount);
  return {
    liveSyncDurationCount: sync,
    liveMaxLatencyDurationCount: maxCount,
    liveMaxLatencyDuration: td > 0 ? Math.min(maxCount * td, HLS_MAX_LATENCY_CAP_SEC) : undefined,
    maxBufferLength: s.maxBufferLength,
    maxMaxBufferLength: s.maxMaxBufferLength,
  };
}

/** A hold that ended on max-hold or no-new-media says the transport is not delivering: do not chain another hold for this long. */
export const HOLD_COOLDOWN_MS = 60_000;
export const holdAllowed = (lastBadEnd: number, now: number) => !lastBadEnd || now - lastBadEnd >= HOLD_COOLDOWN_MS;

// ---- Auto policy -----------------------------------------------------------------------------------------------------

/** Auto only ever climbs to High by itself; Max is a manual choice. It never drops below Normal, so a quiet channel does not oscillate. */
export const AUTO_MIN: Profile = "normal";
export const AUTO_MAX: Profile = "high";
export const STEP_DOWN_MS = 10 * 60_000;
/** Rebuffer events closer together than this are one stall. */
export const DEBOUNCE_MS = 3_000;
/** After a step the new cushion is still being built: stalls in this window are counted but do not step again. */
export const GRACE_MS = 20_000;
export const TOAST_EVERY_MS = 5 * 60_000;

export interface AutoState { level: Profile; lastStall: number; lastChange: number; startedAt: number; stalls: number }

const clampAuto = (p: Profile): Profile => (PROFILES.indexOf(p) < PROFILES.indexOf(AUTO_MIN) ? AUTO_MIN : PROFILES.indexOf(p) > PROFILES.indexOf(AUTO_MAX) ? AUTO_MAX : p);

export function initAuto(remembered: Profile | null, now: number): AutoState {
  return { level: clampAuto(remembered ?? AUTO_MIN), lastStall: 0, lastChange: 0, startedAt: now, stalls: 0 };
}

export function onStall(s: AutoState, now: number): { state: AutoState; counted: boolean; stepped: boolean } {
  if (s.lastStall && now - s.lastStall < DEBOUNCE_MS) return { state: s, counted: false, stepped: false };
  const st = { ...s, lastStall: now, stalls: s.stalls + 1 };
  if (s.lastChange && now - s.lastChange < GRACE_MS) return { state: st, counted: true, stepped: false };
  const next = PROFILES[Math.min(PROFILES.indexOf(s.level) + 1, PROFILES.indexOf(AUTO_MAX))];
  if (next === s.level) return { state: st, counted: true, stepped: false };
  return { state: { ...st, level: next, lastChange: now }, counted: true, stepped: true };
}

export function onTick(s: AutoState, now: number): { state: AutoState; stepped: boolean } {
  if (PROFILES.indexOf(s.level) <= PROFILES.indexOf(AUTO_MIN)) return { state: s, stepped: false };
  if (now - Math.max(s.lastStall, s.lastChange, s.startedAt) < STEP_DOWN_MS) return { state: s, stepped: false };
  return { state: { ...s, level: PROFILES[PROFILES.indexOf(s.level) - 1], lastChange: now }, stepped: true };
}

export const effectiveProfile = (mode: Mode, s: AutoState): Profile => (mode === "auto" ? s.level : mode);
export const toastDue = (lastToastAt: number, now: number) => !lastToastAt || now - lastToastAt >= TOAST_EVERY_MS;

// ---- mpegts runtime decisions ------------------------------------------------------------------------------------------

/** Seek target (seconds) when playback has drifted further behind the edge than the profile allows, else null. */
export function chaseTarget(p: Profile, bufferedEnd: number, currentTime: number): number | null {
  const s = SPEC[p];
  return bufferedEnd - currentTime > s.chaseMax ? bufferedEnd - s.chaseTo : null;
}

/** Seconds of runway to hold for ("start" after an attach, "stall" after a rebuffer); 0 = do not hold. Holds only while runway is short. */
export function holdTarget(p: Profile, phase: "start" | "stall", runway: number): number {
  const t = phase === "start" ? SPEC[p].startHold : SPEC[p].stallHold;
  return t > 0 && runway < t - 0.5 ? t : 0;
}

export const HOLD_MAX_MS = 25_000;
/** No new media for this long during a hold: stop holding so the normal stall/recovery logic sees the dead stream. */
export const HOLD_NO_GROWTH_MS = 3_000;

// ---- storage -----------------------------------------------------------------------------------------------------------

export interface KV { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void }
export const DEFAULT_KEY = "jellytip.liveBuffer.default";
const chKey = (channelKey: string) => `jellytip.liveBuffer.ch.${channelKey}`;

export function loadDefaultMode(kv: KV | null): Mode {
  try { const v = kv?.getItem(DEFAULT_KEY); return isMode(v) ? v : "auto"; } catch { return "auto"; }
}
export function saveDefaultMode(kv: KV | null, m: Mode) { try { kv?.setItem(DEFAULT_KEY, m); } catch { /* private mode */ } }
export function loadChannelLevel(kv: KV | null, channelKey: string | null): Profile | null {
  if (!channelKey) return null;
  try { const v = kv?.getItem(chKey(channelKey)); return isProfile(v) ? clampAuto(v) : null; } catch { return null; }
}
/** Normal is the default, so it clears the entry instead of storing one. */
export function saveChannelLevel(kv: KV | null, channelKey: string | null, level: Profile) {
  if (!channelKey) return;
  try { if (level === AUTO_MIN) kv?.removeItem(chKey(channelKey)); else kv?.setItem(chKey(channelKey), level); } catch { /* private mode */ }
}
