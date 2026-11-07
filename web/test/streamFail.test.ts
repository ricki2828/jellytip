import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { clearLocalFails, failedLately, failNote, failOf, nextAfterFailure, OutcomeSession, outcomeBody, outcomeTarget, parseLastFail, pickPlayable, rememberLocalFail, skippedNote, type Outcome } from "../src/lib/streamFail.ts";

const NOW = 1_800_000_000;
const lf = (ago: number, count7d = 1, reason = "stall") => ({ at: NOW - ago, reason: reason as never, count7d });
beforeEach(() => clearLocalFails());

test("parseLastFail: shape, defaults, junk", () => {
  assert.deepEqual(parseLastFail({ at: 5, reason: "refused", count7d: 3 }), { at: 5, reason: "refused", count7d: 3 });
  assert.deepEqual(parseLastFail({ at: 5, reason: "nope" }), { at: 5, reason: "open_failed", count7d: 1 });
  assert.equal(parseLastFail(null), null);
  assert.equal(parseLastFail({ reason: "stall" }), null);
  assert.equal(parseLastFail({ at: -1 }), null);
});

test("failNote: minutes, hours, days, and the weekly count wins", () => {
  assert.equal(failNote(lf(30), NOW), "Failed just now");
  assert.equal(failNote(lf(20 * 60), NOW), "Failed 20 min ago");
  assert.equal(failNote(lf(2 * 3600), NOW), "Failed 2 h ago");
  assert.equal(failNote(lf(2 * 86400), NOW), "Failed 2 days ago");
  assert.equal(failNote(lf(86400 + 100), NOW), "Failed 1 day ago");
  assert.equal(failNote(lf(3600, 3), NOW), "Failed 3× this week");
  assert.equal(failNote(lf(8 * 86400), NOW), null);
  assert.equal(failNote(null, NOW), null);
});

test("failedLately: 24 h window", () => {
  assert.equal(failedLately(lf(23 * 3600), NOW), true);
  assert.equal(failedLately(lf(25 * 3600), NOW), false);
  assert.equal(failedLately(undefined, NOW), false);
});

test("pickPlayable: skips a lately-failed first choice for a working alternative and names it", () => {
  const a = { id: "1:a", name: "Alpha", lastFail: lf(2 * 3600) };
  const b = { id: "1:b", name: "Beta" };
  const c = { id: "1:c", name: "Gamma" };
  const r = pickPlayable(a, [a, b, c], NOW);
  assert.equal(r.chosen.id, "1:b");
  assert.equal(r.skipped, "Alpha");
  assert.equal(skippedNote(r.skipped!), "Skipped Alpha: failed earlier");
});
test("pickPlayable: nothing skipped when the first works, or when every choice failed, or the failure is old", () => {
  const ok = { id: "1:a", name: "Alpha" };
  assert.deepEqual(pickPlayable(ok, [{ id: "1:b", name: "B" }], NOW), { chosen: ok, skipped: null });
  const a = { id: "1:a", name: "Alpha", lastFail: lf(3600) };
  const b = { id: "1:b", name: "Beta", lastFail: lf(7200) };
  assert.equal(pickPlayable(a, [b], NOW).chosen.id, "1:a");
  assert.equal(pickPlayable(a, [b], NOW).skipped, null);
  const old = { id: "1:a", name: "Alpha", lastFail: lf(30 * 3600) };
  assert.equal(pickPlayable(old, [{ id: "1:b", name: "B" }], NOW).chosen.id, "1:a");
});
test("pickPlayable: this device's own report counts before any list refresh", () => {
  const a = { id: "1:a", name: "Alpha" };
  const b = { id: "1:b", name: "Beta" };
  rememberLocalFail("1:a", "user_report", NOW - 60);
  assert.equal(pickPlayable(a, [b], NOW).chosen.id, "1:b");
});
test("failOf: newer of payload and local; counts merge", () => {
  rememberLocalFail("x", "stall", NOW - 10);
  assert.equal(failOf("x", lf(500, 2))?.reason, "stall");
  assert.equal(failOf("x", lf(500, 2))?.count7d, 2);
  assert.equal(failOf("x", lf(5))?.at, NOW - 5);
  assert.equal(failOf("y", null), null);
});

test("nextAfterFailure: first alternative that has not failed lately, never the current one", () => {
  const list = [{ id: "c", name: "C" }, { id: "d", name: "D", lastFail: lf(3600) }, { id: "e", name: "E" }];
  assert.equal(nextAfterFailure("c", list, NOW)?.id, "e");
  assert.equal(nextAfterFailure("c", [{ id: "d", name: "D", lastFail: lf(3600) }], NOW), null);
});

test("outcomeBody / outcomeTarget", () => {
  assert.deepEqual(outcomeBody({ id: "a:1", kind: "live", ok: false, reason: "stall", posSec: 20.7 }), { id: "a:1", kind: "live", ok: false, reason: "stall", posSec: 20 });
  assert.deepEqual(outcomeBody({ id: "a:1", kind: "live", ok: true, posSec: 61 }), { id: "a:1", kind: "live", ok: true, posSec: 61 });
  assert.deepEqual(outcomeTarget({ channel: "a:1" }), { id: "a:1", kind: "live" });
  assert.deepEqual(outcomeTarget({ channel: "a:1", start: 5 }), { id: "a:1", kind: "catchup" });
  assert.deepEqual(outcomeTarget({ vod: "a:m:1" }), { id: "a:m:1", kind: "vod" });
  assert.deepEqual(outcomeTarget({ episode: "a:e:1" }), { id: "a:e:1", kind: "episode" });
  assert.equal(outcomeTarget({ recording: "r" }), null);
});

test("OutcomeSession: one failure per session (user_report always), success only at 60 s and then clears the ding", () => {
  const sent: Outcome[] = [];
  const s = new OutcomeSession((o) => sent.push(o), () => NOW);
  const t = { id: "a:1", kind: "live" as const };
  assert.equal(s.fail(t, "stall", 20), true);
  assert.equal(s.fail(t, "open_failed"), false);
  assert.equal(s.fail(t, "recovery_gave_up"), false);
  assert.equal(s.fail(t, "user_report"), true);
  assert.equal(sent.length, 2);
  assert.equal(failedLately(failOf("a:1"), NOW), true);
  assert.equal(s.played(t, 59), false);
  assert.equal(s.played(t, 61), true);
  assert.equal(s.played(t, 90), false);
  assert.equal(failOf("a:1"), null);
  assert.deepEqual(sent[2], { id: "a:1", kind: "live", ok: true, posSec: 61 });
  assert.equal(s.fail(null, "stall"), false);
});
