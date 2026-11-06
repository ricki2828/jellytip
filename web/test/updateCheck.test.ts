import { test } from "node:test";
import assert from "node:assert/strict";
import { createUpdateChecker, decideToast, parseVersionJson } from "../src/lib/updateCheck.ts";

const MIN = 60_000;
const ok = { sha: "bbbb222", builtAt: "", assetsVersion: "" };

test("parseVersionJson: valid, text, and junk", () => {
  assert.deepEqual(parseVersionJson({ sha: "abc1234", builtAt: "2026-11-06T10:00:00Z", assetsVersion: "x1" }), { sha: "abc1234", builtAt: "2026-11-06T10:00:00Z", assetsVersion: "x1" });
  assert.equal(parseVersionJson('{"sha":"abc1234"}')?.sha, "abc1234");
  assert.deepEqual(parseVersionJson({ sha: "abc1234", builtAt: 5 }), { sha: "abc1234", builtAt: "", assetsVersion: "" });
  for (const bad of [null, undefined, 3, [], "", "<html>", "{bad", {}, { sha: "" }, { sha: "dev" }, { sha: 12345678 }, { sha: 'a"b c' }]) assert.equal(parseVersionJson(bad), null, JSON.stringify(bad));
});

test("decideToast", () => {
  assert.equal(decideToast({ running: "aaaa111", latest: ok, routeName: "home" }), "show");
  assert.equal(decideToast({ running: "aaaa111", latest: ok, routeName: "play" }), "defer");
  assert.equal(decideToast({ running: "aaaa111", latest: ok, routeName: "iptvplay" }), "defer");
  assert.equal(decideToast({ running: "bbbb222", latest: ok, routeName: "home" }), "none");
  assert.equal(decideToast({ running: "aaaa111", latest: null, routeName: "home" }), "none");
  assert.equal(decideToast({ running: "dev", latest: ok, routeName: "home" }), "none");
  assert.equal(decideToast({ running: "aaaa111", latest: ok, routeName: "home", dismissedSha: "bbbb222" }), "none");
  // a newer build than the dismissed one shows again
  assert.equal(decideToast({ running: "aaaa111", latest: { ...ok, sha: "cccc333" }, routeName: "home", dismissedSha: "bbbb222" }), "show");
});

function harness(opts: { visible?: boolean; failures?: number } = {}) {
  let t = 1_000_000;
  let visible = opts.visible ?? true;
  let failLeft = opts.failures ?? 0;
  const timers = new Map<number, { at: number; fn: () => void }>();
  let id = 0;
  const calls: number[] = [];
  const c = createUpdateChecker({
    check: async () => {
      calls.push(t);
      if (failLeft-- > 0) throw new Error("net");
    },
    now: () => t,
    isVisible: () => visible,
    setTimer: (fn, ms) => (timers.set(++id, { at: t + ms, fn }), id),
    clearTimer: (h) => void timers.delete(h as number),
  });
  /** advance the clock, firing due timers in order, letting async checks settle */
  async function advance(ms: number) {
    const end = t + ms;
    for (;;) {
      const due = [...timers.entries()].filter(([, x]) => x.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]);
      t = Math.max(t, due[1].at);
      due[1].fn();
      await new Promise((r) => setImmediate(r));
    }
    t = end;
  }
  return { c, calls, advance, setVisible: (v: boolean) => (visible = v), pending: () => timers.size, now: () => t };
}

test("checks immediately, then every 10 minutes", async () => {
  const h = harness();
  h.c.start();
  await h.advance(0);
  assert.equal(h.calls.length, 1);
  await h.advance(9 * MIN);
  assert.equal(h.calls.length, 1);
  await h.advance(MIN);
  assert.equal(h.calls.length, 2);
  await h.advance(10 * MIN);
  assert.equal(h.calls.length, 3);
  h.c.stop();
  await h.advance(60 * MIN);
  assert.equal(h.calls.length, 3);
});

test("hidden: no checks; becoming visible checks, but never within 2 minutes of the last", async () => {
  const h = harness({ visible: false });
  h.c.start();
  await h.advance(60 * MIN);
  assert.equal(h.calls.length, 0);
  h.setVisible(true);
  h.c.onVisibility();
  await h.advance(0);
  assert.equal(h.calls.length, 1);
  // flip hidden/visible rapidly: floor holds
  h.setVisible(false);
  await h.advance(30_000);
  h.setVisible(true);
  h.c.onVisibility();
  await h.advance(0);
  assert.equal(h.calls.length, 1);
  await h.advance(MIN + 31_000);
  assert.equal(h.calls.length, 2);
  assert.ok(h.calls[1] - h.calls[0] >= 2 * MIN);
});

test("hidden when the timer fires: skipped until visible", async () => {
  const h = harness();
  h.c.start();
  await h.advance(0);
  h.setVisible(false);
  await h.advance(30 * MIN);
  assert.equal(h.calls.length, 1);
  h.setVisible(true);
  h.c.onVisibility();
  await h.advance(0);
  assert.equal(h.calls.length, 2);
});

test("errors back off exponentially (capped) and success resets", async () => {
  const h = harness({ failures: 3 });
  h.c.start();
  await h.advance(0); // fail 1 -> wait 20m
  assert.equal(h.calls.length, 1);
  await h.advance(19 * MIN);
  assert.equal(h.calls.length, 1);
  await h.advance(MIN); // 20m: fail 2 -> wait 40m
  assert.equal(h.calls.length, 2);
  await h.advance(39 * MIN);
  assert.equal(h.calls.length, 2);
  await h.advance(MIN); // fail 3 -> wait 60m (cap)
  assert.equal(h.calls.length, 3);
  await h.advance(59 * MIN);
  assert.equal(h.calls.length, 3);
  await h.advance(MIN); // succeeds -> back to 10m
  assert.equal(h.calls.length, 4);
  await h.advance(10 * MIN);
  assert.equal(h.calls.length, 5);
});

test("onVisibility before start / after stop does nothing", async () => {
  const h = harness();
  h.c.onVisibility();
  await h.advance(5 * MIN);
  assert.equal(h.calls.length, 0);
  h.c.start();
  h.c.stop();
  h.c.onVisibility();
  await h.advance(30 * MIN);
  assert.equal(h.calls.length, 0);
});
