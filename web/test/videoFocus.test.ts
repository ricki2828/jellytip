import { test } from "node:test";
import assert from "node:assert/strict";
import { claimVideo, releaseVideo, videoOwner } from "../src/lib/videoFocus.ts";

test("latest claim plays; release hands back to the previous claimant, then to nobody (the hero)", () => {
  assert.equal(videoOwner(), null);
  claimVideo("trailer");
  claimVideo("closet");
  assert.equal(videoOwner(), "closet");
  claimVideo("trailer"); // focus wins it back
  assert.equal(videoOwner(), "trailer");
  releaseVideo("trailer");
  assert.equal(videoOwner(), "closet");
  releaseVideo("closet");
  releaseVideo("closet");
  assert.equal(videoOwner(), null);
});
