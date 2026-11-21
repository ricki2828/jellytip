// Round 3 sport cards ported from the TV (jellytip/iptv/SportWow.kt): the kick-off card for a followed team with no reminder (1B) and
// the full-time card's "what's next" (3). Pure; tested in test/sportWow.test.ts.
import { isFavourite, pickChannel, type FxItem } from "./gameCentre.ts";

type Refs = { teamId: string; leagueKey?: string | null }[];

// ---------- kick-off card ----------

/** Announce this long before kick-off ("Kick-off in 2 min"). */
export const KICKOFF_SOON_SEC = 150;
/** And once more at kick-off, while the game is this young. */
export const KICKOFF_LIVE_SEC = 300;
export const KICKOFF_SHOW_MS = 15_000;

export interface KickoffDue {
  fixture: FxItem;
  phase: "soon" | "live";
}
export const kickoffKey = (d: KickoffDue) => `${d.fixture.fid}:${d.phase}`;

/**
 * The first favourite fixture due an announcement. [nudged] holds "fid:phase" already shown; a fixture with a reminder is the
 * reminder toast's (no double nudge); one whose channel is playing is already being watched; a muted one never. A "live"
 * announcement also counts as "soon" (never both for one game).
 */
export function kickoffDue(
  items: readonly FxItem[],
  nowSec: number,
  nudged: ReadonlySet<string>,
  o: { refs?: Refs; playingChannel?: string | null; hasReminder?: (f: FxItem) => boolean; muted?: (f: FxItem) => boolean } = {},
): KickoffDue | null {
  for (const f of [...items].sort((a, b) => a.kickoff - b.kickoff)) {
    if (!isFavourite(f, o.refs ?? []) || f.reminderId != null || o.hasReminder?.(f) || o.muted?.(f) || !f.channels.length) continue;
    if (o.playingChannel && f.channels.some((c) => c.id === o.playingChannel)) continue;
    const until = f.kickoff - nowSec;
    const phase: KickoffDue["phase"] | null =
      f.status === "pre" && until > 0 && until <= KICKOFF_SOON_SEC
        ? "soon"
        : (f.status === "in" || (f.status === "pre" && until <= 0)) && nowSec - f.kickoff >= 0 && nowSec - f.kickoff <= KICKOFF_LIVE_SEC
          ? "live"
          : null;
    if (!phase) continue;
    if (nudged.has(`${f.fid}:${phase}`)) continue;
    if (phase === "soon" && nudged.has(`${f.fid}:live`)) continue;
    return { fixture: f, phase };
  }
  return null;
}

/** "Kick-off in 2 min" / "Kick-off now". */
export const kickoffHeadline = (d: KickoffDue, nowSec: number): string =>
  d.phase === "live" ? "Kick-off now" : `Kick-off in ${Math.max(1, Math.ceil((d.fixture.kickoff - nowSec) / 60))} min`;

// ---------- full time: what's next ----------

/** Upcoming games further away than this aren't "next". */
export const NEXT_WITHIN_SEC = 60 * 60;
/** The full-time card tucks itself away after this long. */
export const FULLTIME_SHOW_MS = 30_000;

/**
 * Next game: a live favourite first, then the soonest favourite starting within the hour, then a live game (server rank
 * order, as listed), then the soonest upcoming within the hour. Excludes the game just finished and games without a channel.
 * Null = nothing (the card offers Pick for tonight instead).
 */
export function nextGame(items: readonly FxItem[], exclude: ReadonlySet<string>, nowSec: number, refs: Refs = [], muted?: (f: FxItem) => boolean): FxItem | null {
  const ok = items.filter(
    (f) =>
      !exclude.has(f.fid) &&
      (f.matchId == null || !exclude.has(f.matchId)) &&
      !muted?.(f) &&
      pickChannel(f, nowSec).channel != null &&
      (f.status === "in" || (f.status === "pre" && f.kickoff - nowSec >= 0 && f.kickoff - nowSec <= NEXT_WITHIN_SEC)),
  );
  const fav = (f: FxItem) => isFavourite(f, refs);
  const soonest = (l: FxItem[]) => l.reduce<FxItem | null>((a, f) => (!a || f.kickoff < a.kickoff ? f : a), null);
  return ok.find((f) => fav(f) && f.status === "in") ?? soonest(ok.filter(fav)) ?? ok.find((f) => f.status === "in") ?? soonest(ok.filter((f) => f.status === "pre"));
}

/** "Live now" / "Starts in 12 min". */
export const nextLine = (f: Pick<FxItem, "status" | "kickoff">, nowSec: number): string =>
  f.status === "in" ? "Live now" : `Starts in ${Math.max(1, Math.ceil((f.kickoff - nowSec) / 60))} min`;
