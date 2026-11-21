// Catch me up when joining a live game late (port of the TV's CatchUpPolicy, jellytip/iptv/SportWow.kt). Joining 10+ min in
// offers three choices, with the score held back until one is picked: from kick-off (when the channel can start over), catch me
// up (up to 12 cards of the key plays the spoiler delay has released, 5 s each, then live) or live. Pure; tested in
// test/catchUp.test.ts.
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

/**
 * Key plays before now, oldest first, only what the reveal clock has released: the rugby league timeline (each entry gated on
 * its own time), else the scoring plays of the released answer, else the released key moments.
 */
export function cards(released: LiveStats | null, latest: LiveStats | null, reveal: (atSec?: number) => boolean): CatchCard[] {
  // Nothing at all while reveal is off (replay): asked with no time, the clock answers for "now"
  if (!reveal()) return [];
  const tl = (latest?.timeline ?? []).filter((t) => t.at != null && reveal(t.at));
  if (tl.length) return tl.slice(-MAX_CARDS).map((t) => ({ when: t.clock ?? "", text: t.text, team: t.team, points: TIMELINE_POINTS[t.kind] ?? null }));
  const plays = (released?.plays ?? []).filter((p) => p.scoring);
  // plays arrive newest first
  if (plays.length) return plays.slice(0, MAX_CARDS).reverse().map((p) => ({ when: [p.period, p.clock].filter(Boolean).join(" "), text: p.text, team: p.team, points: p.delta != null && p.delta > 0 ? p.delta : null }));
  const moments = [...(released?.moments ?? []), ...(latest?.moments ?? [])].filter((m) => reveal(m.at));
  const seen = new Set<string>();
  const uniq = moments
    .sort((a, b) => a.at - b.at)
    .filter((m) => {
      const k = `${m.at}|${m.kind}|${m.team}`;
      return !seen.has(k) && !!seen.add(k);
    });
  return uniq.slice(-MAX_CARDS).map((m) => ({ when: "", text: m.label, team: m.team, points: null }));
}

/** "You're joining 25 minutes in" / "You're joining 2 h 5 min in". */
export function joiningLine(startsAt: number, nowSec: number): string {
  const m = Math.max(1, Math.floor((nowSec - startsAt) / 60));
  return m >= 120 ? `You're joining ${Math.floor(m / 60)} h ${m % 60} min in` : `You're joining ${m} minutes in`;
}

/** The choices on the card, in order (From kick-off only when the channel can start the programme over). */
export function choices(canKickOff: boolean): { id: "kickoff" | "catchup" | "live"; title: string; sub: string }[] {
  const all = [
    { id: "kickoff" as const, title: "From kick-off", sub: "Watch the whole game" },
    { id: "catchup" as const, title: "Catch me up", sub: "60 s of key plays, then live" },
    { id: "live" as const, title: "Live", sub: "Jump straight in" },
  ];
  return canKickOff ? all : all.slice(1);
}

// Once per match per page load: switching back to the same game does not ask again (a full reload may ask again, which only holds the score)
const offered = new Set<string>();
export const wasOffered = (matchId: string) => offered.has(matchId);
export const markOffered = (matchId: string) => void offered.add(matchId);
