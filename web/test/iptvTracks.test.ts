import { test } from "node:test";
import assert from "node:assert/strict";
import { hlsChoices, nativeChoices, nativeSubtitlesOff, trackLabel } from "../src/lib/iptvTracks.ts";

test("track labels use names, languages and a fallback", () => {
  assert.equal(trackLabel("en", "English", 0), "English · en");
  assert.equal(trackLabel("und", "", 1), "Track 2");
});
test("HLS selection follows the current index after a list update", () => {
  assert.deepEqual(hlsChoices([{ name: "A" }, { name: "B" }], 1).map((t) => t.active), [false, true]);
  assert.deepEqual(hlsChoices([{ name: "B" }], 0).map((t) => t.active), [true]);
  assert.equal(hlsChoices([], -1).length, 0);
});
test("native text tracks expose Off when none is showing", () => {
  const tracks = nativeChoices([{ label: "English", kind: "subtitles", mode: "disabled" }], true);
  assert.equal(tracks.some((track) => track.active), false);
  assert.equal(nativeSubtitlesOff([{ kind: "metadata", mode: "showing" }, { kind: "subtitles", mode: "disabled" }]), true);
  assert.equal(nativeSubtitlesOff([{ kind: "captions", mode: "showing" }]), false);
});
test("native subtitle choices filter metadata but preserve original indexes", () => {
  const tracks = nativeChoices([
    { label: "ID3", kind: "metadata", mode: "disabled" },
    { label: "English", kind: "subtitles", mode: "showing" },
    { label: "CC", kind: "captions", mode: "disabled" },
  ], true);
  assert.deepEqual(tracks.map(({ index }) => index), [1, 2]);
  assert.deepEqual(tracks.map(({ active }) => active), [true, false]);
});
