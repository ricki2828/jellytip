import { test } from "node:test";
import assert from "node:assert/strict";
import { alternatesAfterSwitch, shouldSurfaceLiveStall } from "../src/lib/streamRecovery.ts";

test("live stall only surfaces after its threshold in foreground playback", () => {
  assert.equal(shouldSurfaceLiveStall(10_000, 0, 10_000, false, false, 0), true);
  assert.equal(shouldSurfaceLiveStall(9_999, 0, 10_000, false, false, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, true, false, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, false, true, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, false, false, 20_001), false);
});

test("alternate switching keeps unselected candidates and appends the channel left", () => {
  const alternatives = [{ id: "b", name: "B" }, { id: "c", name: "C" }];
  assert.deepEqual(alternatesAfterSwitch(alternatives, { id: "a", name: "A" }, "b"), [
    { id: "c", name: "C" },
    { id: "a", name: "A" },
  ]);
});

import { permanentReason, beginExclusive, claimEngine, backoffMs, initialRecovery, isRetryable, recoveryCopy, step, type RecoveryEvent, type RecoveryEffect, type RecoveryState } from "../src/lib/streamRecovery.ts";

/** Run events; "released" is answered automatically after each release effect, like the player does. */
function run(s: RecoveryState, events: RecoveryEvent[]) {
  const all: RecoveryEffect[] = [];
  const queue = [...events];
  while (queue.length) {
    const r = step(s, queue.shift()!);
    s = r.state;
    for (const f of r.effects) {
      all.push(f);
      if (f.do === "release") queue.unshift({ t: "released" });
    }
  }
  return { s, fx: all };
}
const opens = (fx: RecoveryEffect[]) => fx.filter((f) => f.do === "open") as Extract<RecoveryEffect, { do: "open" }>[];
const fault = (pos = 0, canHls = false): RecoveryEvent => ({ t: "fault", pos, canHls });

test("backoff doubles and caps", () => {
  assert.deepEqual([1, 2, 3, 4, 5, 9].map(backoffMs), [1000, 2000, 4000, 8000, 8000, 8000]);
});

test("stall then fault walks stalled -> reconnecting 1 of 4 with release before open", () => {
  let r = run(initialRecovery(true), [{ t: "stall", pos: 0, canHls: false }]);
  assert.equal(r.s.phase, "stalled");
  assert.equal(recoveryCopy(r.s), "Stream is buffering…");
  r = run(r.s, [fault()]);
  assert.equal(r.s.phase, "reconnecting");
  assert.equal(recoveryCopy(r.s), "Reconnecting… (1 of 4)");
  assert.deepEqual(r.fx.map((f) => f.do), ["release", "open"]);
  assert.equal(opens(r.fx)[0].delayMs, 1000);
});

test("attempts count up, then fail with calm copy", () => {
  let r = run(initialRecovery(true), [fault()]);
  const delays = [opens(r.fx)[0].delayMs];
  for (let i = 0; i < 3; i++) {
    r = run(r.s, [{ t: "opened" }, fault()]);
    delays.push(opens(r.fx)[0].delayMs);
  }
  assert.deepEqual(delays, [1000, 2000, 4000, 8000]);
  assert.equal(recoveryCopy(r.s), "Reconnecting… (4 of 4)");
  r = run(r.s, [{ t: "opened" }, fault()]);
  assert.equal(r.s.phase, "failed");
  assert.equal(opens(r.fx).length, 0);
  assert.match(recoveryCopy(r.s)!, /isn't responding/);
  assert.equal(r.s.conn, "closed");
});

test("live TS exhausted switches once to HLS, then fails after two more", () => {
  let r = run(initialRecovery(true), [fault(0, true)]);
  for (let i = 0; i < 3; i++) r = run(r.s, [{ t: "opened" }, fault(0, true)]);
  r = run(r.s, [{ t: "opened" }, fault(0, true)]);
  assert.equal(r.s.phase, "switched");
  assert.equal(r.s.format, "m3u8");
  assert.equal(opens(r.fx)[0].format, "m3u8");
  assert.equal(recoveryCopy(r.s), "Switched to a backup stream");
  r = run(r.s, [{ t: "opened" }, fault(0, true)]);
  assert.equal(recoveryCopy(r.s), "Reconnecting… (2 of 2)");
  r = run(r.s, [{ t: "opened" }, fault(0, true)]);
  assert.equal(r.s.phase, "failed");
});

test("no HLS switch for VOD or when HLS is unavailable", () => {
  let r = run(initialRecovery(false), [fault(100, true)]);
  for (let i = 0; i < 4; i++) r = run(r.s, [{ t: "opened" }, fault(100, true)]);
  assert.equal(r.s.phase, "failed");
  assert.equal(r.s.format, "ts");
});

test("recovery returns to playing and resets the counters", () => {
  let r = run(initialRecovery(true), [fault(), { t: "opened" }, { t: "recovered" }]);
  assert.equal(r.s.phase, "playing");
  assert.equal(recoveryCopy(r.s), null);
  r = run(r.s, [{ t: "stable" }]);
  assert.equal(r.s.attempt, 0);
});

test("VOD / catch-up resumes at the stall position; live goes to the edge", () => {
  let r = run(initialRecovery(false), [{ t: "stall", pos: 734.5, canHls: false }, fault(740)]);
  assert.equal(opens(r.fx)[0].resumeAt, 734.5 > 0 ? r.s.resumeAt : null);
  assert.equal(r.s.resumeAt, 734.5);
  // position survives later attempts
  r = run(r.s, [{ t: "opened" }, fault(0)]);
  assert.equal(opens(r.fx)[0].resumeAt, 734.5);
  const live = run(initialRecovery(true), [fault(900)]);
  assert.equal(opens(live.fx)[0].resumeAt, null);
});

test("fault without a prior stall captures the position", () => {
  const r = run(initialRecovery(false), [fault(1200)]);
  assert.equal(opens(r.fx)[0].resumeAt, 1200);
});

test("single connection: open is never emitted while a stream is held or opening", () => {
  // manual machine without auto-release so we can see the gap
  let r = step(initialRecovery(true), fault());
  assert.deepEqual(r.effects.map((f) => f.do), ["release"]);
  assert.equal(r.state.conn, "releasing");
  assert.equal(r.state.want?.delayMs, 1000);
  // a duplicate fault while releasing changes nothing
  const dup = step(r.state, fault());
  assert.deepEqual(dup.effects, []);
  assert.equal(dup.state.attempt, 1);
  r = step(r.state, { t: "released" });
  assert.deepEqual(r.effects.map((f) => f.do), ["open"]);
  assert.equal(r.state.conn, "opening");
  // retry-now while an open is in flight is ignored (no second /play)
  const retry = step(r.state, { t: "retryNow", pos: 0, canHls: false });
  assert.deepEqual(retry.effects, []);
  assert.equal(retry.state.conn, "opening");
});

test("invariant over a long random-ish event stream: opens only follow a release", () => {
  const evs: RecoveryEvent[] = [
    { t: "stall", pos: 10, canHls: true }, fault(10, true), { t: "offline" }, fault(10, true), { t: "online" }, { t: "opened" }, fault(10, true),
    { t: "hidden", now: 0 }, { t: "hiddenTimeout" }, { t: "visible", now: 70_000 }, { t: "retryNow", pos: 10, canHls: true }, { t: "opened" }, { t: "recovered" },
  ];
  let s = initialRecovery(false);
  let held = true;
  const queue = [...evs];
  while (queue.length) {
    const r = step(s, queue.shift()!);
    s = r.state;
    for (const f of r.effects) {
      if (f.do === "release") { held = false; queue.unshift({ t: "released" }); }
      if (f.do === "open") { assert.equal(held, false, "open while a stream is held"); held = true; }
    }
  }
});

test("offline pauses retries without using attempts; online resumes immediately", () => {
  let r = run(initialRecovery(true), [{ t: "offline" }, fault()]);
  assert.equal(r.s.phase, "reconnecting");
  assert.equal(opens(r.fx).length, 0);
  assert.equal(recoveryCopy(r.s), "You're offline. Waiting for a connection…");
  const attempt = r.s.attempt;
  r = run(r.s, [{ t: "online" }]);
  assert.equal(opens(r.fx).length, 1);
  assert.equal(opens(r.fx)[0].delayMs, 0);
  assert.equal(r.s.attempt, attempt);
  // failure while offline again does not consume an attempt
  r = run(r.s, [{ t: "offline" }, fault()]);
  assert.equal(r.s.attempt, attempt);
  assert.equal(opens(r.fx).length, 0);
});

test("offline cancels pending backoff timers and rolls back an open in flight", () => {
  const held = run(initialRecovery(true), [fault(), { t: "opened" }, { t: "offline" }]);
  assert.ok(held.fx.some((f) => f.do === "cancelTimers"));
  const inFlight = run(initialRecovery(true), [fault(), { t: "offline" }]);
  assert.equal(inFlight.s.conn, "closed");
  assert.equal(opens(inFlight.fx).length, 1); // the one before going offline
  const back = run(inFlight.s, [{ t: "online" }]);
  assert.equal(opens(back.fx).length, 1);
  assert.equal(back.s.attempt, 1);
});

test("hidden over 60 s stops retries and releases; return gives a fresh attempt", () => {
  let r = run(initialRecovery(true), [fault(), { t: "opened" }, fault(), { t: "hidden", now: 1000 }]);
  assert.equal(r.s.attempt, 2);
  r = run(r.s, [{ t: "hiddenTimeout" }]);
  assert.equal(r.s.hiddenStop, true);
  assert.ok(r.fx.some((f) => f.do === "release"));
  assert.equal(opens(r.fx).length, 0);
  // a fault arriving while stopped starts nothing
  r = run(r.s, [fault()]);
  assert.equal(opens(r.fx).length, 0);
  r = run(r.s, [{ t: "visible", now: 90_000 }]);
  assert.equal(opens(r.fx).length, 1);
  assert.equal(opens(r.fx)[0].delayMs, 0);
  assert.equal(r.s.attempt, 1);
  assert.equal(r.s.hiddenStop, false);
});

test("hidden under 60 s keeps the current attempts running", () => {
  let r = run(initialRecovery(true), [fault(), { t: "opened" }, fault(), { t: "hidden", now: 0 }]);
  const before = r.s.attempt;
  r = run(r.s, [{ t: "visible", now: 30_000 }]);
  assert.equal(r.s.attempt, before);
  assert.equal(r.s.hiddenStop, false);
});

test("retry now restarts from attempt 1, also after failure", () => {
  let r = run(initialRecovery(true), [fault()]);
  for (let i = 0; i < 4; i++) r = run(r.s, [{ t: "opened" }, fault()]);
  assert.equal(r.s.phase, "failed");
  r = run(r.s, [{ t: "retryNow", pos: 0, canHls: false }]);
  assert.equal(r.s.phase, "reconnecting");
  assert.equal(r.s.attempt, 1);
  assert.equal(opens(r.fx)[0].delayMs, 0);
});

test("cancel releases the stream and exits; later events are inert", () => {
  let r = run(initialRecovery(true), [fault(), { t: "cancel" }]);
  assert.equal(r.s.phase, "ended");
  assert.ok(r.fx.some((f) => f.do === "exit"));
  r = run(r.s, [fault(), { t: "online" }, { t: "retryNow", pos: 0, canHls: false }]);
  assert.equal(opens(r.fx).length, 0);
});

test("blocked (409 on our own retry) stops recovery and releases", () => {
  const r = run(initialRecovery(true), [fault(), { t: "blocked" }]);
  assert.equal(r.s.phase, "blocked");
  assert.equal(recoveryCopy(r.s), null);
});

test("retryable classification", () => {
  assert.equal(isRetryable(0), true);
  assert.equal(isRetryable(503), true);
  assert.equal(isRetryable(200), true);
  assert.equal(isRetryable(404), false);
  assert.equal(isRetryable(401, "provider_auth"), false);
  assert.equal(isRetryable(200, "no_catchup"), false);
});

test("copy never leaks codes", () => {
  for (const phase of ["stalled", "reconnecting", "switched", "failed"] as const) {
    const c = recoveryCopy({ ...initialRecovery(true), phase, attempt: 1 });
    assert.ok(c && !/\b(\d{3}|code|MediaError)\b/i.test(c.replace(/\(\d of \d\)/, "")));
  }
});

test("stale released is a no-op unless the machine is releasing", () => {
  // held stream: a stale released from a previous run must not close it nor open a second one
  let r = step(initialRecovery(true), { t: "released" });
  assert.equal(r.state.conn, "held");
  assert.deepEqual(r.effects, []);
  // mid-attempt (opening): same
  const mid = run(initialRecovery(true), [fault()]).s;
  assert.equal(mid.conn, "opening");
  r = step({ ...mid, want: { delayMs: 0 } }, { t: "released" });
  assert.equal(r.state.conn, "opening");
  assert.deepEqual(r.effects, []);
  // a second stale released after the real one does not re-open
  const rel = step(initialRecovery(true), fault());
  const done = step(rel.state, { t: "released" });
  assert.equal(done.effects.length, 1);
  const again = step({ ...done.state, want: { delayMs: 0 } }, { t: "released" });
  assert.deepEqual(again.effects, []);
});

test("beginExclusive: invalidates queued opens, clears the timer and tears down first", () => {
  const gen = { current: 3 };
  const log: string[] = [];
  const g = beginExclusive(gen, () => log.push("clear"), () => log.push("teardown"));
  assert.equal(g, 4);
  assert.deepEqual(log, ["clear", "teardown"]);
  // an open queued under gen 3 is now stale
  assert.notEqual(gen.current, 3);
});

test("claimEngine destroys the old engine before the new one is stored (no leaked connection)", () => {
  const slot = { current: "old" as string | null };
  const log: string[] = [];
  claimEngine(slot, (o) => log.push(`destroy ${o} while slot=${slot.current}`), "new");
  assert.deepEqual(log, ["destroy old while slot=null"]);
  assert.equal(slot.current, "new");
});

test("single connection across recovery + manual start entry points", () => {
  // model: live engines counter; recovery opens only after release; manual start tears down first
  let engines = 1;
  const gen = { current: 0 };
  let s = initialRecovery(true);
  const queue: RecoveryEvent[] = [fault()];
  let manualStarts = 0;
  while (queue.length) {
    const r = step(s, queue.shift()!);
    s = r.state;
    for (const f of r.effects) {
      if (f.do === "release") { gen.current++; engines = 0; queue.push({ t: "released" }); }
      if (f.do === "open") { engines++; assert.ok(engines <= 1, "second connection"); if (manualStarts++ < 2) { beginExclusive(gen, () => {}, () => { engines = 0; }); engines++; assert.ok(engines <= 1); queue.push({ t: "opened" }, fault()); } }
    }
  }
});

test("recovery does not forgive attempts until stable", () => {
  let r = run(initialRecovery(true), [fault(), { t: "opened" }, { t: "recovered" }]);
  assert.equal(r.s.phase, "playing");
  assert.equal(r.s.attempt, 1);
  assert.equal(r.s.unstable, true);
  // flaps: next fault continues counting
  r = run(r.s, [fault()]);
  assert.equal(r.s.attempt, 2);
  r = run(r.s, [{ t: "opened" }, { t: "recovered" }, fault(), { t: "opened" }, { t: "recovered" }, fault(), { t: "opened" }, { t: "recovered" }, fault()]);
  assert.equal(r.s.phase, "failed");
  // stable forgives
  const ok = run(initialRecovery(true), [fault(), { t: "opened" }, { t: "recovered" }, { t: "stable" }]);
  assert.equal(ok.s.attempt, 0);
  assert.equal(ok.s.unstable, false);
});

test("a plain stall that clears needs no stability window", () => {
  const r = run(initialRecovery(true), [{ t: "stall", pos: 0, canHls: false }, { t: "recovered" }]);
  assert.equal(r.s.phase, "playing");
  assert.equal(r.s.unstable, false);
});

test("stall during reconnecting escalates through fault to the next attempt", () => {
  let r = run(initialRecovery(true), [fault(), { t: "opened" }]);
  assert.equal(r.s.attempt, 1);
  r = run(r.s, [{ t: "stall", pos: 0, canHls: false }]);
  assert.equal(r.s.phase, "reconnecting");
  r = run(r.s, [fault()]);
  assert.equal(r.s.attempt, 2);
});

test("retry now is ignored while an open is in flight and within the debounce window", () => {
  const r0 = step(initialRecovery(true), fault());
  const open = step(r0.state, { t: "released" }).state;
  assert.equal(open.conn, "opening");
  const ignored = step(open, { t: "retryNow", pos: 0, canHls: false, now: 1000 });
  assert.deepEqual(ignored.effects, []);
  assert.equal(ignored.state.attempt, open.attempt);
  // after failure: first retry works, second within 1.5 s is dropped, later one works
  const failed = run(initialRecovery(true), [fault(), { t: "opened" }, fault(), { t: "opened" }, fault(), { t: "opened" }, fault(), { t: "opened" }, fault()]).s;
  assert.equal(failed.phase, "failed");
  let r = run(failed, [{ t: "retryNow", pos: 0, canHls: false, now: 10_000 }]);
  assert.equal(opens(r.fx).length, 1);
  r = run({ ...r.s, conn: "held" }, [{ t: "retryNow", pos: 0, canHls: false, now: 10_500 }]);
  assert.equal(opens(r.fx).length, 0);
  r = run({ ...r.s, conn: "held" }, [{ t: "retryNow", pos: 0, canHls: false, now: 11_600 }]);
  assert.equal(opens(r.fx).length, 1);
});

test("permanent refusals are classified and never retried automatically", () => {
  assert.equal(permanentReason(429), "rate");
  assert.equal(permanentReason(401), "denied");
  assert.equal(permanentReason(403), "denied");
  assert.equal(permanentReason(404), "missing");
  assert.equal(permanentReason(200, "provider_auth"), "auth");
  assert.equal(permanentReason(200, "no_catchup"), "nocatchup");
  assert.equal(permanentReason(200, "bad_token"), "link");
  assert.equal(permanentReason(503), null);
  assert.equal(isRetryable(429), false);
  assert.equal(isRetryable(404), false);
});

test("fatal enters denied with calm copy, releases, opens nothing; manual retry works", () => {
  const expectCopy: [Parameters<typeof permanentReason>[0], string | null, RegExp][] = [[429, null, /limiting connections; try again in a minute/], [404, null, /couldn't be found/], [401, null, /isn't available/], [200, "provider_auth", /refused the login/], [200, "no_catchup", /no catch-up/], [200, "bad_token", /no longer valid/]];
  for (const [status, code, re] of expectCopy) {
    let r = run(initialRecovery(true), [{ t: "fatal", reason: permanentReason(status, code)! }]);
    assert.equal(r.s.phase, "denied");
    assert.match(recoveryCopy(r.s)!, re);
    assert.ok(r.fx.some((f) => f.do === "release"));
    assert.equal(opens(r.fx).length, 0);
    assert.equal(r.s.conn, "closed");
    r = run(r.s, [fault(), { t: "online" }, { t: "stall", pos: 0, canHls: false }]);
    assert.equal(r.s.phase, "denied");
    assert.equal(opens(r.fx).length, 0);
    r = run(r.s, [{ t: "retryNow", pos: 0, canHls: false, now: 5000 }]);
    assert.equal(r.s.phase, "reconnecting");
    assert.equal(opens(r.fx).length, 1);
  }
});

test("denied is distinct from blocked (409 -> StreamBusy)", () => {
  assert.equal(run(initialRecovery(true), [{ t: "blocked" }]).s.phase, "blocked");
  assert.equal(run(initialRecovery(true), [{ t: "fatal", reason: "rate" }]).s.phase, "denied");
});

test("a healthy playing stream is not released when the tab stays hidden", () => {
  const r = run(initialRecovery(true), [{ t: "hidden", now: 0 }, { t: "hiddenTimeout" }]);
  assert.equal(r.s.conn, "held");
  assert.equal(r.fx.some((f) => f.do === "release"), false);
});
