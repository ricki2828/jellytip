import { test } from "node:test";
import assert from "node:assert/strict";
import { calibrateMessage, calibratedDelay, clampDelay, DEFAULT_DELAY_SEC, isRevealed, lagSec, liveEdge, parseChannelKey, REPLAY_EXIT_LAG_SEC, REPLAY_LAG_SEC, replayState, revealAt } from "../src/lib/delayMath.ts";
import { parsePrefs } from "../src/lib/prefs.ts";

const ranges = (...ends: number[]) => ({ length: ends.length, end: (i: number) => ends[i]! });

test("reveal_at = event + delay + lag", () => {
  assert.equal(revealAt(1000, 30, 0), 1030);
  assert.equal(revealAt(1000, 30, 12), 1042);
});

test("revealed only once event + delay + lag has passed", () => {
  assert.equal(isRevealed(1000, 1029, 30, 0, false), false);
  assert.equal(isRevealed(1000, 1030, 30, 0, false), true);
  assert.equal(isRevealed(1000, 1035, 30, 10, false), false); // playhead 10 s behind: 5 s more
  assert.equal(isRevealed(1000, 1040, 30, 10, false), true);
});

test("live edge drift: as the playhead falls behind, the same event reveals later", () => {
  const ev = 1000;
  let lag = lagSec(100, liveEdge({ currentTime: 100, seekable: ranges(100) }));
  assert.equal(lag, 0);
  assert.equal(isRevealed(ev, 1031, 30, lag, false), true);
  // pause for 20 s: the edge moves on, the playhead does not
  lag = lagSec(100, liveEdge({ currentTime: 100, seekable: ranges(120) }));
  assert.equal(lag, 20);
  assert.equal(isRevealed(ev, 1031, 30, lag, false), false);
  assert.equal(isRevealed(ev, 1050, 30, lag, false), true);
});

test("live edge: seekable, else buffered, else the playhead; never behind the playhead", () => {
  assert.equal(liveEdge({ currentTime: 5, seekable: ranges(0, 50), buffered: ranges(40) }), 50);
  assert.equal(liveEdge({ currentTime: 5, seekable: ranges(), buffered: ranges(40) }), 40);
  assert.equal(liveEdge({ currentTime: 5 }), 5);
  assert.equal(liveEdge({ currentTime: 60, seekable: ranges(50) }), 60);
  assert.equal(liveEdge({ currentTime: 5, seekable: ranges(Infinity) }), 5);
  assert.equal(lagSec(10, 4), 0);
  assert.equal(lagSec(NaN, 4), 0);
});

test("replay: more than 2 min behind, with hysteresis; forced always", () => {
  assert.equal(replayState(false, REPLAY_LAG_SEC), false);
  assert.equal(replayState(false, REPLAY_LAG_SEC + 1), true);
  assert.equal(replayState(true, REPLAY_LAG_SEC - 5), true); // still replay between exit and enter
  assert.equal(replayState(true, REPLAY_EXIT_LAG_SEC - 1), false);
  assert.equal(replayState(false, 0, true), true);
  assert.equal(replayState(true, 0, true), true);
});

test("replay reveals nothing, whatever the times", () => {
  assert.equal(isRevealed(1000, 99999, 30, 0, true), false);
  assert.equal(isRevealed(undefined, 99999, 30, 0, true), false);
});

test("no event time: live reveal is on", () => {
  assert.equal(isRevealed(undefined, 5, 30, 0, false), true);
  assert.equal(isRevealed(NaN, 5, 30, 0, false), true);
});

test("calibration: 'that just happened' reveals the latest event now", () => {
  const d = calibratedDelay(1000, 1042, 0)!;
  assert.equal(d, 42);
  assert.equal(isRevealed(1000, 1042, d, 0, false), true);
  assert.equal(isRevealed(1000, 1041, d, 0, false), false);
  // with a playhead 10 s behind the edge the lag is already counted
  assert.equal(calibratedDelay(1000, 1042, 10), 32);
});

test("calibration: no anchor, an event from the future, and clamping", () => {
  assert.equal(calibratedDelay(null, 1000, 0), null);
  assert.equal(calibratedDelay(1100, 1000, 0), null); // a tap 100 s before the event: clocks disagree, don't trust
  assert.equal(calibratedDelay(1000, 1002, 0), 2);
  assert.equal(calibratedDelay(1000, 1003, 6), 0); // within the tolerance, floored at 0
  assert.equal(calibratedDelay(0, 99999, 0), 300);
});

test("delay clamps to 0-300 and falls back", () => {
  assert.equal(clampDelay(-4), 0);
  assert.equal(clampDelay(999), 300);
  assert.equal(clampDelay(41.6), 42);
  assert.equal(clampDelay("x"), DEFAULT_DELAY_SEC);
  assert.equal(clampDelay(NaN, 7), 7);
});

test("channel key", () => {
  assert.deepEqual(parseChannelKey("p1:42"), { pid: "p1", sid: "42" });
  assert.deepEqual(parseChannelKey("p1:a:b"), { pid: "p1", sid: "a:b" });
  assert.deepEqual(parseChannelKey("42"), { pid: "_", sid: "42" });
});

test("prefs: sport.delaySec and sport.alerts parse, with defaults", () => {
  assert.equal(parsePrefs({}).sport.delaySec, 30);
  assert.equal(parsePrefs({}).sport.alerts, "key");
  assert.equal(parsePrefs({ sport: { delaySec: 45, alerts: "off" } }).sport.delaySec, 45);
  assert.equal(parsePrefs({ sport: { delaySec: 45, alerts: "off" } }).sport.alerts, "off");
  assert.equal(parsePrefs({ sport: { delaySec: 9999, alerts: "wat" } }).sport.delaySec, 300);
  assert.equal(parsePrefs({ sport: { alerts: "wat" } }).sport.alerts, "key");
});

test("calibrate feedback: set, nothing to anchor on, replay", () => {
  assert.equal(calibrateMessage({ kind: "set", sec: 42 }), "Delay set to 42 s");
  assert.match(calibrateMessage({ kind: "none" }), /^Nothing to line up yet/);
  assert.match(calibrateMessage({ kind: "replay" }), /replay/);
});

import { persistChannelDelay, stepDelay, MAX_DELAY_SEC } from "../src/lib/delayMath.ts";

test("stepDelay clamps to 0..max", () => {
  assert.equal(stepDelay(30, 5), 35);
  assert.equal(stepDelay(3, -10), 0);
  assert.equal(stepDelay(MAX_DELAY_SEC - 2, 10), MAX_DELAY_SEC);
});

test("persistChannelDelay clamps, writes local, then puts per channel", () => {
  const calls: unknown[][] = [];
  const io = { writeLocal: (k: string, s: number) => calls.push(["local", k, s]), put: (p: string, s: string, d: number) => calls.push(["put", p, s, d]) };
  assert.equal(persistChannelDelay("prov:123", 999, io), MAX_DELAY_SEC);
  assert.deepEqual(calls, [["local", "prov:123", MAX_DELAY_SEC], ["put", "prov", "123", MAX_DELAY_SEC]]);
  calls.length = 0;
  assert.equal(persistChannelDelay(null, 40, io), 40);
  assert.deepEqual(calls, []);
});
