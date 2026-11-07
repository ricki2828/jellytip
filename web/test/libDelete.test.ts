import { test } from "node:test";
import assert from "node:assert/strict";
import { isDeleted, markDeleted, parseInfo, parseResult, sizeText, whatGoes } from "../src/lib/libDelete.ts";

test("sizes read in GB/MB, nothing when unknown", () => {
  assert.equal(sizeText(null), null);
  assert.equal(sizeText(5 * 1024 ** 3), "5.0 GB");
  assert.equal(sizeText(42 * 1024 ** 3), "42 GB");
  assert.equal(sizeText(300 * 1024 ** 2), "300 MB");
});

test("delete-info: only a 200 with canDelete true shows the action", () => {
  assert.equal(parseInfo(404, { error: "not found" }), null);
  assert.equal(parseInfo(401, null), null);
  assert.equal(parseInfo(200, { canDelete: false, reason: "no" }), null);
  const i = parseInfo(200, { canDelete: true, title: "Heat", kind: "movie", bytes: 9000, via: "radarr", note: "n" });
  assert.deepEqual([i?.title, i?.kind, i?.bytes, i?.via], ["Heat", "movie", 9000, "radarr"]);
});

test("delete result: ok, forbidden, unreachable, server error", () => {
  assert.deepEqual(parseResult(200, { ok: true, freedBytes: 5 }), { ok: true, freedBytes: 5 });
  assert.equal(parseResult(403, { ok: false }).ok, false);
  assert.match((parseResult(0, null) as { error: string }).error, /reached/);
  assert.equal((parseResult(502, { ok: false, error: "delete failed" }) as { error: string }).error, "delete failed");
});

test("what goes, per kind", () => {
  assert.match(whatGoes("series"), /whole series/);
  assert.match(whatGoes("season"), /season/);
  assert.match(whatGoes("episode"), /episode/);
  assert.match(whatGoes("movie"), /film/);
});

test("deleted ids are matched dashed or not, any case", () => {
  markDeleted("AAAA-bbbb");
  assert.equal(isDeleted("aaaabbbb"), true);
  assert.equal(isDeleted("cccc"), false);
});
