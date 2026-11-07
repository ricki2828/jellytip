import { test } from "node:test";
import assert from "node:assert/strict";
import { fullscreenStep, isFullscreenKey } from "../src/lib/fullscreenPlan.ts";

const base = { native: false, active: false, canElement: true, canVideo: true };

test("web enters element fullscreen on the container, exits when active", () => {
  assert.equal(fullscreenStep(base), "element");
  assert.equal(fullscreenStep({ ...base, active: true }), "exit");
});
test("native always uses the window mode", () => {
  assert.equal(fullscreenStep({ ...base, native: true, active: true }), "native");
});
test("iPhone Safari falls back to the video, else nothing", () => {
  assert.equal(fullscreenStep({ ...base, canElement: false }), "video");
  assert.equal(fullscreenStep({ ...base, canElement: false, canVideo: false }), "none");
});
test("F key ignores typing and modifiers", () => {
  assert.equal(isFullscreenKey({ key: "f" }, false), true);
  assert.equal(isFullscreenKey({ key: "F" }, false), true);
  assert.equal(isFullscreenKey({ key: "f" }, true), false);
  assert.equal(isFullscreenKey({ key: "f", ctrlKey: true }, false), false);
  assert.equal(isFullscreenKey({ key: "g" }, false), false);
});
