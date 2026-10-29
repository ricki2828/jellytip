import { test } from "node:test";
import assert from "node:assert/strict";
import { langIs, pickAudio, pickSubtitle } from "../src/lib/tracks.ts";

const S = [
  { Index: 0, Type: "Video" },
  { Index: 1, Type: "Audio", Language: "fre", IsDefault: true },
  { Index: 2, Type: "Audio", Language: "eng" },
  { Index: 3, Type: "Audio", Language: "eng", IsDefault: true },
  { Index: 4, Type: "Subtitle", Language: "eng", IsForced: true, IsTextSubtitleStream: true },
  { Index: 5, Type: "Subtitle", Language: "eng", IsTextSubtitleStream: true },
  { Index: 6, Type: "Subtitle", Language: "fre", IsTextSubtitleStream: false },
];

test("language tags", () => {
  assert.ok(langIs("fra", "fr"));
  assert.ok(langIs("en-GB", "en"));
  assert.ok(!langIs(undefined, "en"));
  assert.ok(!langIs("ger", "fr"));
});

test("audio: first pref language present, its default track first; else the file default", () => {
  assert.equal(pickAudio(S, ["en", "fr"], 1), 3);
  assert.equal(pickAudio(S, ["ja", "fr"], 2), 1);
  assert.equal(pickAudio(S, ["ja"], 1), 1);
  assert.equal(pickAudio(S, [], null), null);
});

test("subtitles: only when the audio isn't a preferred language; full text subs before forced", () => {
  const prefs = { audioLangs: ["en"], subtitleLangs: ["en"] };
  assert.equal(pickSubtitle(S, 3, prefs, 4), 4); // English audio: keep the file default (forced)
  assert.equal(pickSubtitle(S, 1, prefs, -1), 5); // French audio: English subtitles on
  assert.equal(pickSubtitle(S, 1, { audioLangs: ["en"], subtitleLangs: ["fr"] }, -1), -1); // only an image sub in French
  assert.equal(pickSubtitle(S, 1, { audioLangs: ["en"], subtitleLangs: [] }, -1), -1);
});
