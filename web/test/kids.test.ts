import { test } from "node:test";
import assert from "node:assert/strict";
import { kidsAllowed, ratingAge } from "../src/lib/kids.ts";

test("rating → age across schemes, prefixes stripped, unknown = 18", () => {
  assert.equal(ratingAge("G"), 0);
  assert.equal(ratingAge("PG"), 7);
  assert.equal(ratingAge("PG-13"), 13);
  assert.equal(ratingAge("NZ-M"), 13);
  assert.equal(ratingAge("GB-15"), 16);
  assert.equal(ratingAge("AU-MA15+"), 16);
  assert.equal(ratingAge("R"), 18);
  assert.equal(ratingAge("TV-MA"), 18);
  assert.equal(ratingAge("nz-r16"), 16);
  assert.equal(ratingAge(""), 18);
  assert.equal(ratingAge(undefined), 18);
  assert.equal(ratingAge("Unrated"), 18);
});

test("allowed against the ceiling", () => {
  assert.ok(kidsAllowed("PG", 7));
  assert.ok(!kidsAllowed("PG-13", 10));
  assert.ok(!kidsAllowed(null, 16));
});
