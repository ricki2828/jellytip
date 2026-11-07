import { test } from "node:test";
import assert from "node:assert/strict";
import { squareCrop, decodeFailMessage } from "../src/lib/avatarCrop.ts";

test("landscape crops the sides, centered", () => {
  assert.deepEqual(squareCrop(4000, 3000), { sx: 500, sy: 0, side: 3000 });
});
test("portrait crops top and bottom, centered", () => {
  assert.deepEqual(squareCrop(3000, 4000), { sx: 0, sy: 500, side: 3000 });
});
test("square is untouched; tiny and odd sizes stay integral and in bounds", () => {
  assert.deepEqual(squareCrop(512, 512), { sx: 0, sy: 0, side: 512 });
  for (const [w, h] of [[101, 50], [50, 101], [1, 7], [333, 334]]) {
    const { sx, sy, side } = squareCrop(w, h);
    assert.ok(Number.isInteger(sx) && Number.isInteger(sy) && Number.isInteger(side));
    assert.ok(sx >= 0 && sy >= 0 && sx + side <= w && sy + side <= h);
  }
});
test("HEIC gets its own message", () => {
  assert.match(decodeFailMessage({ type: "image/heic", name: "a" }), /HEIC/);
  assert.match(decodeFailMessage({ type: "", name: "IMG_1.HEIF" }), /HEIC/);
  assert.doesNotMatch(decodeFailMessage({ type: "image/jpeg", name: "a.jpg" }), /HEIC/);
});
