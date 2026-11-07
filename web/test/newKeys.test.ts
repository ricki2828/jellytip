import { test } from "node:test";
import assert from "node:assert/strict";
import { advanceNewKeys, newKeysInit } from "../src/lib/newKeys.ts";

test("first batch is never new", () => {
  const s = newKeysInit();
  assert.equal(advanceNewKeys(s, ["a", "b"]).size, 0);
});

test("only keys absent from the previous batch are new", () => {
  const s = newKeysInit();
  advanceNewKeys(s, ["a", "b"]);
  assert.deepEqual([...advanceNewKeys(s, ["c", "a", "b"])], ["c"]);
});

test("same batch re-read is idempotent (double render, 4/s re-renders)", () => {
  const s = newKeysInit();
  advanceNewKeys(s, ["a"]);
  const f = advanceNewKeys(s, ["b", "a"]);
  assert.equal(advanceNewKeys(s, ["b", "a"]), f);
  assert.deepEqual([...f], ["b"]);
});

test("seen is replaced, not accumulated", () => {
  const s = newKeysInit();
  advanceNewKeys(s, ["a", "b"]);
  advanceNewKeys(s, ["b", "c"]);
  assert.equal(s.seen!.size, 2);
  assert.equal(s.seen!.has("a"), false);
});
