import { test } from "node:test";
import assert from "node:assert/strict";
import { alternatesAfterSwitch, shouldSurfaceLiveStall } from "../src/lib/streamRecovery.ts";

test("live stall only surfaces after its threshold in foreground playback", () => {
  assert.equal(shouldSurfaceLiveStall(10_000, 0, 10_000, false, false, 0), true);
  assert.equal(shouldSurfaceLiveStall(9_999, 0, 10_000, false, false, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, true, false, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, false, true, 0), false);
  assert.equal(shouldSurfaceLiveStall(20_000, 0, 10_000, false, false, 20_001), false);
});

test("alternate switching keeps unselected candidates and appends the channel left", () => {
  const alternatives = [{ id: "b", name: "B" }, { id: "c", name: "C" }];
  assert.deepEqual(alternatesAfterSwitch(alternatives, { id: "a", name: "A" }, "b"), [
    { id: "c", name: "C" },
    { id: "a", name: "A" },
  ]);
});
