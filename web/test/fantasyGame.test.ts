import { test } from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { parseFantasy } from "../src/lib/fantasy.ts";
import { ageLabel, canonTeam, findGameChannel, matchupSummaries, playersInGame, sideIs, watchable } from "../src/lib/fantasyGame.ts";
import { parseOnNow } from "../src/lib/onNow.ts";
import { createRequire } from "node:module";
import { rolldown } from "rolldown";
import { parseFixture } from "../src/lib/matchInfo.ts";

const NOW = 1_000_000;
const mi = (id: string, home: [string, string], away: [string, string], startsAt: number, extra: Record<string, unknown> = {}) => ({
  id, league: "NFL", leagueKey: "nfl", home: { name: home[0], short: home[1] }, away: { name: away[0], short: away[1] }, startsAt, status: "in", ...extra,
});
const chan = (n: number, matchInfo: unknown, extra: Record<string, unknown> = {}) => ({
  channel: { id: `p:${n}`, name: `Chan ${n}` }, now: { title: "NFL", start: NOW - 3600, stop: NOW + 7200, kind: "live", sport: true, matchInfo }, ...extra,
});
const list = (...c: unknown[]) => parseOnNow({ channels: c }, NOW);

test("matches by server match id", () => {
  const l = list(chan(1, mi("g1", ["Seattle Seahawks", "SEA"], ["San Francisco 49ers", "SF"], NOW - 3000)), chan(2, mi("g2", ["Dallas Cowboys", "DAL"], ["New York Giants", "NYG"], NOW - 3000)));
  assert.equal(findGameChannel({ matchId: "g2", nflTeam: "DAL" }, l, NOW)?.channelId, "p:2");
  assert.equal(findGameChannel({ matchId: "g2", nflTeam: "DAL" }, l, NOW)?.extras.matchId, "g2");
});

test("matches by team name and abbreviation when the id is missing", () => {
  const l = list(chan(1, mi("g1", ["Washington Commanders", "WSH"], ["Philadelphia Eagles", "PHI"], NOW - 3000)), chan(2, mi("g2", ["Jets", "NYJ"], ["Los Angeles Rams", "LA"], NOW - 3000)));
  assert.equal(findGameChannel({ matchId: null, nflTeam: "WAS" }, l, NOW)?.channelId, "p:1");
  assert.equal(findGameChannel({ matchId: null, nflTeam: "LAR" }, l, NOW)?.channelId, "p:2");
  assert.equal(findGameChannel({ matchId: null, nflTeam: "NYJ" }, l, NOW)?.channelId, "p:2");
  assert.equal(findGameChannel({ matchId: null, nflTeam: "NYG" }, l, NOW), null);
  assert.equal(findGameChannel({ matchId: null, nflTeam: "LAC" }, l, NOW), null);
});

test("doubleheader: live game wins over a later one", () => {
  const l = list(
    chan(1, mi("late", ["Seattle Seahawks", "SEA"], ["Rams", "LAR"], NOW + 5000, { status: "pre" })),
    chan(2, mi("early", ["Seattle Seahawks", "SEA"], ["49ers", "SF"], NOW - 1000, { status: "in" })),
  );
  assert.equal(findGameChannel({ matchId: null, nflTeam: "SEA" }, l, NOW)?.channelId, "p:2");
  // an explicit id still picks exactly that game
  assert.equal(findGameChannel({ matchId: "late", nflTeam: "SEA" }, l, NOW)?.channelId, "p:1");
});

test("no match: replays, other leagues, finished programmes, unknown id", () => {
  const l = list(
    chan(1, mi("g1", ["Seattle Seahawks", "SEA"], ["49ers", "SF"], NOW - 3000, { replay: true })),
    chan(2, mi("g3", ["Seattle Sounders", "SEA"], ["Portland Timbers", "POR"], NOW - 3000, { leagueKey: "mls" })),
  );
  assert.equal(findGameChannel({ matchId: null, nflTeam: "SEA" }, l, NOW), null);
  assert.equal(findGameChannel({ matchId: "g1", nflTeam: "SEA" }, l, NOW), null);
  assert.equal(findGameChannel({ matchId: "zzz", nflTeam: "SEA" }, l, NOW), null);
  assert.equal(findGameChannel({ matchId: null, nflTeam: "SEA" }, [], NOW), null);
});

test("team helpers", () => {
  assert.equal(canonTeam("jac"), "JAX");
  assert.equal(canonTeam("XYZ"), null);
  assert.ok(sideIs({ name: "Green Bay Packers", short: null }, "GB"));
  assert.ok(!sideIs({ name: "Los Angeles Chargers", short: null }, "LAR"));
  assert.ok(watchable({ gameState: "in" }) && watchable({ gameState: "pre" }) && !watchable({ gameState: "post" }) && !watchable({ gameState: null }));
});

const live = parseFantasy({
  providers: { sleeper: { linked: true } }, week: 9, updatedAt: 1000,
  leagues: [{ id: "L1", name: "CCP Dynasty", me: { team: "A", points: 50, projected: 98.5 }, opp: { team: "B", points: 40 },
    starters: [
      { side: "me", name: "Jamarr Gibbs", pos: "RB", nflTeam: "DET", points: 12.4, matchId: "g1", gameState: "in" },
      { side: "me", name: "Sam Lamb", pos: "WR", nflTeam: "DAL", points: 3, matchId: "g2", gameState: "in" },
      { side: "me", name: "No Id", pos: "TE", nflTeam: "WSH", points: 1, matchId: null, gameState: "in" },
      { side: "opp", name: "Their Guy", pos: "QB", nflTeam: "DET", points: 20, matchId: "g1", gameState: "in" },
    ] }],
  events: [{ at: 900, league: "CCP Dynasty", name: "Gibbs", delta: 6.4, side: "me", reason: "TD run, 12 yds" }, { at: 800, league: "CCP Dynasty", name: "Gibbs", delta: 1, side: "me", reason: "Rush" }],
})!;
const fx = (id: string, home: string, away: string) => parseFixture(mi(id, [home, ""], [away, ""], NOW))!;

test("players in this game: mine only, with the last scoring event", () => {
  const g = playersInGame(live, fx("g1", "Detroit Lions", "Chicago Bears"));
  assert.equal(g.length, 1);
  assert.deepEqual(g[0].players.map((p) => p.name), ["Jamarr Gibbs"]);
  assert.equal(g[0].players[0].last?.reason, "TD run, 12 yds");
  assert.equal(g[0].players[0].last?.delta, 6.4);
});

test("players in this game: falls back to team when the starter has no match id", () => {
  const g = playersInGame(live, fx("g9", "Washington Commanders", "Philadelphia Eagles"));
  assert.deepEqual(g[0].players.map((p) => p.name), ["No Id"]);
  assert.equal(g[0].players[0].last, null);
});

async function loadComponent() {
  const bundle = await rolldown({ input: new URL("../src/components/FantasyYourPlayers.tsx", import.meta.url).pathname, external: ["react", "react/jsx-runtime"], transform: { jsx: { runtime: "automatic" } } });
  const out = await bundle.generate({ format: "cjs" });
  await bundle.close();
  const module = { exports: {} as Record<string, any> };
  new Function("require", "module", "exports", out.output[0].code)(createRequire(import.meta.url), module, module.exports);
  return module.exports.FantasyYourPlayers;
}

test("hidden states: no league, no players, non-NFL, whip-around", async () => {
  const FantasyYourPlayers = await loadComponent();
  assert.deepEqual(playersInGame(null, fx("g1", "Detroit Lions", "Bears")), []);
  assert.deepEqual(playersInGame(live, null), []);
  assert.deepEqual(playersInGame(live, fx("g7", "Miami Dolphins", "Buffalo Bills")), []);
  assert.deepEqual(playersInGame(live, { ...fx("g1", "Detroit Lions", "Bears"), leagueKey: "nba" }), []);
  assert.deepEqual(playersInGame(live, { ...fx("g1", "Detroit Lions", "Bears"), kind: "multi" }), []);
  assert.deepEqual(playersInGame(parseFantasy({ providers: {}, leagues: [] }), fx("g1", "Detroit Lions", "Bears")), []);
  const f = fx("g7", "Miami Dolphins", "Buffalo Bills");
  assert.equal(renderToStaticMarkup(createElement(FantasyYourPlayers, { live, fixture: f })), "");
  assert.equal(renderToStaticMarkup(createElement(FantasyYourPlayers, { live: null, fixture: f })), "");
  assert.match(renderToStaticMarkup(createElement(FantasyYourPlayers, { live, fixture: fx("g1", "Detroit Lions", "Chicago Bears") })), /Your players in this game[\s\S]*Jamarr Gibbs[\s\S]*12\.4[\s\S]*TD run/);
});

test("matchup summary shows only data the server sent", () => {
  const [m] = matchupSummaries(live);
  assert.equal(m.lead, "me");
  assert.equal(Math.round(m.diff * 10), 100);
  assert.equal(m.projected, 98.5);
  assert.equal(m.liveCount, 4);
  assert.ok(!("winProbability" in m));
  assert.equal(matchupSummaries(null).length, 0);
  assert.equal(ageLabel(40), "updated 40 s ago");
  assert.equal(ageLabel(300), "updated 5 min ago");
  assert.equal(ageLabel(null), null);
});
