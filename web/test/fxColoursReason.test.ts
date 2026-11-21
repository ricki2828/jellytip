import { test } from "node:test";
import assert from "node:assert/strict";
import { parseFixtureList } from "../src/lib/gameCentre.ts";
import { pairColors } from "../src/lib/broadcast.ts";
import { parseRows, rowReason } from "../src/lib/rows.ts";

const fx = (home: Record<string, unknown>, away: Record<string, unknown>) =>
  parseFixtureList({ items: [{ fid: "f1", kickoff: 1_800_000_000, status: "in", home: { name: "Penrith Panthers", ...home }, away: { name: "New Zealand Warriors", ...away } }] }).items[0];

test("fixture sides carry their colours ('#rrggbb', lower-cased); absent or malformed = null", () => {
  const f = fx({ color: "#2A2A2A", altColor: "#e4003b" }, { color: "#0b2a4a" });
  assert.equal(f.homeColor, "#2a2a2a");
  assert.equal(f.homeAlt, "#e4003b");
  assert.equal(f.awayColor, "#0b2a4a");
  assert.equal(f.awayAlt, null);
  const g = fx({ color: "red" }, { color: "#12345" });
  assert.equal(g.homeColor, null);
  assert.equal(g.awayColor, null);
  // events (F1 / golf): a title, no sides, no colours
  const ev = parseFixtureList({ items: [{ fid: "e", kickoff: 1, status: "pre", title: "Mexico City GP" }] }).items[0];
  assert.equal(ev.homeColor, null);
});

test("same clash guard as the match record: near-identical primaries switch the away side to its alt", () => {
  assert.deepEqual(pairColors("#ef0107", "#ee0000", "#00b2a9"), { home: "#ef0107", away: "#00b2a9", known: true });
  assert.deepEqual(pairColors("#ef0107", "#063672", "#00b2a9"), { home: "#ef0107", away: "#063672", known: true });
  assert.equal(pairColors(null, null).known, false);
  assert.equal(pairColors("#ef0107", null).known, true);
});

test("For you reason: kept as one trimmed line, capped; absent when not a string", () => {
  const rows = parseRows([
    { id: "for-you", title: "For you", itemIds: ["a"], reason: "  Because you like   Martin Scorsese and 2010s drama " },
    { id: "other", title: "Other", itemIds: ["b"] },
  ])!;
  assert.equal(rows[0].reason, "Because you like Martin Scorsese and 2010s drama");
  assert.equal(rows[1].reason, null);
  assert.equal(rowReason(42), null);
  assert.equal(rowReason("   "), null);
  assert.equal(rowReason("x".repeat(200))!.length, 140);
});
