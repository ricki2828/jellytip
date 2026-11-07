import { test } from "node:test";
import assert from "node:assert/strict";
import { NUDGE_MAX, clampNudge, doneNote, findNewTrack, loadNudge, nudgeKey, nudgeLabel, parsePoll, parseStart, progressText, saveNudge, speedText, stepNudge } from "../src/lib/subSync.ts";

const mem = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
};

test("nudge steps by 0.5 s without float drift and clamps", () => {
  let v = 0;
  for (let i = 0; i < 7; i++) v = stepNudge(v, 1);
  assert.equal(v, 3.5);
  assert.equal(stepNudge(0, -1), -0.5);
  assert.equal(stepNudge(0.3, 1), 0.8);
  assert.equal(stepNudge(NUDGE_MAX, 1), NUDGE_MAX);
  assert.equal(stepNudge(-NUDGE_MAX, -1), -NUDGE_MAX);
  assert.equal(clampNudge(NaN), 0);
  assert.equal(clampNudge(1e9), NUDGE_MAX);
});

test("nudge persists per title + subtitle track and 0 removes the key", () => {
  const s = mem();
  saveNudge("abc", 3, 1.5, s);
  saveNudge("abc", 4, -2, s);
  assert.equal(loadNudge("abc", 3, s), 1.5);
  assert.equal(loadNudge("abc", 4, s), -2);
  assert.equal(loadNudge("abc", 5, s), 0, "a different track has its own value");
  assert.equal(loadNudge("def", 3, s), 0, "a different title too");
  saveNudge("abc", 3, 0, s);
  assert.ok(!s.m.has(nudgeKey("abc", 3)));
  s.m.set(nudgeKey("abc", 9), "junk");
  assert.equal(loadNudge("abc", 9, s), 0);
  assert.equal(loadNudge("abc", 3, { getItem: () => { throw new Error("blocked"); } }), 0);
  assert.equal(nudgeLabel(1.5), "+1.5 s");
  assert.equal(nudgeLabel(-0.5), "−0.5 s");
  assert.equal(nudgeLabel(0), "0.0 s");
});

test("start reply: job, cached result, busy, server message", () => {
  assert.deepEqual(parseStart(200, { jobId: "0123456789ab" }), { jobId: "0123456789ab" });
  const c = parseStart(200, { cached: { offsetSec: -2.4, speed: 1, confidence: 1.4, label: "x" } });
  assert.ok("result" in c && c.result.offsetSec === -2.4 && c.result.newStreamIndex === null);
  assert.deepEqual(parseStart(429, {}), { error: "Matching is busy, try again in a minute" });
  assert.deepEqual(parseStart(422, { error: "Can't auto-match picture subtitles" }), { error: "Can't auto-match picture subtitles" });
  assert.deepEqual(parseStart(0, null), { error: "Auto-match isn't available right now" });
  assert.deepEqual(parseStart(404, null), { error: "Auto-match isn't available right now" });
  assert.ok("error" in parseStart(200, { jobId: "../x" }));
});

test("poll reply", () => {
  assert.deepEqual(parsePoll(200, { state: "running", etaSec: 30 }), { state: "running", etaSec: 30 });
  assert.deepEqual(parsePoll(200, { state: "failed", error: "Couldn't match this one confidently" }), { state: "failed", error: "Couldn't match this one confidently" });
  const d = parsePoll(200, { state: "done", result: { offsetSec: 2.4, speed: 1.0427, confidence: 1.3, label: "l", newStreamIndex: 7 } });
  assert.ok(d.state === "done" && d.result.newStreamIndex === 7);
  assert.equal(parsePoll(200, { state: "done" }).state, "failed", "a done without a result is not trusted");
  assert.equal(parsePoll(404, null).state, "failed");
});

test("messages", () => {
  assert.equal(progressText(null), "Matching…");
  assert.equal(progressText(32), "Matching… about 30 s");
  assert.equal(progressText(1), "Matching… about 5 s");
  const r = { offsetSec: 2.4, speed: 25 / 23.976, confidence: 1.3, label: "", newStreamIndex: 7, alreadySynced: false };
  assert.equal(doneNote(r), "Moved +2.4 s · speed 25→23.976 fps");
  assert.equal(doneNote({ ...r, offsetSec: -1.03, speed: 1 }), "Moved −1.0 s");
  assert.equal(doneNote({ ...r, alreadySynced: true }), "Already in sync");
  assert.equal(speedText(1.0004), null);
  assert.equal(speedText(1.0123), "speed ×1.0123");
});

test("finding the new track", () => {
  const before = [{ Index: 1, Type: "Audio" }, { Index: 2, Type: "Subtitle" }];
  const after = [...before, { Index: 3, Type: "Subtitle" }];
  assert.equal(findNewTrack(before, after, 3), 3);
  assert.equal(findNewTrack(before, after, null), 3, "no hint: the one new subtitle");
  assert.equal(findNewTrack(before, after, 99), 3, "stale hint falls back");
  assert.equal(findNewTrack(before, before, null), null, "not listed yet");
  assert.equal(findNewTrack(before, before, 2), null, "a hint that already existed is not new");
});
