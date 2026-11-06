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

import { canonLang, isCommentary } from "../src/lib/tracks.ts";

const A = (Index: number, Language: string | undefined, extra: Record<string, unknown> = {}) => ({ Index, Type: "Audio", Language, ...extra });
const T = (Index: number, Language: string | undefined, extra: Record<string, unknown> = {}) => ({ Index, Type: "Subtitle", Language, IsTextSubtitleStream: true, ...extra });

test("canonical language: ISO 639-2 variants meet, unknown stays unknown", () => {
  assert.equal(canonLang("fre"), "fr");
  assert.equal(canonLang("fra"), "fr");
  assert.equal(canonLang("en-US"), "en");
  assert.equal(canonLang("und"), "");
  assert.equal(canonLang(undefined), "");
  assert.equal(canonLang("tgl"), "tgl");
});

test("commentary is judged from the stream's own title text", () => {
  assert.ok(isCommentary({ Index: 1, Type: "Audio", Title: "Director's Commentary" }));
  assert.ok(isCommentary({ Index: 1, Type: "Audio", DisplayTitle: "English - Audio Description - AC3" }));
  assert.ok(!isCommentary({ Index: 1, Type: "Audio", Title: "Surround 5.1" }));
});

test("audio: skips commentary even when it is the file default; same-language ties go to IsDefault then first", () => {
  const f = [A(1, "eng", { Title: "Commentary by the director", IsDefault: true }), A(2, "eng"), A(3, "eng", { IsDefault: true })];
  assert.equal(pickAudio(f, ["en"], 1), 3);
  assert.equal(pickAudio([A(1, "eng", { Title: "Commentary" }), A(2, "eng")], ["en"], 1), 2);
  assert.equal(pickAudio([A(1, "eng"), A(2, "eng")], ["en"], 2), 1); // no default flag: first
});

test("audio: only-commentary language is not chosen; falls to the next pref", () => {
  const f = [A(1, "eng", { Title: "Commentary" }), A(2, "fre")];
  assert.equal(pickAudio(f, ["en", "fr"], 1), 2);
});

test("audio: a commentary file default is swapped for a plain track when no pref matches", () => {
  const f = [A(1, "ger", { Title: "Commentary", IsDefault: true }), A(2, "jpn")];
  assert.equal(pickAudio(f, ["en"], 1), 2);
  assert.equal(pickAudio([A(1, "jpn", { IsDefault: true })], ["en"], 1), 1);
});

test("audio: unknown language never matches a pref", () => {
  const f = [A(1, undefined, { IsDefault: true }), A(2, "und"), A(3, "eng")];
  assert.equal(pickAudio(f, ["en"], 1), 3);
  assert.equal(pickAudio(f.slice(0, 2), ["en"], 1), 1);
});

test("subtitles: forced track in the preferred language is on when audio matches", () => {
  const f = [A(1, "eng", { IsDefault: true }), T(2, "eng"), T(3, "eng", { IsForced: true })];
  const prefs = { audioLangs: ["en"], subtitleLangs: ["en"] };
  assert.equal(pickSubtitle(f, 1, prefs, -1), 3);
});

test("subtitles: forced track in another language is not turned on", () => {
  const f = [A(1, "eng"), T(2, "fre", { IsForced: true })];
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"] }, -1), -1);
});

test("subtitles: foreign audio turns the full subtitle on, not the forced one; commentary subs skipped", () => {
  const f = [A(1, "jpn"), T(2, "eng", { IsForced: true }), T(3, "eng", { Title: "Commentary" }), T(4, "eng"), T(5, "eng", { IsDefault: true })];
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"] }, -1), 5);
});

test("subtitles: unknown audio language is not assumed foreign", () => {
  const f = [A(1, undefined), T(2, "eng")];
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"] }, -1), -1);
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"], alwaysSubs: true }, -1), 2);
});

test("subtitles: always-show turns full subtitles on even with matching audio", () => {
  const f = [A(1, "eng"), T(2, "eng", { IsForced: true }), T(3, "eng")];
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"], alwaysSubs: true }, -1), 3);
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["en"], alwaysSubs: false }, -1), 2);
});

test("subtitles: preference order across languages; no match keeps the fallback", () => {
  const f = [A(1, "jpn"), T(2, "fre"), T(3, "spa")];
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["es", "fr"] }, -1), 3);
  assert.equal(pickSubtitle(f, 1, { audioLangs: ["en"], subtitleLangs: ["de"] }, 9), 9);
});

test("subtitles: old prefs shape (no alwaysSubs) behaves as before", () => {
  assert.equal(pickSubtitle(S, 1, { audioLangs: ["en"], subtitleLangs: ["en"] }, -1), 5);
});
