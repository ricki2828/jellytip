import { test } from "node:test";
import assert from "node:assert/strict";
import { FIXTURES_RETRY_MAX_MS, fixturesOutcome, fixturesRetryMs } from "../src/lib/gameCentre.ts";

test("only a 404 means the fixtures route is not there; everything else is retried", () => {
  assert.equal(fixturesOutcome(200), "ok");
  assert.equal(fixturesOutcome(404), "missing");
  for (const s of [0, 500, 502, 503, 504, 403, 429, 408, null, undefined]) assert.equal(fixturesOutcome(s), "retry", String(s));
});

test("retries back off from 5 s, doubling, capped at 5 min", () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7].map(fixturesRetryMs), [5_000, 10_000, 20_000, 40_000, 80_000, 160_000, 300_000]);
  assert.equal(fixturesRetryMs(50), FIXTURES_RETRY_MAX_MS);
  assert.equal(fixturesRetryMs(0), 5_000);
});
