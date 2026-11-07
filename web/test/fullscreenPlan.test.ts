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

import { exitAlreadyConsumed, keepFullscreenOnUnmount } from "../src/lib/fullscreenPlan.ts";

test("a channel hop keeps fullscreen only while the host survives", () => {
  assert.equal(keepFullscreenOnUnmount(true), true);
  assert.equal(keepFullscreenOnUnmount(false), false);
  assert.equal(keepFullscreenOnUnmount(undefined), false);
});
test("Escape right after a browser-side fullscreen exit is consumed", () => {
  assert.equal(exitAlreadyConsumed(1100, 1000), true);
  assert.equal(exitAlreadyConsumed(1400, 1000), false);
  assert.equal(exitAlreadyConsumed(1000, -Infinity), false);
});
