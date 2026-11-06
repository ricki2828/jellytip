import { test } from "node:test";
import assert from "node:assert/strict";
import { MEMORY_MAX, getAlwaysSubs, loadStore, memoryKey, parseStore, recordChoice, resolveMemory, saveStore, setAlwaysSubs } from "../src/lib/trackMemory.ts";
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
  const r = resolveMemory(next, st["s:S"]);
  assert.equal(r.audio, 0);
  assert.equal(r.subtitle, 2); // fre == fra
});

test("subtitles off is remembered and beats prefs", () => {
  const st = recordChoice({}, "s:S", streams, { subtitle: -1 }, 1);
  assert.equal(resolveMemory(next, st["s:S"]).subtitle, -1);
});

test("precedence: memory wins over prefs; missing language falls back to prefs", () => {
  const prefs = { audioLangs: ["en"], subtitleLangs: ["en"] };
  const st = recordChoice({}, "s:S", streams, { audio: 2 }, 1);
  const mem = resolveMemory(next, st["s:S"]);
  assert.equal(mem.audio ?? pickAudio(next, prefs.audioLangs, null), 0);
  // remembered language absent from this file: prefs decide
  const only = [{ Index: 1, Type: "Audio", Language: "eng" }];
  const m2 = resolveMemory(only, st["s:S"]);
  assert.equal(m2.audio, undefined);
  assert.equal(m2.audio ?? pickAudio(only, prefs.audioLangs, null), 1);
  assert.equal(pickSubtitle(next, 0, prefs, -1), 3);
});

test("unknown-language track choices are not remembered", () => {
  const st = recordChoice({}, "s:S", streams, { audio: 3 }, 1);
  assert.deepEqual(st, {});
});

test("an unchanged choice returns the same store", () => {
  const a = recordChoice({}, "k", streams, { audio: 2 }, 1);
  assert.equal(recordChoice(a, "k", streams, { audio: 2 }, 2), a);
});

test("later choice for a title replaces the earlier one, other half kept", () => {
  let st = recordChoice({}, "k", streams, { audio: 2, subtitle: 4 }, 1);
  st = recordChoice(st, "k", streams, { audio: 1 }, 2);
  assert.deepEqual(st.k, { a: "en", s: "en", t: 2 });
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
