import { test } from "node:test";
import assert from "node:assert/strict";
import { detailBadges, parseTech, registerTechs, techOf, tileBadges } from "../src/lib/techBadges.ts";

test("parse: known values only, upper-cased, once", () => {
  assert.deepEqual(parseTech(["4k", "DV", "dv", "IMAX", 3, null, "atmos"]), ["4K", "DV", "ATMOS"]);
  assert.deepEqual(parseTech("4K"), []);
  assert.deepEqual(parseTech(undefined), []);
});

test("tile: one video (4K folded in) + one object-audio badge", () => {
  assert.deepEqual(tileBadges(["4K", "DV", "HDR", "ATMOS", "TRUEHD"]), ["4K DV", "ATMOS"]);
  assert.deepEqual(tileBadges(["4K", "HDR", "DTSX"]), ["4K HDR", "DTS:X"]);
  assert.deepEqual(tileBadges(["HDR10+"]), ["HDR10+"]);
  assert.deepEqual(tileBadges(["4K"]), ["4K"]);
  assert.deepEqual(tileBadges(["TRUEHD", "DTSHDMA"]), []);
  assert.deepEqual(tileBadges([]), []);
});

test("details: the full list in contract order", () => {
  assert.deepEqual(detailBadges(["ATMOS", "4K", "TRUEHD", "DV"]), ["4K", "Dolby Vision", "Dolby Atmos", "TrueHD"]);
  assert.deepEqual(detailBadges(["DTSHDMA"]), ["DTS-HD MA"]);
});

test("registry: maps from payloads, junk ignored, empty list clears", () => {
  registerTechs({ a: ["4K", "ATMOS"], b: "nope", c: [] });
  registerTechs(null);
  registerTechs(["x"]);
  assert.deepEqual(techOf("a"), ["4K", "ATMOS"]);
  assert.deepEqual(techOf("b"), []);
  registerTechs({ a: [] });
  assert.deepEqual(techOf("a"), []);
  assert.deepEqual(techOf(null), []);
});
