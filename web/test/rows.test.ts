import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRows } from "../src/lib/rows.ts";

const coll = { tmdbId: 945, name: "Lethal Weapon Collection", partsOrder: [{ tmdbId: 941, title: "Lethal Weapon", itemId: "a" }], watchedCount: 1, total: 4, inLibrary: 4, itemIds: ["a"] };

test("no rows field = old server", () => {
  assert.equal(parseRows(undefined), null);
  assert.deepEqual(parseRows([]), []);
});

test("unknown layout renders standard; franchise without a collection too", () => {
  const rows = parseRows([
    { id: "x", title: "X", layout: "hologram", itemIds: ["1"] },
    { id: "f", title: "F", layout: "franchise", itemIds: ["1"] },
    { id: "g", title: "G", layout: "franchise", itemIds: ["a"], collection: coll },
    { id: "p", title: "P", layout: "poster", itemIds: ["1"], mediaType: "tv" },
  ])!;
  assert.deepEqual(rows.map((r) => [r.id, r.layout, r.mediaType]), [["x", "standard", "movie"], ["f", "standard", "movie"], ["g", "franchise", "movie"], ["p", "poster", "tv"]]);
  assert.equal(rows[2].collection?.watchedCount, 1);
});

test("rows without id/title/items and duplicate ids are dropped; item ids deduped in order", () => {
  const rows = parseRows([null, { id: "a", title: "", itemIds: ["1"] }, { id: "b", title: "B", itemIds: [] }, { id: "c", title: "C", itemIds: ["2", "1", "2", 7], untitled: ["1", null] }, { id: "c", title: "C2", itemIds: ["3"] }])!;
  assert.deepEqual(rows.map((r) => [r.id, r.itemIds, r.untitled]), [["c", ["2", "1"], ["1"]]]);
});
