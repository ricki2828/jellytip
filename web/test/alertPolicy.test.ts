import { test } from "node:test";
import assert from "node:assert/strict";
import { EXPIRE_SEC, isBlowout, isKeyEvent, isLateGame, MIN_GAP_SEC, parseAlertMode, pickAlert, QUEUE_MAX, type Candidate } from "../src/lib/alertPolicy.ts";

const c = (id: string, kind: string, at: number, o: Partial<Candidate<string>> = {}): Candidate<string> => ({ id, kind, at, payload: id, ...o });
const all = () => true;

test("key mode: the listed events only", () => {
  for (const k of ["goal", "try", "touchdown", "wicket", "red_card"]) assert.equal(isKeyEvent(c("a", k, 0), "key"), true, k);
  assert.equal(isKeyEvent(c("a", "field_goal", 0), "key"), false);
  assert.equal(isKeyEvent(c("a", "field_goal", 0, { favourite: true }), "key"), true);
  assert.equal(isKeyEvent(c("a", "lead_change", 0), "key"), false);
  assert.equal(isKeyEvent(c("a", "lead_change", 0, { late: true }), "key"), true);
  assert.equal(isKeyEvent(c("a", "clutch", 0), "key"), false);
  assert.equal(isKeyEvent(c("a", "fantasy", 0, { swing: 4.9 }), "key"), false);
  assert.equal(isKeyEvent(c("a", "fantasy", 0, { swing: 5 }), "key"), true);
});

test("all and off modes", () => {
  assert.equal(isKeyEvent(c("a", "clutch", 0), "all"), true);
  assert.equal(isKeyEvent(c("a", "goal", 0), "off"), false);
  assert.equal(isKeyEvent(c("a", "flip", 0), "off"), false);
  assert.equal(parseAlertMode(undefined), "key");
  assert.equal(parseAlertMode("all"), "all");
  assert.equal(parseAlertMode("junk"), "key");
});

test("blowout suppresses everything but the favourite's scores", () => {
  assert.equal(isKeyEvent(c("a", "goal", 0), "key", true), false);
  assert.equal(isKeyEvent(c("a", "red_card", 0), "all", true), false);
  assert.equal(isKeyEvent(c("a", "fantasy", 0, { swing: 9 }), "key", true), false);
  assert.equal(isKeyEvent(c("a", "goal", 0, { favourite: true }), "key", true), true);
});

test("nothing before the reveal", () => {
  const p = [c("g", "goal", 100)];
  const r = pickAlert(p, { lastShownAt: null }, 110, { reveal: (at) => 110 >= at + 30, mode: "key" });
  assert.equal(r.show, null);
  assert.equal(r.pending.length, 1);
  const r2 = pickAlert(p, { lastShownAt: null }, 131, { reveal: (at) => 131 >= at + 30, mode: "key" });
  assert.equal(r2.show?.id, "g");
  assert.equal(r2.pending.length, 0);
});

test("max one per 30 s; the next waits then shows", () => {
  const p = [c("a", "goal", 100), c("b", "goal", 105)];
  let r = pickAlert(p, { lastShownAt: 120 }, 140, { reveal: all, mode: "key" });
  assert.equal(r.show, null);
  assert.equal(r.pending.length, 2);
  r = pickAlert(p, { lastShownAt: 120 }, 120 + MIN_GAP_SEC, { reveal: all, mode: "key" });
  assert.equal(r.show?.id, "a");
  assert.deepEqual(r.pending.map((x) => x.id), ["b"]);
});

test("ordering: priority first, then oldest", () => {
  const p = [c("lead", "lead_change", 100, { late: true }), c("goal", "goal", 110), c("goal0", "goal", 105)];
  const r = pickAlert(p, { lastShownAt: null }, 120, { reveal: all, mode: "key" });
  assert.equal(r.show?.id, "goal0");
  assert.deepEqual(r.pending.map((x) => x.id), ["lead", "goal"]);
});

test("unrevealed events never block revealed ones", () => {
  const p = [c("early", "goal", 100), c("late", "goal", 200)];
  const r = pickAlert(p, { lastShownAt: null }, 150, { reveal: (at) => at < 150, mode: "key" });
  assert.equal(r.show?.id, "early");
});

test("stale, duplicate and non-key candidates are dropped", () => {
  const p = [c("old", "goal", 100), c("dup", "goal", 500), c("dup", "goal", 500), c("meh", "clutch", 500)];
  const r = pickAlert(p, { lastShownAt: 499 }, 100 + EXPIRE_SEC + 1 + 300, { reveal: all, mode: "key" });
  assert.deepEqual(r.pending.map((x) => x.id), ["dup"]);
});

test("queue is bounded, newest kept", () => {
  const p = Array.from({ length: 9 }, (_, i) => c(`e${i}`, "goal", 100 + i));
  const r = pickAlert(p, { lastShownAt: 100 }, 110, { reveal: all, mode: "key" });
  assert.equal(r.pending.length, QUEUE_MAX);
  assert.equal(r.pending[QUEUE_MAX - 1]!.id, "e8");
});

test("replay: a reveal that always says no shows nothing and keeps nothing stale", () => {
  const r = pickAlert([c("a", "goal", 100)], { lastShownAt: null }, 110, { reveal: () => false, mode: "key" });
  assert.equal(r.show, null);
});

test("late game and blowout by sport", () => {
  assert.equal(isLateGame("basketball", 4, "5:00"), true);
  assert.equal(isLateGame("basketball", 2, "5:00"), false);
  assert.equal(isLateGame("football", 2, "78'"), true);
  assert.equal(isLateGame("football", 2, "40'"), false);
  assert.equal(isLateGame("cricket", 2, "1"), false);
  assert.equal(isBlowout("basketball", 120, 90, true), true);
  assert.equal(isBlowout("basketball", 120, 90, false), false);
  assert.equal(isBlowout("football", 5, 0, true), true);
  assert.equal(isBlowout("football", 2, 0, true), false);
  assert.equal(isBlowout("cricket", 300, 0, true), false);
  assert.equal(isBlowout("football", null, 0, true), false);
});
