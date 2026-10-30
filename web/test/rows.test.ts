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

import { inOrder } from "../src/lib/rows.ts";
test("mixed rows follow the server order; unnamed tiles keep their place at the end", () => {
  const tiles = [{ id: "a" }, { id: "b" }, { id: "1:m:9" }, { id: "z" }];
  assert.deepEqual(inOrder(tiles, ["1:m:9", "b", "a"]).map((t) => t.id), ["1:m:9", "b", "a", "z"]);
  assert.deepEqual(inOrder(tiles, []).map((t) => t.id), ["a", "b", "1:m:9", "z"]);
  assert.deepEqual(parseRows([{ id: "r", title: "T", itemIds: ["a"], order: ["a", 3] }])?.[0].order, ["a"]);
});

import { trendEntries } from "../src/lib/rows.ts";

test("trending row: ranks from the server, order as fallback, missing slotted in, capped at 10", () => {
  const row = parseRows([
    {
      id: "trending-top10-movies",
      title: "Top 10 trending films",
      itemIds: ["aaaa-1111", "bbbb2222"],
      iptvItems: [{ id: "3:m:9", title: "Léon", rank: 2 }],
      order: ["bbbb2222", "aaaa1111", "3:m:9"],
      rank: { "AAAA-1111": 4, bbbb2222: 1 },
      missing: [{ tmdbId: 5, title: "Gone", poster: "https://x/p.jpg", rank: 3 }, { tmdbId: null, title: "Unresolved", rank: 9 }, { tmdbId: 6 }],
    },
  ])![0];
  assert.deepEqual(row.missing.map((m) => m.tmdbId), [5, null]);
  const e = trendEntries(row, ["aaaa-1111", "bbbb2222"]);
  assert.deepEqual(e.map((x) => [x.kind, x.rank]), [["lib", 1], ["iptv", 2], ["missing", 3], ["lib", 4], ["missing", 9]]);
  const bare = parseRows([{ id: "trending-top10-series", title: "T", itemIds: ["a", "b"], order: ["b", "a"] }])![0];
  assert.deepEqual(trendEntries(bare, ["a", "b"]).map((x) => (x.kind === "lib" ? x.id : "")), ["b", "a"]);
});
