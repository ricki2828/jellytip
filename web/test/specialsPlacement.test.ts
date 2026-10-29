import { test } from "node:test";
import assert from "node:assert/strict";
import { placeSpecials } from "../src/lib/specialsPlacement.ts";

const sp = (id: string, position: number) => ({ id, position });
const ids = (slots: { id: string }[][]) => slots.map((s) => s.map((x) => x.id));

test("each special goes before its row; past the end goes last", () => {
  assert.deepEqual(ids(placeSpecials(3, [sp("a", 0), sp("b", 2), sp("c", 9)])), [["a"], [], ["b"], ["c"]]);
});

test("ties keep server order; bad positions count as 0; duplicates dropped", () => {
  assert.deepEqual(ids(placeSpecials(2, [sp("x", 1), sp("y", 1), sp("z", -4), sp("n", NaN), sp("x", 0)])), [["z", "n"], ["x", "y"], []]);
});

test("no rows yet: everything at the end slot", () => {
  assert.deepEqual(ids(placeSpecials(0, [sp("a", 0), sp("b", 3)])), [["a", "b"]]);
});
