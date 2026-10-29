import { test } from "node:test";
import assert from "node:assert/strict";
import { focalFit, focalOrigin, focalPosition } from "../src/lib/focal.ts";

// The Quiet Girl hero (idx 2): face focal 0.306, headTop 0.0, a 16:9 backdrop in a wide desktop hero / tall phone hero
const quiet = { x: 0.5, y: 0.306, face: true, headTop: 0 };

test("head clamp: the headTop line is never above the frame", () => {
  for (const [cw, ch] of [[1600, 690], [1920, 830], [412, 700]]) {
    const iw = 1920, ih = 1080;
    const { y, sh } = focalFit(quiet, cw, ch, iw, ih);
    assert.ok(y + quiet.headTop * sh >= -0.5, `head cut at ${cw}x${ch}: y=${y}`);
  }
  // Without headTop the old maths would have cut it on the wide desktop hero
  const old = focalFit({ ...quiet, headTop: null }, 1600, 500, 1920, 1080);
  assert.ok(old.y < 0);
  assert.equal(focalFit(quiet, 1600, 500, 1920, 1080).y, 0);
});

test("headTop lower in the frame still lets the crop move down, and stays inside the image", () => {
  const f = { x: 0.5, y: 0.7, face: true, headTop: 0.45 };
  const { y, sh } = focalFit(f, 1600, 500, 1920, 1080);
  assert.ok(y <= 0 && y >= 500 - sh);
  assert.ok(y + 0.45 * sh >= 0);
  assert.match(focalPosition(f, 1600, 500, 1920, 1080), /^-?\d+px -?\d+px$/);
});

test("Ken Burns origin sits at or above the head line", () => {
  assert.equal(focalOrigin(quiet, 1600, 690, 1920, 1080), "50% 0%");
  assert.equal(focalOrigin({ x: 0.5, y: 0.5, face: false }, 1600, 690, 1920, 1080), "50% 40%");
  assert.equal(focalOrigin(null), "50% 30%");
});
