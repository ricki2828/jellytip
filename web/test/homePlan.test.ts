import { test } from "node:test";
import assert from "node:assert/strict";
import { TOP_KEY, dedupeIds, interleaveSpecials, planHome, readLocalHome, shouldDefer, writeLocalHome, type Cand } from "../src/lib/homePlan.ts";

const ids = (p: string, n: number) => Array.from({ length: n }, (_, i) => `${p}${i}`);
const row = (key: string, n = 8, extra: Partial<Cand> = {}): Cand => ({ key, ids: ids(key, n), count: n, ...extra });
const none = { pinned: [], hidden: [] };

test("order: pick first, then 4 discovery rows, the rest in More", () => {
  const c = [row("a"), row("b"), row("pick", 5, { pick: true }), row("c"), row("d"), row("e"), row("f")];
  const p = planHome(c, none, null);
  assert.deepEqual(p.visible, ["pick", "a", "b", "c", "d"]);
  assert.deepEqual(p.more, ["e", "f"]);
});

test("pinned rows come right after the pick, always visible, not counted in the 4", () => {
  const c = [row("a"), row("b"), row("c"), row("d"), row("e"), row("z")];
  const p = planHome(c, { pinned: ["z"], hidden: [] }, null);
  assert.deepEqual(p.visible, ["z", "a", "b", "c", "d"]);
  assert.deepEqual(p.more, ["e"]);
});

test("quality: under 4 tiles dropped; 80%+ watched dropped", () => {
  const w = new Set(ids("b", 8).slice(0, 7)); // 87%
  const p = planHome([row("a", 3), row("b"), row("c")], none, w);
  assert.deepEqual(p.dropped, ["a", "b"]);
  assert.deepEqual(p.visible, ["c"]);
  // exactly 80% goes too, 75% stays
  assert.deepEqual(planHome([row("b", 5)], none, new Set(ids("b", 5).slice(0, 4))).dropped, ["b"]);
  assert.deepEqual(planHome([row("b", 8)], none, new Set(ids("b", 8).slice(0, 6))).visible, ["b"]);
});

test("watched unknown (null) filters nothing; unfilterable rows skip the watched rule", () => {
  assert.deepEqual(planHome([row("a")], none, null).visible, ["a"]);
  assert.deepEqual(planHome([{ key: "x", ids: null, count: 6 }], none, new Set()).visible, ["x"]);
});

test("pin beats the automatic filters; hide beats pin", () => {
  const w = new Set(ids("a", 8));
  assert.deepEqual(planHome([row("a", 2), row("b")], { pinned: ["a"], hidden: [] }, w).visible, ["a", "b"]);
  const p = planHome([row("a"), row("b")], { pinned: ["a"], hidden: ["a"] }, null);
  assert.deepEqual(p.visible, ["b"]);
  assert.deepEqual(p.dropped, ["a"]);
});

test("dedupe: an item in 3+ earlier rows is skipped later, but never below 4 tiles", () => {
  const seen = new Map([["x", 3], ["y", 2]]);
  assert.deepEqual(dedupeIds(["x", "y", "p", "q", "r", "s"], seen), ["y", "p", "q", "r", "s"]);
  assert.deepEqual(dedupeIds(["x", "y", "p", "q", "r"], seen), ["y", "p", "q", "r"]);
  assert.deepEqual(dedupeIds(["x", "p", "q", "r"], seen), ["x", "p", "q", "r"]); // would leave 3: keep all
});

test("dedupe in a plan: shared ids thin the fourth row only", () => {
  const shared = ["s1", "s2"];
  const mk = (k: string) => ({ key: k, ids: [...shared, ...ids(k, 5)], count: 7 });
  const p = planHome([mk("a"), mk("b"), mk("c"), mk("d")], none, null);
  assert.equal(p.ids.a, undefined);
  assert.equal(p.ids.c, undefined);
  assert.deepEqual(p.ids.d, ids("d", 5));
});

test("dedupe never touches pinned rows", () => {
  const mk = (k: string) => ({ key: k, ids: ["s1", ...ids(k, 5)], count: 6 });
  const p = planHome([mk("a"), mk("b"), mk("c"), mk("d")], { pinned: ["d"], hidden: [] }, null);
  assert.equal(p.ids.d, undefined);
});

test("late rows: defer when the focused row would move or vanish, else apply", () => {
  const cur = ["pick", "a", "b"];
  assert.equal(shouldDefer(cur, ["pick", "a", "b", "c"], "a"), false); // appended after
  assert.equal(shouldDefer(cur, ["new", "pick", "a", "b"], "a"), true); // inserted above
  assert.equal(shouldDefer(cur, ["pick", "b"], "b"), true); // row above removed
  assert.equal(shouldDefer(cur, ["pick", "b"], "a"), true); // focused row gone
  assert.equal(shouldDefer(cur, ["new", "pick", "a", "b"], null), false); // nothing focused
  assert.equal(shouldDefer(cur, ["new", "a"], "elsewhere"), false); // focus not in the plan
  assert.equal(shouldDefer(cur, ["pick", "x", "b"], "b"), true); // a row above is swapped: still a visible change
});

test("local home choices round-trip per user and survive junk", () => {
  const m = new Map<string, string>();
  const store = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
  assert.ok(writeLocalHome(store, "u1", { pinned: ["a"], hidden: ["b"] }));
  assert.deepEqual(readLocalHome(store, "u1"), { pinned: ["a"], hidden: ["b"] });
  assert.deepEqual(readLocalHome(store, "u2"), { pinned: [], hidden: [] });
  m.set("jellytip.homeLocal.u3", "{oops");
  assert.deepEqual(readLocalHome(store, "u3"), { pinned: [], hidden: [] });
});

test("specials land before the row they asked for; the pick still leads the plan", () => {
  const c: Cand[] = [row("a"), row("b"), row("c"), { key: "special-s1", ids: null, count: 99 }, { key: "special-p", ids: null, count: 99, pick: true }];
  const order = interleaveSpecials(c, [{ id: "s1", position: 1 }, { id: "p", position: 2 }]).map((x) => x.key);
  assert.deepEqual(order, ["a", "special-s1", "b", "special-p", "c"]);
  assert.deepEqual(planHome(interleaveSpecials(c, [{ id: "s1", position: 1 }, { id: "p", position: 2 }]), none, null).visible, ["special-p", "a", "special-s1", "b", "c"]);
});

test("focus on Continue Watching (above the first row): a new first row waits; appended rows don't", () => {
  const cur = ["a", "b"];
  assert.equal(shouldDefer(cur, ["pick", "a", "b"], TOP_KEY), true); // Tonight's pick arrives above
  assert.equal(shouldDefer(cur, ["a", "b", "c"], TOP_KEY), false);
  assert.equal(shouldDefer([], ["pick", "a"], TOP_KEY), false); // nothing shown yet
});

test("watched ratio is over the sampled ids only", () => {
  const long = ids("a", 100);
  const w = new Set(long.slice(0, 40)); // all 40 sampled are watched
  assert.deepEqual(planHome([{ key: "a", ids: long, count: 100 }], none, w).dropped, ["a"]);
});

test("specials have their own cap and don't use up the 4 row slots", () => {
  const sp = (n: string): Cand => ({ key: `special-${n}`, ids: null, count: 99 });
  const c = [sp("1"), sp("2"), sp("3"), row("a"), row("b"), row("c"), row("d"), row("e")];
  const p = planHome(c, none, null);
  assert.deepEqual(p.visible, ["special-1", "special-2", "a", "b", "c", "d"]);
  assert.deepEqual(p.more, ["special-3", "e"]);
});
