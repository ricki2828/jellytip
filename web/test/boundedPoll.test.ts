import { test } from "node:test";
import assert from "node:assert/strict";
import { startBoundedPoll, type Outcome, type PollEnv, type PollState } from "../src/lib/boundedPoll.ts";

function fakeEnv() {
  let t = 0;
  let hidden = false;
  const timers = new Map<number, { at: number; fn: () => void }>();
  let id = 0;
  let visCb: (() => void) | null = null;
  const env: PollEnv = {
    setTimer: (fn, ms) => (timers.set(++id, { at: t + ms, fn }), id),
    clearTimer: (h) => void timers.delete(h as number),
    now: () => t,
    hidden: () => hidden,
    onVisible: (cb) => ((visCb = cb), () => (visCb = null)),
  };
  return {
    env,
    pending: () => timers.size,
    /** Advance time, firing due timers in order, letting promises settle between. */
    async advance(ms: number) {
      const end = t + ms;
      for (;;) {
        const next = [...timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
        if (!next || next[1].at > end) break;
        timers.delete(next[0]);
        t = next[1].at;
        next[1].fn();
        await tick();
      }
      t = end;
    },
    setHidden(h: boolean) {
      hidden = h;
    },
    async show() {
      hidden = false;
      visCb?.();
      await tick();
    },
  };
}
const tick = () => new Promise<void>((r) => setImmediate(r));
const ok = (v: string, pending: boolean): Outcome<string> => ({ kind: "ok", value: v, pending });

function run(outcomes: (() => Promise<Outcome<string>>)[], f = fakeEnv(), extra: { maxAttempts?: number; maxMs?: number } = {}) {
  const states: PollState[] = [];
  const values: string[] = [];
  let calls = 0;
  const signals: AbortSignal[] = [];
  const h = startBoundedPoll<string>({
    env: f.env,
    fetch: (sig) => {
      signals.push(sig);
      return outcomes[Math.min(calls++, outcomes.length - 1)]();
    },
    onValue: (v) => values.push(v),
    onState: (s) => states.push(s),
    ...extra,
  });
  return { h, states, values, f, calls: () => calls, signals };
}

test("stops as soon as the answer is complete", async () => {
  const r = run([async () => ok("a", true), async () => ok("ab", false)]);
  await tick();
  assert.deepEqual(r.states, ["checking", "checking"]);
  await r.f.advance(2000);
  assert.equal(r.states.at(-1), "done");
  assert.deepEqual(r.values, ["a", "ab"]);
  assert.equal(r.f.pending(), 0);
});

test("a never-finishing answer times out after maxAttempts, keeping the partial result", async () => {
  const r = run([async () => ok("part", true)], undefined, { maxAttempts: 3 });
  await tick();
  await r.f.advance(60_000);
  assert.equal(r.calls(), 3);
  assert.equal(r.states.at(-1), "timeout");
  assert.deepEqual(r.values, ["part", "part", "part"]);
  assert.equal(r.f.pending(), 0);
});

test("maxMs also bounds the poll, with backoff between asks", async () => {
  const r = run([async () => ok("p", true)], undefined, { maxAttempts: 99, maxMs: 8000 });
  await tick();
  await r.f.advance(100_000);
  assert.ok(r.calls() <= 3, `calls ${r.calls()}`);
  assert.equal(r.states.at(-1), "timeout");
});

test("a missing route is 'unavailable' with no retries; repeated errors end as 'error'", async () => {
  const m = run([async () => ({ kind: "missing" as const })]);
  await tick();
  assert.equal(m.states.at(-1), "unavailable");
  assert.equal(m.f.pending(), 0);
  const e = run([async () => ({ kind: "error" as const })], undefined, { maxAttempts: 2 });
  await tick();
  await e.f.advance(60_000);
  assert.equal(e.calls(), 2);
  assert.equal(e.states.at(-1), "error");
});

test("cancel aborts in-flight, clears timers and drops a late answer", async () => {
  let release!: (o: Outcome<string>) => void;
  const r = run([() => new Promise((res) => (release = res))]);
  r.h.cancel();
  assert.equal(r.signals[0].aborted, true);
  release(ok("stale person", false));
  await tick();
  assert.deepEqual(r.values, []);
  assert.deepEqual(r.states, ["checking"]);
  const r2 = run([async () => ok("a", true)]);
  await tick();
  assert.equal(r2.f.pending(), 1);
  r2.h.cancel();
  assert.equal(r2.f.pending(), 0);
  await r2.f.advance(60_000);
  assert.equal(r2.calls(), 1);
});

test("hidden page pauses the next ask until visible again", async () => {
  const r = run([async () => ok("a", true), async () => ok("ab", false)]);
  await tick();
  r.f.setHidden(true);
  await r.f.advance(10_000);
  assert.equal(r.calls(), 1);
  await r.f.show();
  assert.equal(r.calls(), 2);
  assert.equal(r.states.at(-1), "done");
});

test("retry restarts from scratch and ignores the old run's late answer", async () => {
  let release!: (o: Outcome<string>) => void;
  const r = run([() => new Promise((res) => (release = res)), async () => ok("fresh", false)]);
  r.h.retry();
  await tick();
  release(ok("old", false));
  await tick();
  assert.deepEqual(r.values, ["fresh"]);
  assert.equal(r.states.at(-1), "done");
});

test("denied (401/403) is terminal: one ask, error state, nothing scheduled", async () => {
  const r = run([async () => ({ kind: "denied" as const })]);
  await tick();
  assert.equal(r.calls(), 1);
  assert.equal(r.states.at(-1), "error");
  assert.equal(r.f.pending(), 0);
});

test("resuming after maxMs while hidden times out instead of asking again", async () => {
  const r = run([async () => ok("a", true)], undefined, { maxMs: 8000 });
  await tick();
  r.f.setHidden(true);
  await r.f.advance(20_000);
  assert.equal(r.calls(), 1);
  await r.f.show();
  assert.equal(r.calls(), 1);
  assert.equal(r.states.at(-1), "timeout");
});

test("retry while a hidden wait is pending cannot run two attempts at once", async () => {
  const r = run([async () => ok("a", true)]);
  await tick();
  r.f.setHidden(true);
  await r.f.advance(3000);
  r.h.retry();
  await tick();
  const before = r.calls();
  r.f.setHidden(false);
  await r.f.show();
  assert.equal(r.calls(), before);
});
