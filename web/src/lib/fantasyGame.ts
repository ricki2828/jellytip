// Fantasy ↔ game: find the live channel for a starter's NFL game, the user's players in a game, and the matchup summary.
// Pure; tested in web/test/fantasyGame.test.ts. Uses only what /v1/fantasy/live and on-now already return.
import type { FantasyEvent, FantasyLeague, FantasyLive, Starter } from "./fantasy.ts";
import { isNfl } from "./fantasy.ts";
import type { Fixture } from "./matchInfo.ts";
import type { LiveExtras } from "./iptvPlay.ts";
import type { OnNow } from "./onNow.ts";

/** NFL abbreviation → [city, nickname]. Aliases cover the other abbreviations feeds use. */
const TEAMS: Record<string, [string, string]> = {
  ARI: ["arizona", "cardinals"], ATL: ["atlanta", "falcons"], BAL: ["baltimore", "ravens"], BUF: ["buffalo", "bills"],
  CAR: ["carolina", "panthers"], CHI: ["chicago", "bears"], CIN: ["cincinnati", "bengals"], CLE: ["cleveland", "browns"],
  DAL: ["dallas", "cowboys"], DEN: ["denver", "broncos"], DET: ["detroit", "lions"], GB: ["green bay", "packers"],
  HOU: ["houston", "texans"], IND: ["indianapolis", "colts"], JAX: ["jacksonville", "jaguars"], KC: ["kansas city", "chiefs"],
  LAC: ["los angeles", "chargers"], LAR: ["los angeles", "rams"], LV: ["las vegas", "raiders"], MIA: ["miami", "dolphins"],
  MIN: ["minnesota", "vikings"], NE: ["new england", "patriots"], NO: ["new orleans", "saints"], NYG: ["new york", "giants"],
  NYJ: ["new york", "jets"], PHI: ["philadelphia", "eagles"], PIT: ["pittsburgh", "steelers"], SEA: ["seattle", "seahawks"],
  SF: ["san francisco", "49ers"], TB: ["tampa bay", "buccaneers"], TEN: ["tennessee", "titans"], WAS: ["washington", "commanders"],
};
const ALIAS: Record<string, string> = { ARZ: "ARI", JAC: "JAX", WSH: "WAS", LA: "LAR", OAK: "LV", SD: "LAC", GNB: "GB", KAN: "KC", NWE: "NE", NOR: "NO", SFO: "SF", TAM: "TB" };

export const canonTeam = (abbr: string | null | undefined): string | null => {
  const a = (abbr ?? "").trim().toUpperCase();
  const c = ALIAS[a] ?? a;
  return TEAMS[c] ? c : null;
};

const norm = (s: string | null | undefined) => (s ?? "").toLowerCase().replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();

/** Whether a fixture side (name / short) is the team with this abbreviation. "Seattle Seahawks", "Seahawks", "SEA" and "WSH" for WAS all match. */
export function sideIs(side: { name: string; short: string | null }, abbr: string | null | undefined): boolean {
  const c = canonTeam(abbr);
  if (!c) return false;
  const n = norm(side.name);
  const words = ` ${n} `;
  // The full name decides when it names a team: "Los Angeles Chargers" with a short of "LA" is not the Rams
  const named = Object.keys(TEAMS).filter((k) => words.includes(` ${TEAMS[k][1]} `));
  const byName = canonTeam(side.name);
  if (byName) return byName === c;
  if (named.length) return named.includes(c);
  const sc = canonTeam(side.short);
  if (sc) return sc === c;
  // A bare city only when the city has one team
  const [city] = TEAMS[c];
  return !!n && n === city && city !== "los angeles" && city !== "new york";
}

export function fixtureHasTeam(f: Pick<Fixture, "home" | "away">, abbr: string | null | undefined): boolean {
  return sideIs(f.home, abbr) || sideIs(f.away, abbr);
}

export interface GameChannel {
  channelId: string;
  channel: string;
  extras: LiveExtras;
}

const MAX_ALT = 4;

/** The extras the player takes with a channel (same fields the On-now strip passes). */
function extrasOf(o: OnNow, now: number): LiveExtras {
  const pre = o.info.phase === "pre" || (o.info.startsAt != null && now < o.info.startsAt);
  return {
    kickoff: o.info.startOver && !pre ? { start: o.start, stop: o.stop } : null,
    startsAt: pre ? o.info.startsAt : null,
    eventKey: o.info.eventKey,
    matchId: o.info.fixture?.id ?? null,
    replay: !!o.info.fixture?.replay,
    desc: o.desc,
    upNext: o.next,
    alternates: (o.info.alternateChannels.length ? o.info.alternateChannels.map((a) => ({ id: a.id, name: a.name })) : o.info.alternates.map((id) => ({ id, name: "Another channel" }))).slice(0, MAX_ALT),
  };
}

/**
 * The channel showing a starter's NFL game right now. The server's match id decides; with no id, the NFL team abbreviation is matched
 * to the fixture's sides. A replay never counts. With two games for a team (a doubleheader, a rerun slot) the one that is live wins,
 * then the one that has started most recently, then the next to start. null when none of the user's channels carry it.
 */
export function findGameChannel(starter: Pick<Starter, "matchId" | "nflTeam">, list: OnNow[], now: number): GameChannel | null {
  const games = list.filter((o) => {
    const f = o.info.fixture;
    return !!f && f.kind === "match" && f.status !== "post" && !f.replay && o.info.kind !== "replay" && o.info.kind !== "highlights" && o.stop > now;
  });
  let pool = starter.matchId ? games.filter((o) => o.info.fixture!.id === starter.matchId) : [];
  if (!pool.length && !starter.matchId) pool = games.filter((o) => isNfl(o.info.fixture!.leagueKey) && fixtureHasTeam(o.info.fixture!, starter.nflTeam));
  if (!pool.length) return null;
  const rank = (o: OnNow) => {
    const f = o.info.fixture!;
    const started = (f.startsAt ?? o.start) <= now;
    const live = f.status === "in" || (started && f.status !== "post");
    // live first; among live the latest start; among upcoming the soonest
    return live ? [0, -(f.startsAt ?? o.start)] : [1, f.startsAt ?? o.start];
  };
  const best = [...pool].sort((a, b) => {
    const [ra, rb] = [rank(a), rank(b)];
    return ra[0] - rb[0] || ra[1] - rb[1];
  })[0];
  return { channelId: best.channelId, channel: best.channel, extras: extrasOf(best, now) };
}

export interface GamePlayer {
  name: string;
  pos: string | null;
  nflTeam: string | null;
  points: number;
  state: Starter["gameState"];
  /** Newest scoring event for this player, when the play-by-play matched one. */
  last: { reason: string | null; delta: number; at: number } | null;
}
export interface GameLeague {
  id: string;
  league: string;
  players: GamePlayer[];
}

/**
 * The starter's newest scoring event. By player id when both sides carry one, else by full name plus team (when the event names a
 * team). Never by bare last name. An event belongs to a league by its id; a name is accepted only when no other league shares it.
 */
function lastEvent(events: FantasyEvent[], live: FantasyLive, league: FantasyLeague, s: Starter): GamePlayer["last"] {
  const nameUnique = live.leagues.filter((l) => l.name === league.name).length === 1;
  const full = norm(s.name);
  const mine = events
    .filter((e) => e.side === "me" && (e.league === league.id || (nameUnique && e.league === league.name)))
    .filter((e) => {
      if (e.playerId && s.id) return e.playerId === s.id;
      if (!full || norm(e.name) !== full) return false;
      return !e.nflTeam || canonTeam(e.nflTeam) === canonTeam(s.nflTeam);
    })
    .sort((a, b) => b.at - a.at)[0];
  return mine ? { reason: mine.reason, delta: mine.delta, at: mine.at } : null;
}

/** The user's own starters (not the opponent's) in this game, per league. [] when none, so the caller can hide the block. */
export function playersInGame(live: FantasyLive | null, fixture: Pick<Fixture, "id" | "home" | "away" | "kind" | "leagueKey"> | null): GameLeague[] {
  if (!live || !fixture || fixture.kind === "multi" || !isNfl(fixture.leagueKey)) return [];
  return live.leagues.flatMap((l) => {
    const players = l.starters
      .filter((s) => s.side === "me" && (s.matchId ? s.matchId === fixture.id : fixtureHasTeam(fixture, s.nflTeam)))
      .sort((a, b) => b.points - a.points)
      .map((s) => ({ name: s.name, pos: s.pos, nflTeam: s.nflTeam, points: s.points, state: s.gameState, last: lastEvent(live.events, live, l, s) }));
    return players.length ? [{ id: l.id, league: l.name, players }] : [];
  });
}

/** "updated 40 s ago" / "updated 3 min ago"; null when the source gave no time. */
export function ageLabel(seconds: number | null): string | null {
  if (seconds == null) return null;
  if (seconds < 5) return "updated just now";
  if (seconds < 90) return `updated ${seconds} s ago`;
  if (seconds < 5400) return `updated ${Math.round(seconds / 60)} min ago`;
  return `updated ${Math.round(seconds / 3600)} h ago`;
}

export interface MatchupSummary {
  id: string;
  name: string;
  me: { team: string; points: number };
  opp: { team: string; points: number };
  /** Only what the server sent: the user's projection. No opponent projection or win probability exists in the data, so none is shown. */
  projected: number | null;
  lead: "me" | "opp" | "tie";
  diff: number;
  liveCount: number;
}

export function matchupSummaries(live: FantasyLive | null): MatchupSummary[] {
  return (live?.leagues ?? []).map((l) => {
    const d = l.me.points - l.opp.points;
    return {
      id: l.id,
      name: l.name,
      me: { team: l.me.team, points: l.me.points },
      opp: { team: l.opp.team, points: l.opp.points },
      projected: l.me.projected,
      lead: Math.abs(d) < 0.05 ? "tie" : d > 0 ? "me" : "opp",
      diff: Math.abs(d),
      liveCount: l.starters.filter((s) => s.gameState === "in").length,
    };
  });
}

/** Whether a starter's game can be watched now: not finished, and the data knows its state. */
export const watchable = (s: Pick<Starter, "gameState">) => s.gameState === "in" || s.gameState === "pre";

export const NOT_ON_CHANNELS = "Not on your channels right now";
