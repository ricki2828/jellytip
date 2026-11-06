import { test } from "node:test";
import assert from "node:assert/strict";
import { replayDone, replayTarget, startReplay } from "../src/lib/captionReplay.ts";

test("target jumps back 10 s, clamped to the seekable start, null without room", () => {
  assert.equal(replayTarget(100), 90);
  assert.equal(replayTarget(6), 0);
  assert.equal(replayTarget(1), null);
  assert.equal(replayTarget(105, 100), 100);
  assert.equal(replayTarget(101, 100), null);
  assert.equal(replayTarget(NaN), null);
});

test("subtitles off: show the fallback track, remember Off, restore once the span has played", () => {
  const r = startReplay(100, -1, 3, 0, null)!;
  assert.equal(r.seekTo, 90);
  assert.equal(r.show, 3);
  assert.deepEqual(r.replay, { restore: -1, until: 100, from: 90 });
  assert.equal(replayDone(r.replay!, 95), false);
  assert.equal(replayDone(r.replay!, 100), true);
});

test("a viewer who seeks away from the replayed span ends the replay", () => {
  const r = startReplay(100, -1, 3, 0, null)!.replay!;
  assert.equal(replayDone(r, 40), true);
  assert.equal(replayDone(r, 89.5), false);
});

test("captions already on: just rewind, nothing to restore", () => {
  const r = startReplay(100, 2, 3, 0, null)!;
  assert.equal(r.seekTo, 90);
  assert.equal(r.show, null);
  assert.equal(r.replay, null);
});

test("pressing again mid-replay keeps the ORIGINAL off state and extends the span", () => {
  const first = startReplay(100, -1, 3, 0, null)!.replay!;
  const again = startReplay(96, 3, 3, 0, first)!;
  assert.equal(again.show, null); // already showing the replay track
  assert.equal(again.replay!.restore, -1);
  assert.equal(again.replay!.until, 100);
  assert.equal(again.seekTo, 86);
});

test("no caption track or no room to seek: no replay", () => {
  assert.equal(startReplay(100, -1, null, 0, null), null);
  assert.equal(startReplay(1, -1, 3, 0, null), null);
});
