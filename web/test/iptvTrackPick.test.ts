import test from "node:test";
import assert from "node:assert/strict";
import { makePick, matchPick, type TrackChoice } from "../src/lib/iptvTracks.ts";

const c = (index: number, lang?: string, name?: string): TrackChoice => ({ index, label: name ?? `T${index}`, active: false, lang, name });

test("matches by lang+name after indices shuffle", () => {
  const before = [c(0, "en", "English"), c(1, "de", "Deutsch")];
  const pick = makePick(before, 1);
  assert.equal(matchPick([c(0, "de", "Deutsch"), c(1, "en", "English")], pick), 0);
});
test("name match when lang changes or is missing", () => {
  assert.equal(matchPick([c(0, undefined, "Commentary"), c(1, undefined, "Main")], { index: 5, name: "Main" }), 1);
});
test("unique lang match when name differs", () => {
  assert.equal(matchPick([c(0, "en", "Audio 1"), c(1, "fr", "Audio 2")], { index: 0, lang: "fr", name: "Francais" }), 1);
});
test("ambiguous lang does not guess", () => {
  assert.equal(matchPick([c(0, "en", "A"), c(1, "en", "B")], { index: 0, lang: "en", name: "Z" }), null);
});
test("und lang is not matched", () => {
  assert.equal(matchPick([c(0, "und", "A"), c(1, "und", "B")], { index: 0, lang: "und" }), 0);
  assert.equal(matchPick([c(0, "und", "A")], { index: 3, lang: "und" }), null);
});
test("index fallback only when pick has no name or lang", () => {
  assert.equal(matchPick([c(0), c(1)], { index: 1 }), 1);
  assert.equal(matchPick([c(0)], { index: 1 }), null);
  assert.equal(matchPick([c(0, "en", "X")], { index: 0, name: "Gone" }), null);
});
test("same name, lang disambiguates", () => {
  assert.equal(matchPick([c(0, "en", "Sub"), c(1, "de", "Sub")], { index: 0, lang: "de", name: "Sub" }), 1);
});
