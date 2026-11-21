import { test } from "node:test";
import assert from "node:assert/strict";
import { cards, choices, joiningLine, LATE_SEC, markOffered, MAX_CARDS, offer, wasOffered } from "../src/lib/catchUp.ts";
import { parseLive, type LiveStats } from "../src/lib/liveStats.ts";

const NOW = 1_800_000_000;
const live = (o: Record<string, unknown>): LiveStats => parseLive({ status: "in", updatedAt: NOW - 60, ...o })!;
const all = () => true;

test("offer: live, under way, 10+ min in; never in replay, pre or post", () => {
  const base = { live: true, replay: false, status: "in", startsAt: NOW - LATE_SEC, nowSec: NOW };
  assert.equal(offer(base), true);
  assert.equal(offer({ ...base, startsAt: NOW - LATE_SEC + 1 }), false);
  assert.equal(offer({ ...base, replay: true }), false);
  assert.equal(offer({ ...base, live: false }), false);
  assert.equal(offer({ ...base, status: "post" }), false);
  assert.equal(offer({ ...base, status: "pre" }), false);
  assert.equal(offer({ ...base, startsAt: null }), false);
  // no answer yet: the start time decides
  assert.equal(offer({ ...base, status: null }), true);
});

test("cards: the rugby league timeline first, each entry on its own time, last 12, points by kind", () => {
  const timeline = Array.from({ length: 15 }, (_, i) => ({ id: `t${i}`, kind: i % 2 ? "try" : "sin_bin", team: i % 2 ? "home" : "away", text: `E${i}`, clock: `${i * 3}'`, at: NOW - 1000 + i * 10 }));
  const d = live({ timeline });
  const c = cards(d, d, all);
  assert.equal(c.length, MAX_CARDS);
  assert.equal(c[0].text, "E3");
  assert.equal(c.at(-1)!.text, "E14");
  assert.equal(c.find((x) => x.text === "E13")!.points, 4);
  assert.equal(c.find((x) => x.text === "E14")!.points, null);
  // the delay holds back the newest entries
  const held = cards(d, d, (at) => at == null || at < NOW - 1000 + 12 * 10);
  assert.equal(held.at(-1)!.text, "E11");
});

test("cards: scoring plays of the released answer, oldest first, when there is no timeline", () => {
  const plays = [
    { text: "Late goal", scoring: true, team: "away", clock: "88'", period: "2nd", delta: 1 },
    { text: "Corner", scoring: false, team: "home" },
    { text: "Opener", scoring: true, team: "home", clock: "12'", period: "1st", delta: 1 },
  ];
  const c = cards(live({ plays }), null, all);
  assert.deepEqual(c.map((x) => x.text), ["Opener", "Late goal"]);
  assert.equal(c[0].when, "1st 12'");
  assert.equal(c[0].team, "home");
  assert.equal(c[0].points, 1);
});

test("cards: released key moments last, deduplicated and gated", () => {
  const m = (at: number, label: string) => ({ at, kind: "score", team: "home", label });
  const released = live({ moments: [m(NOW - 300, "A"), m(NOW - 200, "B")] });
  const latest = live({ moments: [m(NOW - 200, "B"), m(NOW - 100, "C"), m(NOW - 10, "D")] });
  const c = cards(released, latest, (at) => at == null || at <= NOW - 100);
  assert.deepEqual(c.map((x) => x.text), ["A", "B", "C"]);
});

test("cards: nothing while the reveal clock is off (replay), nothing without data", () => {
  const d = live({ plays: [{ text: "Opener", scoring: true }] });
  assert.deepEqual(cards(d, d, () => false), []);
  assert.deepEqual(cards(null, null, all), []);
});

test("joining line and choices", () => {
  assert.equal(joiningLine(NOW - 25 * 60, NOW), "You're joining 25 minutes in");
  assert.equal(joiningLine(NOW - 125 * 60, NOW), "You're joining 2 h 5 min in");
  assert.equal(joiningLine(NOW, NOW), "You're joining 1 minutes in");
  assert.deepEqual(choices(true).map((c) => c.id), ["kickoff", "catchup", "live"]);
  assert.deepEqual(choices(false).map((c) => c.id), ["catchup", "live"]);
});

test("offered once per match", () => {
  assert.equal(wasOffered("m1"), false);
  markOffered("m1");
  assert.equal(wasOffered("m1"), true);
  assert.equal(wasOffered("m2"), false);
});
