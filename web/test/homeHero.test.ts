// Home hero: his live game is one slide of the normal hero (TV HeroCarouselTest port, Ricki 22-Nov).
import { test } from "node:test";
import assert from "node:assert/strict";
import { heroSlots, keepSlide, nextSlide } from "../src/lib/homeHero.ts";

const mix = ["a", "b", "c", "d"];
const keys = (s: { key: string }[]) => s.map((x) => x.key);

test("order: one game, then the journey, then the mix", () => {
  assert.deepEqual(keys(heroSlots("f1", "j1", mix)), ["m:f1", "j:j1", "t:a", "t:b", "t:c", "t:d"]);
  assert.deepEqual(keys(heroSlots(null, "j1", mix)), ["j:j1", "t:a", "t:b", "t:c", "t:d"]);
  assert.deepEqual(keys(heroSlots("f1", null, [])), ["m:f1"]);
  assert.deepEqual(heroSlots("f1", null, ["a"])[1], { key: "t:a", kind: "mix", index: 0 });
  // a duplicate mix key is shown once
  assert.deepEqual(keys(heroSlots(null, null, ["a", "a", "b"])), ["t:a", "t:b"]);
});

test("before the hero turns, the first slide shows (game arriving late still leads)", () => {
  const before = heroSlots(null, null, mix);
  assert.equal(keepSlide(before, "t:a", 0, false), 0);
  const after = heroSlots("f1", null, mix);
  assert.equal(after[keepSlide(after, "t:a", 0, false)]!.key, "m:f1");
});

test("game arrives after the hero turned: the slide on screen stays", () => {
  const after = heroSlots("f1", null, mix);
  // "c" was showing at index 2
  assert.equal(after[keepSlide(after, "t:c", 2, true)]!.key, "t:c");
});

test("game and journey both arrive after rotation: the slide on screen stays", () => {
  const after = heroSlots("f1", "j1", mix);
  assert.equal(after[keepSlide(after, "t:b", 1, true)]!.key, "t:b");
});

test("game ends while it is showing: the slide now first takes over", () => {
  const after = heroSlots(null, "j1", mix);
  assert.equal(after[keepSlide(after, "m:f1", 0, true)]!.key, "j:j1");
  const noJourney = heroSlots(null, null, mix);
  assert.equal(noJourney[keepSlide(noJourney, "m:f1", 0, true)]!.key, "t:a");
});

test("game ends while a mix slide shows: that slide stays", () => {
  const after = heroSlots(null, null, mix);
  assert.equal(after[keepSlide(after, "t:d", 4, true)]!.key, "t:d");
});

test("the last slide goes away: clamped, never out of range", () => {
  const after = heroSlots(null, null, ["a", "b"]);
  assert.equal(keepSlide(after, "t:z", 5, true), 1);
  assert.equal(keepSlide([], "t:z", 5, true), 0);
});

test("nextSlide wraps", () => {
  assert.equal(nextSlide(0, 3), 1);
  assert.equal(nextSlide(2, 3), 0);
  assert.equal(nextSlide(4, 0), 0);
});
