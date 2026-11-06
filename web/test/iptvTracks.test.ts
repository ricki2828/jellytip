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

import { pickIptvTracks, type TrackChoice } from "../src/lib/iptvTracks.ts";
const C = (index: number, lang: string | undefined, name = "", active = false): TrackChoice => ({ index, label: name || `Track ${index + 1}`, active, lang, name });

test("iptv: picks the first preferred audio language by declared tag", () => {
  const r = pickIptvTracks([C(0, "spa", "", true), C(1, "eng")], [], { audioLangs: ["en"], subtitleLangs: [] });
  assert.deepEqual(r, { audio: 1, subtitle: null });
});

test("iptv: never guesses language from the label; unknown stays unknown", () => {
  const r = pickIptvTracks([C(0, undefined, "English", true), C(1, "und", "English")], [C(0, undefined, "English")], { audioLangs: ["en"], subtitleLangs: ["en"] });
  assert.deepEqual(r, { audio: null, subtitle: null });
});

test("iptv: foreign audio turns on a preferred-language subtitle; matching audio does not", () => {
  const subs = [C(0, "eng", "English")];
  assert.equal(pickIptvTracks([C(0, "jpn", "", true)], subs, { audioLangs: ["en"], subtitleLangs: ["en"] }).subtitle, 0);
  assert.equal(pickIptvTracks([C(0, "eng", "", true)], subs, { audioLangs: ["en"], subtitleLangs: ["en"] }).subtitle, null);
  assert.equal(pickIptvTracks([C(0, "eng", "", true)], subs, { audioLangs: ["en"], subtitleLangs: ["en"], alwaysSubs: true }).subtitle, 0);
});

test("iptv: commentary audio avoided; forced subtitle on when audio matches", () => {
  const r = pickIptvTracks([C(0, "eng", "Commentary", true), C(1, "eng", "Main")], [C(0, "eng", "English forced")], { audioLangs: ["en"], subtitleLangs: ["en"] });
  assert.deepEqual(r, { audio: 1, subtitle: 0 });
});
