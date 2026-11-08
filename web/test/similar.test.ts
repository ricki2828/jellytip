import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSimilar, similarPath } from "../src/lib/similar.ts";

const vod = { id: "p1:m:42", title: "Heat", kind: "movie", tmdbId: 949 };

test("parseSimilar mixes library ids and IPTV titles in the server's order", () => {
  const row = parseSimilar({ items: [{ jfId: "abc" }, vod, "def", { jfId: "abc" }], source: "tmdb" }, "movie");
  assert.ok(row);
  assert.deepEqual(row.itemIds, ["abc", "def"]);
  assert.deepEqual(row.iptvItems.map((v) => v.id), ["p1:m:42"]);
  assert.deepEqual(row.order, ["abc", "p1:m:42", "def"]);
  assert.equal(row.title, "More like this");
  assert.equal(row.mediaType, "movie");
});

test("parseSimilar reads the server's final shape (source library / iptv)", () => {
  const row = parseSimilar({ items: [{ source: "library", id: "a".repeat(32), kind: "movie", title: "X", year: 2001 }, { ...vod, source: "iptv" }], source: "tmdb" }, "movie");
  assert.deepEqual(row?.itemIds, ["a".repeat(32)]);
  assert.deepEqual(row?.order, ["a".repeat(32), "p1:m:42"]);
});

test("parseSimilar: empty, junk and non-lists give null; caps at 20", () => {
  assert.equal(parseSimilar({ items: [] }, "movie"), null);
  assert.equal(parseSimilar({ items: [null, 5, {}] }, "series"), null);
  assert.equal(parseSimilar(null, "movie"), null);
  const many = Array.from({ length: 30 }, (_, i) => `id${i}`);
  assert.equal(parseSimilar({ items: many }, "series")?.order.length, 20);
  assert.equal(parseSimilar({ items: ["a"] }, "series")?.mediaType, "tv");
});

test("similarPath needs an id to ask with", () => {
  assert.equal(similarPath({ kind: "movie" }), null);
  assert.equal(similarPath({ kind: "movie", tmdb: 0 }), null);
  assert.equal(similarPath({ kind: "series", tmdb: 12, iptvId: "p:s:1", jfId: "x" }), "v1/similar?kind=series&tmdb=12&iptvId=p%3As%3A1&jfId=x");
});

test("server shape: library {source,id} and iptv VodItem", () => {
  const vod2 = { source: "iptv", id: "p1:m:7", kind: "movie", title: "The Matrix", year: 1999, tmdbId: 603 };
  const row = parseSimilar({ items: [{ source: "library", id: "0123456789abcdef0123456789abcdef", kind: "movie", title: "Heat", year: 1995 }, vod2], source: "tmdb" }, "movie");
  assert.ok(row);
  assert.deepEqual(row!.itemIds, ["0123456789abcdef0123456789abcdef"]);
  assert.equal(row!.iptvItems?.length, 1);
});
