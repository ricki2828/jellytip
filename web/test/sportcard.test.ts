import { test } from "node:test";
import assert from "node:assert/strict";
import { applyReveal, parseLive, scorecardBlocks } from "../src/lib/liveStats.ts";

const card = {
  innings: [{ name: "SA", team: "home", runs: 329, wickets: 10, overs: "82.3", current: false }, { name: "AUS", team: "away", runs: 120, wickets: 3, overs: "30.1", current: true }],
  batters: [{ name: "Head", runs: 40, balls: 52, fours: 5, sixes: 1, onStrike: true }],
  bowler: { name: "Rabada", figures: "1-20", overs: "6.2", econ: 3.1 },
  partnership: { runs: 33, balls: 40 },
  target: 288,
  equation: "need 168 from 150 balls",
  overs: [{ over: 30, runs: 4, balls: ["1", "•", "3"], at: 1000 }, { over: 31, runs: 1, balls: ["1"], live: true, at: 2000 }],
};
const raw = (extra: object = {}) => ({ status: "in", layout: "cricket", score: { home: 1, away: 2 }, updatedAt: 1500, scorecard: card, timeline: [{ id: "a", kind: "try", team: "home", text: "Try · A", clock: "5'", at: 1000, score: { home: 4, away: 0 } }, { id: "b", kind: "conversion", team: "home", text: "Conversion · A", clock: "6'", at: 3000 }], ...extra });
const names = { home: "SA", away: "AUS" };

test("parse: scorecard and timeline are optional", () => {
  const d = parseLive({ status: "in" })!;
  assert.equal(d.scorecard, null);
  assert.deepEqual(d.timeline, []);
  assert.equal(parseLive(raw())!.timeline.length, 2);
  assert.equal(parseLive({ timeline: [{ nope: 1 }, "x"] })!.timeline.length, 0);
});

test("scorecard blocks: innings, crease, bowling, chase, overs newest first", () => {
  const b = scorecardBlocks(card, names);
  assert.equal(b[0]!.kind, "grid");
  const crease = b.find((x) => x.kind === "kv" && x.title === "At the crease");
  assert.ok(crease && crease.kind === "kv" && crease.rows[0]![1].includes("5x4"));
  const kv = b.find((x) => x.kind === "kv" && x.title === null);
  assert.ok(kv && kv.kind === "kv" && kv.rows.some(([k, v]) => k === "Need" && v.startsWith("168")));
  const chips = b.filter((x) => x.kind === "chips");
  assert.match((chips[0] as { label: string }).label, /Over 31.*live/);
  assert.deepEqual(scorecardBlocks(null, names), []);
  assert.deepEqual(scorecardBlocks({}, names), []);
});

test("reveal: nothing shows before the delayed playhead", () => {
  const d = parseLive(raw())!;
  const early = applyReveal(d, (at) => at === undefined || at <= 1200);
  assert.deepEqual(early.timeline.map((t) => t.id), ["a"]);
  assert.equal(early.scorecard, null, "snapshot (1500) not revealed yet");
  const mid = applyReveal(d, (at) => at === undefined || at <= 1600);
  assert.ok(mid.scorecard);
  assert.deepEqual((mid.scorecard!.overs as unknown[]).length, 1, "over at 2000 stays hidden");
  assert.equal(mid.timeline.length, 1);
  const all = applyReveal(d, () => true);
  assert.equal(all, d);
  const replay = applyReveal(d, (at) => at !== undefined);
  assert.equal(replay.timeline.length, 0);
});
