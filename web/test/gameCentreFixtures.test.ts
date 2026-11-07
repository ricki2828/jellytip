import { test } from "node:test";
import assert from "node:assert/strict";
import { fxScoreText, fxTitle, groupFixtures, isFavourite, legacyForced, parseFixtureList, waysForFixture } from "../src/lib/gameCentre.ts";
import type { Rec } from "../src/lib/recs.ts";

const NOW = new Date(2026, 10, 7, 20, 0).getTime() / 1000;
const ch = (id: string, name: string, confidence: number, extra: object = {}) => ({ id, name, confidence, ...extra });
const fx = (fid: string, extra: object = {}) => ({ fid, league: "NBA", leagueKey: "nba", home: "Golden State Warriors", away: "Orlando Magic", kickoff: NOW + 3600, status: "pre", significance: 40, rank: 5, channels: [], ...extra });
const parse = (...x: object[]) => parseFixtureList({ items: x }).items;

test("parser: contract fields, drops unusable, tolerates ms and ISO kickoff", () => {
  const r = parseFixtureList({
    degraded: true,
    items: [
      fx("a", { kickoff: (NOW + 60) * 1000, home: { name: "Warriors", teamId: "9" }, channels: [ch("1:1", "ESPN", 0.95, { quality: { bucket: "fhd" }, dormant: false, start: NOW }), { name: "no id" }], best: "1:1", matchId: 77, recordingId: 3 }),
      fx("a"),
      fx("b", { kickoff: "2026-11-08T01:00:00Z" }),
      fx("c", { status: "weird" }),
      fx("d", { home: null }),
      { kickoff: 1, status: "pre", home: "x", away: "y" },
      fx("e", { home: undefined, away: undefined, title: "Monaco Grand Prix" }),
    ],
  });
  assert.equal(r.degraded, true);
  assert.deepEqual(r.items.map((f) => f.fid), ["a", "b", "e"]);
  const a = r.items[0];
  assert.equal(a.kickoff, NOW + 60);
  assert.equal(a.homeId, "9");
  assert.equal(a.channels.length, 1);
  assert.equal(a.channels[0].quality?.bucket, "fhd");
  assert.equal(a.matchId, "77");
  assert.equal(r.items[1].kickoff, Date.parse("2026-11-08T01:00:00Z") / 1000);
  assert.equal(fxTitle(r.items[2]), "Monaco Grand Prix");
  assert.deepEqual(parseFixtureList(null), { items: [], degraded: false });
  assert.deepEqual(parseFixtureList({ items: "x" }).items, []);
});

test("missing channel confidence is not treated as a solid link", () => {
  const f = parse(fx("a", { status: "in", channels: [{ id: "1:1", name: "Some feed" }] }))[0];
  const w = waysForFixture(f, null, NOW);
  assert.match(w.ways[0].label, /^May be live on/);
});

test("mocked Golden State fixture renders as NBA, never NRL", () => {
  const f = parse(fx("gsw", { status: "in", channels: [ch("1:1", "NBA League Pass", 0.9)] }))[0];
  assert.equal(f.league, "NBA");
  assert.equal(f.leagueKey, "nba");
  assert.equal(fxTitle(f), "Golden State Warriors v Orlando Magic");
  // A followed NRL "Warriors" ref must not make it a favourite: ids only, never names
  assert.equal(isFavourite(f, [{ teamId: "nrl-warriors", leagueKey: "nrl" }]), false);
  assert.equal(isFavourite({ ...f, homeId: "9", leagueKey: "nba" }, [{ teamId: "9", leagueKey: "nba" }]), true);
  assert.equal(isFavourite({ ...f, homeId: "9", leagueKey: "nba" }, [{ teamId: "9", leagueKey: "nrl" }]), false);
  assert.equal(isFavourite({ ...f, homeId: "9", leagueKey: "nba" }, [{ teamId: "9" }]), true);
  assert.equal(isFavourite({ ...f, favourite: true }, []), true);
});

test("order: live first, then favourites, then significance; finished last", () => {
  const items = parse(
    fx("pre-big", { significance: 90 }),
    fx("pre-fav", { significance: 10, favourite: true }),
    fx("live-big", { status: "in", significance: 95 }),
    fx("live-fav", { status: "in", significance: 20, favourite: true }),
    fx("pre-small", { significance: 20 }),
    fx("done", { status: "post", significance: 99, favourite: true }),
    fx("pre-by-id", { significance: 30, home: { name: "Cats", teamId: "T1" } }),
  );
  const g = groupFixtures(items, [{ teamId: "T1" }]);
  assert.deepEqual(g.map((x) => x.id), ["live", "yours", "big", "finished"]);
  assert.deepEqual(g[0].items.map((f) => f.fid), ["live-fav", "live-big"]);
  assert.deepEqual(g[1].items.map((f) => f.fid), ["pre-by-id", "pre-fav"]);
  assert.deepEqual(g[2].items.map((f) => f.fid), ["pre-big", "pre-small"]);
  assert.deepEqual(g[3].items.map((f) => f.fid), ["done"]);
  assert.deepEqual(groupFixtures([]), []);
});

test("no stream: the card stays and says so; suggest record offered", () => {
  const live = waysForFixture(parse(fx("a", { status: "in" }))[0], null, NOW);
  assert.equal(live.noStream, true);
  assert.match(live.note, /No stream found yet/);
  assert.equal(live.ways.length, 0);
  assert.equal(live.canSuggestRecord, false);
  const pre = waysForFixture(parse(fx("b"))[0], null, NOW);
  assert.equal(pre.noStream, true);
  assert.match(pre.note, /No stream found yet/);
  const post = waysForFixture(parse(fx("c", { status: "post" }))[0], null, NOW);
  assert.equal(post.noStream, false);
  assert.equal(post.canSuggestRecord, false);
});

test("linked fixture: solid vs may-carry wording, best channel, alternates, suggest record", () => {
  const f = parse(fx("a", { status: "in", best: "2:2", channels: [ch("1:1", "Low", 0.5), ch("2:2", "ESPN", 0.9)] }))[0];
  const w = waysForFixture(f, null, NOW);
  assert.equal(w.ways[0].label, "Live on ESPN");
  assert.match(w.ways[0].path, /channel=2%3A2|channel=2:2/);
  assert.match(w.ways[0].path, /nospoil=1/);
  assert.doesNotMatch(w.ways[0].path, /[&?](mi|ek)=/);
  assert.equal(w.canSuggestRecord, true);
  const dormant = parse(fx("b", { status: "in", channels: [ch("3:3", "NRL TV", 0.95, { dormant: true })] }))[0];
  assert.match(waysForFixture(dormant, null, NOW).ways[0].label, /^May be live on/);
  const rec: Rec = { id: 9, channelId: "2:2", channel: "ESPN", title: "x", start: NOW, stop: NOW + 7200, state: "done", playable: true };
  const wr = waysForFixture(parse(fx("c", { recordingId: 9, channels: [ch("2:2", "ESPN", 0.9)] }))[0], rec, NOW);
  assert.equal(wr.ways[0].kind, "recording");
  assert.equal(wr.canSuggestRecord, false);
});

test("spoilers: notes and ways never carry a score; score text only when the item has one", () => {
  const f = parse(fx("a", { status: "post", stage: "Final", score: { home: 110, away: 98 }, channels: [ch("1:1", "ESPN", 0.9)] }))[0];
  const w = waysForFixture(f, null, NOW);
  assert.doesNotMatch(w.note, /\d{2,3}/);
  assert.equal(fxScoreText(f), "Golden State Warriors 110–98 Orlando Magic");
  assert.equal(fxScoreText(parse(fx("b", { status: "post" }))[0]), null);
});

test("legacy flag", () => {
  assert.equal(legacyForced(() => "1"), true);
  assert.equal(legacyForced(() => null), false);
  assert.equal(legacyForced(() => "0"), false);
});
