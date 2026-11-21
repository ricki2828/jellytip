import { test } from "node:test";
import assert from "node:assert/strict";
import { countdown, cycle, gameCentrePicks, heroLine, heroTag, homePicks, isMuted } from "../src/lib/liveHero.ts";
import type { FxChannel, FxItem } from "../src/lib/gameCentre.ts";

const NOW = 1_800_000_000;
const ch = (id: string): FxChannel => ({ id, name: `Ch ${id}`, num: null, logo: null, quality: null, confidence: 1, why: null, region: null, lang: null, maybe: false, dormant: false, start: null, stop: null, startOver: false, autoRecord: null });
const fx = (fid: string, o: Partial<FxItem> = {}): FxItem => ({
  fid, league: "NRL", leagueKey: "nrl", sport: "rugby-league", home: `H${fid}`, away: `A${fid}`, homeId: null, awayId: null, homeShort: null, awayShort: null, homeLogo: null, awayLogo: null,
  kickoff: NOW - 1200, status: "in", stage: null, score: null, interest: 0, significance: 0, rank: null, favourite: false, autoRecord: false, channels: [ch(`c${fid}`)], best: null,
  reminderId: null, recordingId: null, matchId: `m${fid}`, ...o,
});
const muted = { sports: [], leagues: [], tags: [] };

test("home: his live games, ranked; else the single top-ranked live game; none without a channel", () => {
  const items = [fx("a", { rank: 2 }), fx("b", { rank: 1 }), fx("c", { favourite: true, rank: 5 }), fx("d", { favourite: true, rank: 3 }), fx("e", { favourite: true, channels: [] })];
  assert.deepEqual(homePicks(items, NOW).map((f) => f.fid), ["d", "c"]);
  assert.deepEqual(homePicks(items.filter((f) => !f.favourite), NOW).map((f) => f.fid), ["b"]);
  assert.deepEqual(homePicks([fx("x", { status: "pre", favourite: true })], NOW), []);
  // a favourite by team ref, not only by the server flag
  assert.deepEqual(homePicks([fx("a", { rank: 1 }), fx("t", { homeId: "42", rank: 9 })], NOW, [{ teamId: "42" }]).map((f) => f.fid), ["t"]);
});

test("home: ties by significance, then kickoff, then id", () => {
  const items = [fx("b", { significance: 1 }), fx("a", { significance: 1 }), fx("z", { significance: 3 })];
  assert.deepEqual(homePicks(items, NOW).map((f) => f.fid), ["z"]);
  assert.deepEqual(homePicks(items.slice(0, 2), NOW).map((f) => f.fid), ["a"]);
});

test("muted leagues and sports never reach the hero", () => {
  assert.equal(isMuted(fx("a"), { ...muted, leagues: ["nrl"] }), true);
  assert.equal(isMuted(fx("a"), { ...muted, sports: ["Rugby-League"] }), true);
  assert.equal(isMuted(fx("a"), muted), false);
  assert.deepEqual(homePicks([fx("a", { favourite: true }), fx("b", { league: "AFL", leagueKey: "afl" })], NOW, [], { ...muted, leagues: ["NRL"] }).map((f) => f.fid), ["b"]);
});

test("game centre: live picks first, else his next game within a day, else the top-ranked one", () => {
  const pre = (fid: string, inS: number, o: Partial<FxItem> = {}) => fx(fid, { status: "pre", kickoff: NOW + inS, ...o });
  assert.deepEqual(gameCentrePicks([fx("live"), pre("p", 600, { favourite: true })], NOW).map((f) => f.fid), ["live"]);
  assert.deepEqual(gameCentrePicks([pre("a", 600, { rank: 1 }), pre("b", 7200, { favourite: true }), pre("c", 3600, { favourite: true })], NOW).map((f) => f.fid), ["c"]);
  assert.deepEqual(gameCentrePicks([pre("a", 600, { rank: 2 }), pre("b", 700, { rank: 1 }), pre("far", 2 * 86400, { favourite: true })], NOW).map((f) => f.fid), ["b"]);
  assert.deepEqual(gameCentrePicks([fx("done", { status: "post" })], NOW), []);
});

test("cycle wraps both ways", () => {
  assert.equal(cycle(0, 3, -1), 2);
  assert.equal(cycle(2, 3, 1), 0);
  assert.equal(cycle(1, 3, 1), 2);
  assert.equal(cycle(0, 0, 1), 0);
});

test("tag and line never carry a score", () => {
  assert.equal(heroTag(fx("a", { score: { home: 12, away: 6, clock: "54'", period: "2nd" } }), NOW), "Live");
  assert.equal(heroTag(fx("a", { status: "post" }), NOW), "Full time");
  assert.equal(heroTag(fx("a", { status: "pre", kickoff: NOW + 25 * 60 }), NOW), "In 25 min");
  assert.equal(countdown(NOW + 200 * 60, NOW), "In 3 h 20 min");
  assert.equal(countdown(NOW + 120 * 60, NOW), "In 2 h");
  assert.equal(heroLine(fx("a", { stage: "Round 12" })), "NRL · Round 12 · Score hidden until you watch");
  assert.equal(heroLine(fx("a", { status: "pre" })), "NRL");
});
