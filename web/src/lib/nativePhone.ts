// Phone app (native ExoPlayer activity) decisions, kept pure for tests (test/nativePhone.test.ts). The native player covers
// the web app, so web cards that float over the web player (kick-off, full time) become short notes inside it, and a stream
// the web player would retry is retried here too. Audit: ~/ai/state/jellytip-phone-gap-20261121.md.
import { fxScoreText, fxTitle, pickChannel, type FxItem } from "./gameCentre.ts";
import { kickoffHeadline, nextLine, type KickoffDue } from "./sportWow.ts";
import { permanentReason } from "./streamRecovery.ts";

/** A native session that played this long earns a fresh set of retries (one drop an hour must not use them all up). */
export const NATIVE_STABLE_MS = 2 * 60_000;

/** Waits before each fresh-link retry of a native stream that failed (then it gives up). */
export const NATIVE_RETRY_MS = [2_000, 5_000, 10_000] as const;

const NETWORK = new Set(["ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", "ERROR_CODE_IO_UNSPECIFIED"]);

/**
 * How long to wait before asking /play again after the native player closed with an error, or null = show the error.
 * Retried (as the web player's recovery does): a server/provider 5xx (the 19-Nov 502s) and a connection the player's own
 * reconnects couldn't save. Never: auth / link / rate / missing refusals, decode faults, or past the last attempt.
 */
export function nativeRetryDelayMs(r: { httpStatus?: number; errorCode?: string; iptvError?: string }, attempt: number): number | null {
  if (attempt < 0 || attempt >= NATIVE_RETRY_MS.length) return null;
  const status = r.httpStatus ?? 0;
  if (permanentReason(status, r.iptvError)) return null;
  const retry = status >= 500 || (status === 0 && NETWORK.has(r.errorCode ?? ""));
  return retry ? NATIVE_RETRY_MS[attempt] : null;
}

/** The kick-off card as a note in the native player: "Kick-off now: Arsenal v Spurs · Sky Sport 1". */
export function kickoffNotice(d: KickoffDue, nowSec: number): string {
  const c = pickChannel(d.fixture, nowSec).channel;
  return `${kickoffHeadline(d, nowSec)}: ${fxTitle(d.fixture)}${c ? ` · ${c.name}` : ""}`;
}

/**
 * The full-time card as a note: "Full time. Next: Leeds v Hull, starts in 20 min". The score only for people with scores on
 * (and only when the list carries one); never the next game's score.
 */
export function fullTimeNotice(f: Pick<FxItem, "home" | "away" | "score">, next: FxItem | null, nowSec: number, spoilers: boolean): string {
  const score = spoilers ? fxScoreText(f) : null;
  const head = score ? `Full time · ${score}.` : "Full time.";
  return next ? `${head} Next: ${fxTitle(next)}, ${nextLine(next, nowSec).toLowerCase()}` : head;
}

/** The address Movie night shares: in the app the page is https://localhost/…, so the public web app's address instead. */
export const shareBase = (native: boolean, serverOrigin: string, href: string): string => (native && serverOrigin ? `${serverOrigin.replace(/\/+$/, "")}/jellytip/` : href);
