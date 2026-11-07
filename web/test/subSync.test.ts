import { test } from "node:test";
import assert from "node:assert/strict";
import { NUDGE_MAX, clampNudge, doneNote, findNewTrack, isAutoMatched, loadNudge, trackIdentity, nudgeKey, nudgeLabel, parsePoll, parseStart, progressText, saveNudge, speedText, stepNudge } from "../src/lib/subSync.ts";

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

test("nudge persists per title + track identity and 0 removes the key", () => {
  const s = mem();
  saveNudge("abc", "x|eng|subrip||a.srt|0", 1.5, s);
  saveNudge("abc", "e|eng|subrip||0", -2, s);
  assert.equal(loadNudge("abc", "x|eng|subrip||a.srt|0", s), 1.5);
  assert.equal(loadNudge("abc", "e|eng|subrip||0", s), -2);
  assert.equal(loadNudge("abc", "e|fra|subrip||0", s), 0, "a different track has its own value");
  assert.equal(loadNudge("def", "e|eng|subrip||0", s), 0, "a different title too");
  saveNudge("abc", "x|eng|subrip||a.srt|0", 0, s);
  assert.ok(!s.m.has(nudgeKey("abc", "x|eng|subrip||a.srt|0")));
  s.m.set(nudgeKey("abc", "j"), "junk");
  assert.equal(loadNudge("abc", "j", s), 0);
  assert.equal(loadNudge("abc", "e", { getItem: () => { throw new Error("blocked"); } }), 0);
  assert.equal(nudgeLabel(1.5), "+1.5 s");
  assert.equal(nudgeLabel(-0.5), "−0.5 s");
  assert.equal(nudgeLabel(0), "0.0 s");
});

test("track identity survives Jellyfin renumbering (an external file inserted first shifts every index)", () => {
  const emb = { Type: "Subtitle", Language: "eng", Codec: "subrip", DisplayTitle: "English - SUBRIP" };
  const before = [{ Index: 0, Type: "Video" }, { Index: 1, ...emb }, { Index: 2, ...emb }, { Index: 3, Type: "Subtitle", Language: "fra", Codec: "ass" }];
  const ext = { Index: 0, Type: "Subtitle", Language: "eng", Codec: "subrip", IsExternal: true, Path: "/m/Heat.en.x1.automatch.srt" };
  const after = [ext, ...before.map((s) => ({ ...s, Index: s.Index + 1 }))];
  assert.equal(trackIdentity(before, 1), trackIdentity(after, 2), "same track, new index");
  assert.equal(trackIdentity(before, 2), trackIdentity(after, 3));
  assert.notEqual(trackIdentity(before, 1), trackIdentity(before, 2), "identical-looking tracks are told apart by order");
  assert.notEqual(trackIdentity(after, 0), trackIdentity(after, 1));
  assert.equal(trackIdentity(before, 0), null, "not a subtitle");
  // nudges follow: saved on the old index's identity, found again under the new index
  const m = mem(); saveNudge("t", trackIdentity(before, 3)!, 2, m);
  assert.equal(loadNudge("t", trackIdentity(after, 4)!, m), 2);
  assert.equal(loadNudge("t", trackIdentity(after, 0)!, m), 0, "the new file does not inherit");
});

test("start reply: job, cached result, busy, server message", () => {
  assert.deepEqual(parseStart(200, { jobId: "0123456789ab" }), { jobId: "0123456789ab" });
  const c = parseStart(200, { cached: { offsetSec: -2.4, speed: 1, confidence: 1.4, label: "x" } });
  assert.ok("result" in c && c.result.offsetSec === -2.4 && c.result.newStreamIndex === null && c.result.fileName === null);
  const c2 = parseStart(200, { cached: { offsetSec: 1, newStreamIndex: 5, fileName: "Heat.en.x1.automatch.srt" } });
  assert.ok("result" in c2 && c2.result.fileName === "Heat.en.x1.automatch.srt" && c2.result.newStreamIndex === 5);
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
  const r = { offsetSec: 2.4, speed: 25 / 23.976, confidence: 1.3, label: "", newStreamIndex: 7, fileName: null, alreadySynced: false };
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

test("a repeat (cached hit) switches to the EXISTING track, found by file name even when its index is old", () => {
  const ext = { Index: 2, Type: "Subtitle", IsExternal: true, Path: "/m/Heat.en.xab12cd.automatch.srt" };
  const streams = [{ Index: 0, Type: "Video" }, { Index: 1, Type: "Subtitle", IsExternal: true, Path: "/m/Heat.en.srt" }, ext];
  // `before` already has the track: "new since before" finds nothing, the file name does
  assert.equal(findNewTrack(streams, streams, 2, null), null);
  assert.equal(findNewTrack(streams, streams, null, "Heat.en.xab12cd.automatch.srt"), 2);
  assert.equal(findNewTrack(streams, streams, 9, "Heat.en.xab12cd.automatch.srt"), 2, "file name beats a stale index");
  assert.equal(findNewTrack(streams, streams, null, "Other.en.x1.automatch.srt"), null, "not listed yet");
  assert.equal(findNewTrack(streams, streams, null, "Heat.en.srt".replace("srt", "mkv")), null);
  assert.equal(isAutoMatched(ext), true);
  assert.equal(isAutoMatched(streams[1]), false);
  assert.equal(isAutoMatched({}), false);
});
