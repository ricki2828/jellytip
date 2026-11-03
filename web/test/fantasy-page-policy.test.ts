import { test } from "node:test";
import assert from "node:assert/strict";
import { leagueEvents, scoreState, snapshotStale, sourceAgeSeconds } from "../src/lib/fantasyPage.ts";
import type { FantasyLeague, FantasyLive } from "../src/lib/fantasy.ts";

const league: FantasyLeague = { id: "one", name: "League One", me: { team: "A", points: 0, projected: null }, opp: { team: "B", points: 0 }, starters: [{ side: "me", name: "P", pos: "WR", nflTeam: "SEA", points: 0, matchId: null, gameState: "pre" }] };
const live: FantasyLive = { providers: { sleeper: { linked: true }, yahoo: { available: false, linked: false } }, week: 9, leagues: [league], events: [], updatedAt: 100 };
test("source age and stale policy use source timestamp and live player state", () => {
  assert.equal(sourceAgeSeconds(100, 191_000), 91);
  assert.equal(snapshotStale(live, false, 191_000), false);
  assert.equal(scoreState(league), "Awaiting player games");
  const playing = { ...live, leagues: [{ ...league, starters: [{ ...league.starters[0], gameState: "in" as const }] }] };
  assert.equal(snapshotStale(playing, false, 190_000), false);
  assert.equal(snapshotStale(playing, false, 191_000), true);
  assert.equal(snapshotStale(live, true, 100_000), true);
});
test("recent events are newest first, deduped, and matched by id or name", () => {
  const event = { at: 10, league: "one", side: "me" as const, name: "P", delta: 6, reason: null };
  const events = [event, { ...event, at: 12, league: "League One" }, event, { ...event, at: 11 }, { ...event, at: 13, league: "other" }];
  assert.deepEqual(leagueEvents(events, league).map(e => e.at), [12, 11, 10]);
});
