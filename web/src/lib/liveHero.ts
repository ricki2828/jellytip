// Live match hero (TV port: jellytip/iptv/JellytipLiveHero.kt `LiveHero`, Game Centre look 2A). Which fixtures the hero shows:
// on Home only live games of his starred teams or followed leagues (none: the normal hero; Ricki 21-Nov, no top-ranked fallback);
// on Game Centre those, else the top-ranked live game, else his next game within a day, else the top-ranked game starting within a day. Spoiler-safe: a tag and the teams, never a score. Pure; tested in
// test/liveHero.test.ts.
import { isFavourite, pickChannel, type FxItem } from "./gameCentre.ts";
import type { Muted } from "./prefs.ts";

const DAY_S = 24 * 3600;

type Refs = { teamId: string; leagueKey?: string | null }[];

const same = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

/** A fixture of a league or sport the person muted ("Not interested"). */
export function isMuted(f: Pick<FxItem, "league" | "leagueKey" | "sport">, muted: Muted | null | undefined): boolean {
  if (!muted) return false;
  const lg = [f.league, f.leagueKey].filter((x): x is string => !!x);
  return muted.leagues.some((m) => lg.some((l) => same(l, m))) || (!!f.sport && muted.sports.some((m) => same(m, f.sport!)));
}

const cmp = (a: FxItem, b: FxItem) => (a.rank ?? Infinity) - (b.rank ?? Infinity) || b.significance - a.significance || a.kickoff - b.kickoff || a.fid.localeCompare(b.fid);

/** A fixture of a league he follows (prefs sport.leagues, by name or key, as muting matches). */
export const followsLeague = (f: Pick<FxItem, "league" | "leagueKey">, leagues: readonly string[] = []): boolean =>
  leagues.some((m) => [f.league, f.leagueKey].some((l) => !!l && same(l, m)));

const liveWatchable = (items: readonly FxItem[], nowSec: number, muted?: Muted | null) =>
  items.filter((f) => f.status === "in" && !isMuted(f, muted) && pickChannel(f, nowSec).channel != null);

/** Home: live fixtures with somewhere to watch of his starred teams or followed leagues (ranked). Nothing else: no fallback game. */
export function homePicks(items: readonly FxItem[], nowSec: number, refs: Refs = [], muted?: Muted | null, leagues: readonly string[] = []): FxItem[] {
  return liveWatchable(items, nowSec, muted).filter((f) => isFavourite(f, refs) || followsLeague(f, leagues)).sort(cmp);
}

/** Game Centre's hero (the sport page keeps a fallback): the Home picks, else the top-ranked live game, else his next game within a day, else the top-ranked game starting within a day. */
export function gameCentrePicks(items: readonly FxItem[], nowSec: number, refs: Refs = [], muted?: Muted | null, leagues: readonly string[] = []): FxItem[] {
  const home = homePicks(items, nowSec, refs, muted, leagues);
  if (home.length) return home;
  const top = liveWatchable(items, nowSec, muted).sort(cmp)[0];
  if (top) return [top];
  const soon = items.filter((f) => f.status === "pre" && !isMuted(f, muted) && f.kickoff - nowSec >= 0 && f.kickoff - nowSec <= DAY_S);
  const mine = soon.filter((f) => isFavourite(f, refs)).sort((a, b) => a.kickoff - b.kickoff)[0];
  const next = mine ?? [...soon].sort(cmp)[0];
  return next ? [next] : [];
}

/** The previous / next game, wrapping. */
export const cycle = (index: number, size: number, dir: 1 | -1): number => (size <= 0 ? 0 : (((index + dir) % size) + size) % size);

/** "In 25 min" / "In 3 h 20 min". */
export function countdown(kickoff: number, nowSec: number): string {
  const m = Math.max(1, Math.ceil((kickoff - nowSec) / 60));
  return m < 60 ? `In ${m} min` : `In ${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ""}`;
}

/** The tag above the teams: Live (no clock, no period: spoiler-safe), Full time, or the countdown. */
export const heroTag = (f: Pick<FxItem, "status" | "kickoff">, nowSec: number): string => (f.status === "in" ? "Live" : f.status === "post" ? "Full time" : countdown(f.kickoff, nowSec));

/** "NRL · Round 12 · Score hidden until you watch" (the last part only once it has started, and not for people with scores on). */
export const heroLine = (f: Pick<FxItem, "league" | "stage" | "status">, spoilers = false): string =>
  [f.league, f.stage, f.status !== "pre" && !spoilers ? "Score hidden until you watch" : null].filter(Boolean).join(" · ");
