import { test } from "node:test";
import assert from "node:assert/strict";
import { MEMORY_MAX, chooseStart, getAlwaysSubs, loadStore, memoryKey, parseStore, recordChoice, resolveAudio, resolveSubtitle, saveStore, setAlwaysSubs } from "../src/lib/trackMemory.ts";
import { pickAudio, pickSubtitle } from "../src/lib/tracks.ts";

const streams = [
  { Index: 1, Type: "Audio", Language: "eng", IsDefault: true },
  { Index: 2, Type: "Audio", Language: "jpn" },
  { Index: 3, Type: "Audio", Language: undefined },
  { Index: 4, Type: "Subtitle", Language: "eng", IsTextSubtitleStream: true },
  { Index: 5, Type: "Subtitle", Language: "fre", IsTextSubtitleStream: true },
  { Index: 6, Type: "Subtitle", Language: "eng", IsForced: true, IsTextSubtitleStream: true },
];
const next = [
  { Index: 0, Type: "Audio", Language: "jpn" },
  { Index: 1, Type: "Audio", Language: "eng", IsDefault: true },
  { Index: 2, Type: "Subtitle", Language: "fra", IsTextSubtitleStream: true },
  { Index: 3, Type: "Subtitle", Language: "eng", IsTextSubtitleStream: true },
];

test("key: episodes share the series, movies use the item", () => {
  assert.equal(memoryKey({ Id: "e1", Type: "Episode", SeriesId: "S" }), "s:S");
  assert.equal(memoryKey({ Id: "m1", Type: "Movie" }), "m:m1");
});

test("manual choice carries to the next episode by language, not index", () => {
  let st = recordChoice({}, "s:S", streams, { audio: 2, subtitle: 5 }, 1);
  assert.equal(resolveAudio(next, st["s:S"]), 0);
  assert.equal(resolveSubtitle(next, st["s:S"], 0), 2); // fre == fra, under the same (jpn) audio
});

test("subtitles off is remembered and beats prefs", () => {
  const st = recordChoice({}, "s:S", streams, { subtitle: -1 }, 1, 1);
  assert.equal(resolveSubtitle(next, st["s:S"], 1), -1);
});

test("precedence: memory wins over prefs; missing language falls back to prefs", () => {
  const prefs = { audioLangs: ["en"], subtitleLangs: ["en"] };
  const st = recordChoice({}, "s:S", streams, { audio: 2 }, 1);
  assert.equal(resolveAudio(next, st["s:S"]) ?? pickAudio(next, prefs.audioLangs, null), 0);
  // remembered language absent from this file: prefs decide
  const only = [{ Index: 1, Type: "Audio", Language: "eng" }];
  assert.equal(resolveAudio(only, st["s:S"]), undefined);
  assert.equal(resolveAudio(only, st["s:S"]) ?? pickAudio(only, prefs.audioLangs, null), 1);
  assert.equal(pickSubtitle(next, 0, prefs, -1), 3);
});

test("unknown-language audio is not remembered; an unknown-language subtitle is recorded as unknown and never applied", () => {
  assert.deepEqual(recordChoice({}, "s:S", streams, { audio: 3 }, 1), {});
  const st = recordChoice({}, "s:S", [...streams, { Index: 9, Type: "Subtitle", Language: "und", IsTextSubtitleStream: true }], { subtitle: 9 }, 1, 1);
  assert.equal(st["s:S"].s, "?");
  assert.equal(resolveSubtitle(next, st["s:S"], 1), undefined);
});

test("an unchanged choice returns the same store", () => {
  const a = recordChoice({}, "k", streams, { audio: 2 }, 1);
  assert.equal(recordChoice(a, "k", streams, { audio: 2 }, 2), a);
});

test("later choice for a title replaces the earlier one, other half kept", () => {
  let st = recordChoice({}, "k", streams, { audio: 2, subtitle: 4 }, 1);
  assert.deepEqual(st.k, { a: "ja", s: "en", sa: "ja", t: 1 });
  st = recordChoice(st, "k", streams, { audio: 1, subtitle: 4 }, 2);
  assert.deepEqual(st.k, { a: "en", s: "en", sa: "en", t: 2 });
});

test("LRU: bounded at 200, least recently used goes first, touching refreshes", () => {
  let st = {};
  for (let i = 0; i < MEMORY_MAX; i++) st = recordChoice(st, `k${i}`, streams, { audio: 2 }, i + 1);
  assert.equal(Object.keys(st).length, MEMORY_MAX);
  st = recordChoice(st, "k0", streams, { audio: 1 }, 1000); // refresh the oldest
  st = recordChoice(st, "new", streams, { audio: 2 }, 1001);
  assert.equal(Object.keys(st).length, MEMORY_MAX);
  assert.ok("k0" in st && "new" in st && !("k1" in st));
});

test("parseStore tolerates junk; storage is per user", () => {
  assert.deepEqual(parseStore(null), {});
  assert.deepEqual(parseStore([1]), {});
  assert.deepEqual(parseStore({ a: 5, b: { t: "x" }, c: { a: "en", t: 3 } }), { c: { a: "en", t: 3 } });
  const m = new Map<string, string>();
  const st = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) };
  saveStore("u1", { k: { a: "en", t: 1 } }, st);
  assert.deepEqual(loadStore("u1", st), { k: { a: "en", t: 1 } });
  assert.deepEqual(loadStore("u2", st), {});
  m.set("jellytip.trackMemory.u3", "not json");
  assert.deepEqual(loadStore("u3", st), {});
  assert.equal(getAlwaysSubs("u1", st), false);
  setAlwaysSubs("u1", true, st);
  assert.equal(getAlwaysSubs("u1", st), true);
  assert.equal(getAlwaysSubs("u2", st), false);
  setAlwaysSubs("u1", false, st);
  assert.equal(getAlwaysSubs("u1", st), false);
});

const ja = [
  { Index: 0, Type: "Audio", Language: "jpn", IsDefault: true },
  { Index: 1, Type: "Audio", Language: "eng" },
  { Index: 2, Type: "Subtitle", Language: "eng", IsTextSubtitleStream: true },
];
const prefsEn = { audioLangs: ["ja"], subtitleLangs: ["en"] };

test("P1: Japanese show with English subs remembered; then the English dub: the subtitle is not carried over", () => {
  // watching Japanese audio, picks English subs
  let st = recordChoice({}, "s:S", ja, { subtitle: 2 }, 1, 0);
  assert.equal(st["s:S"].sa, "ja");
  // next episode, Japanese audio again: subs come back
  assert.deepEqual(chooseStart(ja, st["s:S"], prefsEn, { defaultAudio: 0, defaultSub: -1 }), { audio: 0, subtitle: 2 });
  // user switches to the English dub (no subtitle picked): the old choice is forgotten
  st = recordChoice(st, "s:S", ja, { audio: 1 }, 2);
  assert.equal(st["s:S"].s, undefined);
  assert.deepEqual(chooseStart(ja, st["s:S"], { audioLangs: ["en"], subtitleLangs: ["en"] }, { defaultAudio: 0, defaultSub: -1 }), { audio: 1, subtitle: -1 });
});

test("P1: a subtitle remembered under Japanese audio does not apply when the dub is chosen (legacy-safe via sa)", () => {
  const entry = { a: "en", s: "en", sa: "ja", t: 1 };
  assert.equal(resolveSubtitle(ja, entry, 1), undefined);
  assert.equal(resolveSubtitle(ja, entry, 0), 2);
  assert.deepEqual(chooseStart(ja, entry, null, { defaultAudio: 0, defaultSub: -1 }), { audio: 1, subtitle: -1 });
});

test("explicit off is remembered under its audio and beats always-on prefs", () => {
  const st = recordChoice({}, "k", ja, { subtitle: -1 }, 1, 0);
  assert.deepEqual(chooseStart(ja, st.k, { audioLangs: ["ja"], subtitleLangs: ["en"], alwaysSubs: true }, { defaultAudio: 0, defaultSub: -1 }).subtitle, -1);
});

test("chooseStart without memory or prefs keeps file defaults; forceAudio wins", () => {
  assert.deepEqual(chooseStart(ja, undefined, null, { defaultAudio: 0, defaultSub: 7 }), { audio: null, subtitle: 7 });
  assert.equal(chooseStart(ja, { a: "en", t: 1 }, null, { defaultAudio: 0, defaultSub: -1, forceAudio: 0 }).audio, 0);
});
