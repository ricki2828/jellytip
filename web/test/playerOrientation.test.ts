import { test } from "node:test";
import { strict as assert } from "node:assert";
import { canOfferRotate, nextPlayerOrientation } from "../src/lib/playerOrientation.ts";

test("rotate action alternates portrait and landscape", () => {
  assert.equal(nextPlayerOrientation("portrait-primary"), "landscape");
  assert.equal(nextPlayerOrientation("landscape-secondary"), "portrait");
});

test("rotate control is limited to native players and supported phone browsers", () => {
  assert.equal(canOfferRotate(true, false, false), true);
  assert.equal(canOfferRotate(false, true, true), true);
  assert.equal(canOfferRotate(false, true, false), false);
  assert.equal(canOfferRotate(false, false, true), false);
});
