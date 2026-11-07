// Failed-stream memory, client side (7-Nov "remember failed streams"). The companion remembers failures (stream_fail) and tells us
// per channel / VOD copy / episode: `lastFail: {at, reason, count7d}` (present only while the newest failure is newer than the last
// good play). This file: the parsed shape, a small overlay of failures reported from THIS device (so a ding shows at once, before
// any list refreshes), the "skip what failed earlier" rule, the note text, and the outcome report. Pure apart from `report`.
//
// Contract: POST v1/iptv/play/outcome {id, kind: live|vod|episode|catchup, ok, reason?, posSec?}; ~/ai/state/jellytip-fixtures-contract.md.

export type FailReason = "open_failed" | "unreachable" | "refused" | "stall" | "recovery_gave_up" | "user_report";
export const FAIL_REASONS: readonly FailReason[] = ["open_failed", "unreachable", "refused", "stall", "recovery_gave_up", "user_report"];
export interface LastFail {
  /** Epoch seconds of the newest failure. */
  at: number;
  reason: FailReason;
  /** Failures reported in the last 7 days. */
  count7d: number;
}

/** Failed within this long ago = skipped / sorted below working peers. */
export const DING_S = 24 * 3600;
/** A success counts (resets the server's streak) only after this much playback. */
export const GOOD_PLAY_S = 60;

/** Parse a payload's lastFail for an id and remember it (lists -> player). */
export function parseFailFor(id: string, raw: unknown): LastFail | null {
  const lf = parseLastFail(raw);
  noteServerFail(id, lf);
  return lf;
}

export function parseLastFail(raw: unknown): LastFail | null {
  const r = raw as Record<string, unknown> | null;
  if (!r || typeof r !== "object") return null;
  const at = r.at;
  if (typeof at !== "number" || !isFinite(at) || at <= 0) return null;
  const reason = FAIL_REASONS.includes(r.reason as FailReason) ? (r.reason as FailReason) : "open_failed";
  const n = typeof r.count7d === "number" && isFinite(r.count7d) ? Math.max(1, Math.floor(r.count7d)) : 1;
  return { at, reason, count7d: n };
}

// ---- overlay of failures reported from this device (id -> LastFail); a payload's own lastFail wins when newer ----
const local = new Map<string, LastFail>();
const listeners = new Set<() => void>();
export const onFailChange = (fn: () => void): (() => void) => { listeners.add(fn); return () => { listeners.delete(fn); }; };
const changed = () => listeners.forEach((f) => f());

export function rememberLocalFail(id: string, reason: FailReason, nowS: number): void {
  const prev = local.get(id);
  local.set(id, { at: nowS, reason, count7d: (prev && prev.at > nowS - 7 * 86400 ? prev.count7d : 0) + 1 });
  changed();
}
export function forgetLocalFail(id: string): void {
  if (local.delete(id)) changed();
}
export function clearLocalFails(): void { local.clear(); server.clear(); changed(); }

// ---- what the latest payloads said about an id (so a list's note survives into the player, which only gets ids and names) ----
const server = new Map<string, LastFail>();
export function noteServerFail(id: string, lf: LastFail | null | undefined): void {
  if (lf) server.set(id, lf);
  else server.delete(id);
}
export function clearServerFails(): void { server.clear(); }

/** The failure to show for an id: the newer of the payload's lastFail (given, else the last one a list reported) and this device's own report. */
export function failOf(id: string, given?: LastFail | null): LastFail | null {
  const fromPayload = given ?? server.get(id) ?? null;
  const l = local.get(id) ?? null;
  if (l && fromPayload) return l.at > fromPayload.at ? { ...l, count7d: Math.max(l.count7d, fromPayload.count7d) } : fromPayload;
  return l ?? fromPayload ?? null;
}
export const failedLately = (lf: LastFail | null | undefined, nowS: number): boolean => !!lf && lf.at > nowS - DING_S;

/** "Failed 2 h ago" / "Failed 3× this week" (3+ reports in 7 days wins over the age), null when nothing failed in the last 7 days. */
export function failNote(lf: LastFail | null | undefined, nowS: number): string | null {
  if (!lf) return null;
  const age = nowS - lf.at;
  if (age > 7 * 86400) return null;
  if (lf.count7d >= 3) return `Failed ${lf.count7d}× this week`;
  if (age < 90) return "Failed just now";
  if (age < 3600) return `Failed ${Math.round(age / 60)} min ago`;
  if (age < 86400) return `Failed ${Math.round(age / 3600)} h ago`;
  const d = Math.round(age / 86400);
  return `Failed ${d} day${d === 1 ? "" : "s"} ago`;
}

export interface Pickable { id: string; name: string; lastFail?: LastFail | null }
export interface Pick<T extends Pickable> { chosen: T; /** Name of the first choice that was passed over because it failed earlier (null = nothing skipped). */ skipped: string | null }

/**
 * Which of [first, ...rest] to play. A first choice that failed in the last 24 h is passed over for the first alternative that did not,
 * when there is one; if all failed lately the first choice stays (nothing better to offer). Manual picks never go through this.
 */
export function pickPlayable<T extends Pickable>(first: T, rest: readonly T[], nowS: number): Pick<T> {
  const bad = (c: T) => failedLately(failOf(c.id, c.lastFail), nowS);
  if (!bad(first)) return { chosen: first, skipped: null };
  const alt = rest.find((c) => c.id !== first.id && !bad(c));
  return alt ? { chosen: alt, skipped: first.name } : { chosen: first, skipped: null };
}
export const skippedNote = (name: string) => `Skipped ${name}: failed earlier`;

/** The next thing to offer after "This stream isn't working": the first of `others` (in order) that has not failed lately, else null. */
export function nextAfterFailure<T extends Pickable>(current: string, others: readonly T[], nowS: number): T | null {
  return others.find((c) => c.id !== current && !failedLately(failOf(c.id, c.lastFail), nowS)) ?? null;
}

// ---- outcome report ----
export type OutcomeKind = "live" | "vod" | "episode" | "catchup";
export interface Outcome { id: string; kind: OutcomeKind; ok: boolean; reason?: FailReason; posSec?: number }

export function outcomeBody(o: Outcome): Record<string, unknown> {
  return { id: o.id, kind: o.kind, ok: o.ok, ...(o.ok ? {} : { reason: o.reason ?? "open_failed" }), ...(o.posSec != null ? { posSec: Math.max(0, Math.floor(o.posSec)) } : {}) };
}

/** Target of the player -> the id + kind the companion wants; null for recordings (not a provider stream). */
export function outcomeTarget(t: { channel?: string; start?: number; vod?: string; episode?: string; recording?: string }): { id: string; kind: OutcomeKind } | null {
  if (t.recording) return null;
  if (t.channel) return { id: t.channel, kind: t.start != null ? "catchup" : "live" };
  if (t.vod) return { id: t.vod, kind: "vod" };
  if (t.episode) return { id: t.episode, kind: "episode" };
  return null;
}

/** Idempotent per viewing session: one failure report (the first) unless the person says so themselves; a success once. */
export class OutcomeSession {
  private failed = false;
  private okSent = false;
  private send: (o: Outcome) => void;
  private nowS: () => number;
  constructor(send: (o: Outcome) => void, nowS: () => number = () => Date.now() / 1000) {
    this.send = send;
    this.nowS = nowS;
  }
  /** -> true when a report went out. */
  fail(target: { id: string; kind: OutcomeKind } | null, reason: FailReason, posSec?: number): boolean {
    if (!target) return false;
    if (this.failed && reason !== "user_report") return false;
    this.failed = true;
    this.okSent = false;
    rememberLocalFail(target.id, reason, this.nowS());
    this.send({ ...target, ok: false, reason, posSec });
    return true;
  }
  /** Call with seconds of continuous playback; reports once at >= 60 s and clears the local ding. */
  played(target: { id: string; kind: OutcomeKind } | null, playedSec: number): boolean {
    if (!target || this.okSent || playedSec < GOOD_PLAY_S) return false;
    this.okSent = true;
    this.failed = false;
    forgetLocalFail(target.id);
    this.send({ ...target, ok: true, posSec: playedSec });
    return true;
  }
  get hasFailed() { return this.failed; }
}
