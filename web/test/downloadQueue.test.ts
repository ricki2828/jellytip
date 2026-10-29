// node --test web/test/  (Node strips the types; no test framework dependency)
import { test } from "node:test";
import assert from "node:assert/strict";
import { activeCount, freeSlots, linePosition, nextPrepare, nextSeq, normaliseLimit, toStart, waitingLine, type QItem, type QState } from "../src/lib/downloadQueue.ts";

const item = (id: string, state: QState, seq: number): QItem => ({ id, state, seq });

test("only N files hold slots; the rest wait in added order", () => {
  const items = [item("e3", "waiting", 3), item("e1", "waiting", 1), item("e2", "waiting", 2), item("e4", "waiting", 4)];
  assert.deepEqual(toStart(items, 2), ["e1", "e2"]);
  assert.equal(linePosition(items, "e3"), 3);
});

test("finishing or failing frees a slot for the next in line", () => {
  const items = [item("a", "downloading", 1), item("b", "done", 2), item("c", "failed", 3), item("d", "waiting", 4), item("e", "waiting", 5)];
  assert.equal(activeCount(items), 1);
  assert.deepEqual(toStart(items, 2), ["d"]);
});

test("raising the limit starts more at once; lowering never cancels", () => {
  const running = [item("a", "downloading", 1), item("b", "queued", 2), item("c", "paused", 3), item("d", "waiting", 4), item("e", "waiting", 5)];
  assert.deepEqual(toStart(running, 5), ["d", "e"]);
  assert.equal(freeSlots(running, 1), 0); // 3 running, limit 1: nothing new starts, nothing is stopped
  assert.deepEqual(toStart(running, 1), []);
});

test("server preparation is one at a time and doesn't hold a download slot", () => {
  const items = [item("p2", "serverQueued", 2), item("p1", "serverQueued", 1), item("x", "waiting", 3)];
  assert.equal(nextPrepare(items), "p1");
  assert.deepEqual(toStart(items, 1), ["x"]);
  const preparing = [item("p1", "preparing", 1), item("p2", "serverQueued", 2)];
  assert.equal(nextPrepare(preparing), null);
  assert.equal(linePosition(preparing, "p2"), 1);
  assert.equal(linePosition(preparing, "p1"), null);
});

test("a prepared file joins the download line in its original place", () => {
  const items = [item("late", "waiting", 9), item("prepped", "waiting", 1), item("busy", "downloading", 0)];
  assert.deepEqual(toStart(items, 2), ["prepped"]);
  assert.deepEqual(waitingLine(items).map((i) => i.id), ["prepped", "late"]);
});

test("limit setting falls back to the default for anything unexpected", () => {
  assert.equal(normaliseLimit(3), 3);
  assert.equal(normaliseLimit("5"), 5);
  assert.equal(normaliseLimit(4), 2);
  assert.equal(normaliseLimit(undefined), 2);
});

test("season order survives same-millisecond adds", () => {
  const a = nextSeq(1000);
  const b = nextSeq(1000);
  const c = nextSeq(999);
  assert.ok(a < b && b < c);
});
