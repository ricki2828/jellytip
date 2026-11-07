import test from "node:test";
import assert from "node:assert/strict";
import { fixtureSides, offerSides, parseTeamReminders, remindEveryText, remindingText, shortTeam } from "../src/lib/teamReminders.ts";
import { parseFixtureList } from "../src/lib/gameCentre.ts";
import { parseFixture } from "../src/lib/matchInfo.ts";
import { parseHash } from "../src/lib/routeParse.ts";
import { liveTabFor } from "../src/lib/navModel.ts";

test("parseTeamReminders keeps valid rows, drops junk", () => {
  const r = parseTeamReminders({ items: [{ id: "a1", teamId: "espn:nba:2", teamName: "Boston Celtics", league: "NBA", until: 5 }, { id: "x" }, null, { teamId: "t" }] });
  assert.equal(r.length, 1);
  assert.deepEqual(r[0], { id: "a1", teamId: "espn:nba:2", teamName: "Boston Celtics", sport: null, league: "NBA", until: 5 });
  assert.deepEqual(parseTeamReminders(undefined), []);
});

test("shortTeam prefers a shorter server short name", () => {
  assert.equal(shortTeam({ name: "Boston Celtics", short: "Celtics" }), "Celtics");
  assert.equal(shortTeam({ name: "Celtics", short: "BOS-LONGER" }), "Celtics");
  assert.equal(shortTeam({ name: "Celtics", short: null }), "Celtics");
});

test("offerSides needs a teamId and dedupes", () => {
  const s = (teamId: string | null, name: string) => ({ teamId, name, short: null, sport: null, league: null });
  assert.deepEqual(offerSides([s(null, "A"), s("t1", "B"), s("t1", "B"), s("t2", "")]).map((x) => x.teamId), ["t1"]);
});

test("button texts", () => {
  assert.equal(remindEveryText("Celtics"), "Remind me for every Celtics game");
  assert.equal(remindingText("Celtics"), "Reminding for every Celtics game");
});

test("fixtures list carries team ids and short names", () => {
  const { items } = parseFixtureList({ items: [{ fid: "f", kickoff: 10, status: "pre", home: { name: "Boston Celtics", short: "Celtics", teamId: "espn:nba:2" }, away: { name: "Miami Heat", teamId: "espn:nba:14" }, sport: "basketball", league: "NBA" }] });
  assert.equal(items[0].homeShort, "Celtics");
  assert.equal(items[0].awayShort, null);
  assert.equal(items[0].awayId, "espn:nba:14");
});

test("matchInfo sides keep teamId; events offer no team reminders", () => {
  const f = parseFixture({ id: "m", home: { name: "Heat", teamId: "espn:nba:14" }, away: { name: "Celtics" }, league: "NBA" })!;
  assert.equal(f.home.teamId, "espn:nba:14");
  assert.deepEqual(offerSides(fixtureSides(f, "basketball")).map((x) => x.teamId), ["espn:nba:14"]);
  const ev = parseFixture({ id: "e", kind: "event", title: "Monaco GP" })!;
  assert.deepEqual(fixtureSides(ev, null), []);
});

test("reminders route", () => {
  assert.equal(parseHash("#/reminders").name, "reminders");
  assert.equal(liveTabFor("reminders"), "sport");
});
