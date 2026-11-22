// Catch me up when joining a live game late (port of the TV's CatchUpPolicy, jellytip/iptv/SportWow.kt). Joining 10+ min in
// offers up to three choices, with the score held back until one is picked: from kick-off (when the channel can start over), catch
// me up (when there are cards: up to 12 of the key plays the spoiler delay has released, 5 s each, then live) or live. When only
// Live would be left there is no card. Pure; tested in test/catchUp.test.ts.
import { TIMELINE_POINTS, type LiveStats } from "./liveStats.ts";

/** Joining at least this far into a game offers the choice. */
export const LATE_SEC = 10 * 60;
/** The choice goes to Live by itself after this long. */
export const CHOICE_TIMEOUT_MS = 20_000;
export const CARD_MS = 5_000;
/** Twelve cards at 5 s: the "60 s of key plays". */
export const MAX_CARDS = 12;
/** How long the player waits for the first live answer before deciding (the score is held meanwhile). */
export const DECIDE_WAIT_MS = 4_000;

/**
 * A play's `wallclock` is when it really happened (ESPN), earlier than when the stream shows it (broadcast + provider lag), which is
 * what the reveal clock is calibrated to. A play is asked about this much later than its wallclock so a recap card never spoils
 * what the picture has not reached yet.
 */
export const WALLCLOCK_SLACK_SEC = 60;

export interface CatchCard {
  /** "54'" / "2nd 4:12"; empty when unknown. */
  when: string;
  text: string;
  team: "home" | "away" | null;
  points: number | null;
}

export interface OfferIn {
  live: boolean;
  replay: boolean;
  /** Server status of the latest answer ("pre" | "in" | "post"); null when none yet. */
  status: string | null;
  startsAt: number | null | undefined;
  nowSec: number;
}

/** Offer the choice? Only live (not replay), under way (not pre or post) and at least LATE_SEC past the start. */
export function offer(i: OfferIn): boolean {
  if (!i.live || i.replay || i.startsAt == null) return false;
  const st = i.status ?? (i.startsAt <= i.nowSec ? "in" : "pre");
  return st !== "pre" && st !== "post" && i.nowSec - i.startsAt >= LATE_SEC;
}

type Play = LiveStats["plays"][number];
/** A play's identity across answers: the server id, else what it says and when. */
const playKey = (p: Play) => p.id ?? `${p.period ?? ""}|${p.clock ?? ""}|${p.text}`;

/**
 * Key plays before now, oldest first, only what the reveal clock has released: the rugby league timeline (each entry gated on
 * its own time), else scoring plays, else the released key moments.
 *
 * Scoring plays come from two answers: the newest full answer (`latest`, possibly still held by the spoiler delay as a whole),
 * each play only when it carries a `wallclock` the clock has released (one without is never shown from an unreleased answer),
 * plus every scoring play of the released answer (already released). De-duplicated by play id; the newest MAX_CARDS. Before
 * this, cards came from the released answer only, which for the first 30 s of a join is the light one with no plays: an
 * empty recap.
 */
export function cards(released: LiveStats | null, latest: LiveStats | null, reveal: (atSec?: number) => boolean): CatchCard[] {
  // Nothing at all while reveal is off (replay): asked with no time, the clock answers for "now"
  if (!reveal()) return [];
  const tl = (latest?.timeline ?? []).filter((t) => t.at != null && reveal(t.at));
  if (tl.length) return tl.slice(-MAX_CARDS).map((t) => ({ when: t.clock ?? "", text: t.text, team: t.team, points: TIMELINE_POINTS[t.kind] ?? null }));
  const rel = (released?.plays ?? []).filter((p) => p.scoring);
  const relKeys = new Set(rel.map(playKey));
  // newest first: the latest answer's order, then released plays it no longer carries (older: they fell off its list)
  const seen = new Set<string>();
  const plays: Play[] = [];
  const add = (p: Play) => {
    const k = playKey(p);
    if (seen.has(k)) return;
    seen.add(k);
    plays.push(p);
  };
  if (latest && latest !== released) for (const p of latest.plays) if (p.scoring && (relKeys.has(playKey(p)) || (p.wallclock != null && reveal(p.wallclock + WALLCLOCK_SLACK_SEC)))) add(p);
  for (const p of rel) add(p);
  if (plays.length) return plays.slice(0, MAX_CARDS).reverse().map((p) => ({ when: [p.period, p.clock].filter(Boolean).join(" "), text: p.text, team: p.team, points: p.delta != null && p.delta > 0 ? p.delta : null }));
  const moments = [...(released?.moments ?? []), ...(latest?.moments ?? [])].filter((m) => reveal(m.at));
  const seenM = new Set<string>();
  const uniq = moments
    .sort((a, b) => a.at - b.at)
    .filter((m) => {
      const k = `${m.at}|${m.kind}|${m.team}`;
      return !seenM.has(k) && !!seenM.add(k);
    });
  return uniq.slice(-MAX_CARDS).map((m) => ({ when: "", text: m.label, team: m.team, points: null }));
}

/** "You're joining 25 minutes in" / "You're joining 2 h 5 min in". */
export function joiningLine(startsAt: number, nowSec: number): string {
  const m = Math.max(1, Math.floor((nowSec - startsAt) / 60));
  return m >= 120 ? `You're joining ${Math.floor(m / 60)} h ${m % 60} min in` : `You're joining ${m} minutes in`;
}

export type ChoiceId = "kickoff" | "catchup" | "live";

/**
 * The choices on the card, in order: From kick-off only when the channel can start the programme over (the server's flags),
 * Catch me up only when there are cards to show (`plays` = how many), Live always. Empty when only Live would be left: then
 * there is no card at all (the player goes straight to live).
 */
export function choices(canKickOff: boolean, plays: number): { id: ChoiceId; title: string; sub: string }[] {
  if (!canKickOff && plays <= 0) return [];
  return [
    ...(canKickOff ? [{ id: "kickoff" as const, title: "From kick-off", sub: "Watch the whole game" }] : []),
    ...(plays > 0 ? [{ id: "catchup" as const, title: `Catch me up · ${plays} ${plays === 1 ? "play" : "plays"}`, sub: `${plays * (CARD_MS / 1000)} s of key plays, then live` }] : []),
    { id: "live" as const, title: "Live", sub: "Jump straight in" },
  ];
}

/** The note when From kick-off could not play: the player goes back to live with it. */
export const CATCHUP_FAILED_TEXT = "Catch-up isn't working on this channel right now";
/** The note when From kick-off played a while and then broke. */
export const CATCHUP_STOPPED_TEXT = "Catch-up stopped — back to live";
/** A From kick-off target with no picture this long after it began opening has failed (the provider's dead archive answers 200 + nothing). */
export const KICKOFF_START_MS = 20_000;

export interface KickoffIn {
  /** The player's state ("loading" | "playing" | "error" | "full" ...). */
  state: string;
  /** The recovery machine's phase ("playing" | "failed" | "denied" ...); null for a player without one. */
  phase: string | null;
  /** A picture has been shown on this target. */
  played: boolean;
  /** How long since the target began opening (ms), not counting time held (kickoffClockHeld). */
  sinceMs: number;
  /** The video is paused (autoplay refused, or the viewer paused) / the page is hidden: no picture is expected. */
  paused?: boolean;
  hidden?: boolean;
}

/** Restart the no-picture clock: "every connection is in use" is up, or the video is paused / the page hidden (not the archive's fault). */
export const kickoffClockHeld = (i: KickoffIn): boolean => i.state === "full" || !!i.paused || !!i.hidden;

/**
 * Has a From kick-off (catch-up) target failed? Refused or errored (the server's 400 no_catchup, a dead link, recovery gave up),
 * or no picture KICKOFF_START_MS after it began opening (the provider's dead archive answers 200 + nothing). The player then goes
 * back to the live target with CATCHUP_FAILED_TEXT. "Every connection is in use" (full) keeps its own pop-up.
 */
export function kickoffFailed(i: KickoffIn): boolean {
  if (i.state === "full") return false;
  if (i.state === "error" || i.phase === "failed" || i.phase === "denied") return true;
  return !i.played && !kickoffClockHeld(i) && i.sinceMs >= KICKOFF_START_MS;
}

// Once per match per page load: switching back to the same game does not ask again (a full reload may ask again, which only holds the score)
const offered = new Set<string>();
export const wasOffered = (matchId: string) => offered.has(matchId);
export const markOffered = (matchId: string) => void offered.add(matchId);
