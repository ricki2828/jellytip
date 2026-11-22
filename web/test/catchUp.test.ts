import { test } from "node:test";
import assert from "node:assert/strict";
import { cards, choices, joiningLine, KICKOFF_START_MS, kickoffClockHeld, kickoffFailed, WALLCLOCK_SLACK_SEC, LATE_SEC, markOffered, MAX_CARDS, offer, wasOffered } from "../src/lib/catchUp.ts";
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

test("joining line", () => {
  assert.equal(joiningLine(NOW - 25 * 60, NOW), "You're joining 25 minutes in");
  assert.equal(joiningLine(NOW - 125 * 60, NOW), "You're joining 2 h 5 min in");
  assert.equal(joiningLine(NOW, NOW), "You're joining 1 minutes in");
});

test("choices: kick-off only on the server's flags, catch me up only with cards, Live always; only Live = no card", () => {
  const ids = (k: boolean, n: number) => choices(k, n).map((c) => c.id);
  assert.deepEqual(ids(true, 5), ["kickoff", "catchup", "live"]);
  assert.deepEqual(ids(true, 0), ["kickoff", "live"]);
  assert.deepEqual(ids(false, 3), ["catchup", "live"]);
  assert.deepEqual(ids(false, 0), []);
  assert.equal(choices(false, 5)[0].title, "Catch me up · 5 plays");
  assert.equal(choices(false, 1)[0].title, "Catch me up · 1 play");
  assert.equal(choices(false, 12)[0].sub, "60 s of key plays, then live");
});

test("offered once per match", () => {
  assert.equal(wasOffered("m1"), false);
  markOffered("m1");
  assert.equal(wasOffered("m1"), true);
  assert.equal(wasOffered("m2"), false);
});

// ---- 22-Nov fix: the full answer is held by the spoiler delay as a whole (fresh updatedAt), its plays are gated on their own wallclock
const P = (id: string, text: string, wallclock: number | null, scoring = true) => ({ id, text, scoring, team: "home", clock: "1'", period: "1st", delta: 1, wallclock });
/** The reveal clock 30 s behind now (the default delay): what happened before NOW - 30 is released. */
const delayed = (at?: number) => at == null || at <= NOW - 30;

test("cards: plays of an UNRELEASED full answer, each on its own wallclock; none without one", () => {
  // newest first, as the server sends them; the answer itself is fresh (held), the released one is the light answer (no plays)
  const latest = parseLive({ status: "in", updatedAt: NOW, plays: [P("p4", "Just now", NOW - 5), P("p3", "No time", null), P("p2", "Corner", NOW - 400, false), P("p1", "Opener", NOW - 600)] })!;
  const light = parseLive({ status: "in", updatedAt: NOW - 60, moments: [] })!;
  assert.deepEqual(cards(light, latest, delayed).map((c) => c.text), ["Opener"]);
  // the old behaviour: released answer only = nothing
  assert.deepEqual(cards(light, null, delayed), []);
  assert.deepEqual(cards(null, latest, delayed).map((c) => c.text), ["Opener"]);
  // with no delay at all, everything with a time
  assert.deepEqual(cards(light, latest, all).map((c) => c.text), ["Opener", "Just now"]);
});

test("cards: released plays need no wallclock; merged with the newest answer, de-duplicated, oldest first", () => {
  const released = live({ plays: [P("p2", "Second", null), P("p1", "First", null)] });
  const latest = parseLive({ status: "in", updatedAt: NOW, plays: [P("p4", "Fourth", NOW - 5), P("p3", "Third", NOW - 100), P("p2", "Second", null)] })!;
  assert.deepEqual(cards(released, latest, delayed).map((c) => c.text), ["First", "Second", "Third"]);
  // the same play without ids (key = period|clock|text) still counts once
  const noId = (p: ReturnType<typeof P>) => ({ ...p, id: undefined });
  const r2 = live({ plays: [noId(P("", "Same", null))] });
  const l2 = parseLive({ status: "in", updatedAt: NOW, plays: [noId(P("", "Same", NOW - 100))] })!;
  assert.deepEqual(cards(r2, l2, delayed).map((c) => c.text), ["Same"]);
});

test("cards: at most the newest 12 scoring plays across both answers", () => {
  const latest = parseLive({ status: "in", updatedAt: NOW, plays: Array.from({ length: 15 }, (_, i) => P(`n${14 - i}`, `N${14 - i}`, NOW - 1000 + (14 - i) * 10)) })!;
  const c = cards(null, latest, all);
  assert.equal(c.length, MAX_CARDS);
  assert.equal(c[0].text, "N3");
  assert.equal(c.at(-1)!.text, "N14");
});

test("cards: the rugby league timeline still wins over plays; replay (reveal off) still shows nothing", () => {
  const latest = parseLive({ status: "in", updatedAt: NOW, plays: [P("p1", "Play", NOW - 600)], timeline: [{ id: "t1", kind: "try", team: "home", text: "Try", clock: "5'", at: NOW - 500 }] })!;
  assert.deepEqual(cards(null, latest, delayed).map((c) => c.text), ["Try"]);
  assert.deepEqual(cards(latest, latest, () => false), []);
});

test("parseLive: plays carry wallclock (epoch seconds or ISO), null when missing", () => {
  const d = parseLive({ status: "in", plays: [{ text: "A", wallclock: NOW - 5 }, { text: "B", wallclock: "2026-11-22T10:00:00Z" }, { text: "C" }, { text: "D", wallclock: null }] })!;
  assert.deepEqual(d.plays.map((p) => p.wallclock), [NOW - 5, Date.parse("2026-11-22T10:00:00Z") / 1000, null, null]);
});

test("kickoffFailed: refused / errored / no picture in time = back to live with the note; busy keeps its pop-up", () => {
  const k = { state: "loading", phase: "playing", played: false, sinceMs: 0 };
  assert.equal(kickoffFailed(k), false);
  assert.equal(kickoffFailed({ ...k, state: "error" }), true); // 400 no_catchup
  assert.equal(kickoffFailed({ ...k, state: "playing", phase: "failed" }), true);
  assert.equal(kickoffFailed({ ...k, state: "playing", phase: "denied" }), true);
  assert.equal(kickoffFailed({ ...k, state: "playing", sinceMs: KICKOFF_START_MS - 1 }), false);
  assert.equal(kickoffFailed({ ...k, state: "playing", sinceMs: KICKOFF_START_MS }), true); // 200 + 0 bytes
  assert.equal(kickoffFailed({ ...k, state: "playing", played: true, sinceMs: 10 * KICKOFF_START_MS }), false);
  assert.equal(kickoffFailed({ ...k, state: "full", sinceMs: 10 * KICKOFF_START_MS }), false);
});

test("cards: a play's wallclock (real event time) is asked WALLCLOCK_SLACK_SEC later, so the recap never runs ahead of the picture", () => {
  // released by the 30 s delay on its own wallclock, but not with the slack (the stream shows it later than it happened)
  const t = NOW - 30 - WALLCLOCK_SLACK_SEC + 10;
  const latest = parseLive({ status: "in", updatedAt: NOW, plays: [P("p2", "Too fresh", t), P("p1", "Old enough", NOW - 30 - WALLCLOCK_SLACK_SEC)] })!;
  assert.equal(delayed(t), true);
  assert.deepEqual(cards(null, latest, delayed).map((c) => c.text), ["Old enough"]);
});

test("kickoffFailed: paused (autoplay refused) or a hidden page holds the no-picture clock; errors still count", () => {
  const k = { state: "playing", phase: "playing", played: false, sinceMs: 10 * KICKOFF_START_MS };
  assert.equal(kickoffFailed({ ...k, paused: true }), false);
  assert.equal(kickoffFailed({ ...k, hidden: true }), false);
  assert.equal(kickoffClockHeld({ ...k, paused: true }), true);
  assert.equal(kickoffClockHeld({ ...k, hidden: true }), true);
  assert.equal(kickoffClockHeld({ ...k, state: "full" }), true);
  assert.equal(kickoffClockHeld(k), false);
  assert.equal(kickoffFailed({ ...k, paused: true, state: "error" }), true);
  assert.equal(kickoffFailed({ ...k, hidden: true, phase: "failed" }), true);
});
