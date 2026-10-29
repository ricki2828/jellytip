import { test } from "node:test";
import assert from "node:assert/strict";
import { editRows, homeFrom, move, parseFollows, parsePrefs, parseTaste, rowLabel } from "../src/lib/prefs.ts";

const J = "0123456789abcdef0123456789abcdef";

test("prefs: defaults fill gaps, junk ignored, specials clamped", () => {
  const p = parsePrefs({ home: { pinned: ["a", "a", 3], specialsPerDay: 99 }, kids: { enabled: true }, playback: { trailerSound: true }, letterboxd: { status: "weird" }, version: 3 });
  assert.deepEqual(p.home, { pinned: ["a"], hidden: [], order: [], specialsPerDay: 6 });
  assert.deepEqual(p.kids, { enabled: true, maxRating: "PG", pinSet: false });
  assert.equal(p.playback.intro, true);
  assert.equal(p.playback.trailerSound, true);
  assert.equal(p.letterboxd.status, "none");
  assert.equal(p.version, 3);
  assert.equal(parsePrefs(null).hideWatched, false);
});

test("home edit: pinned first, hidden last with remembered titles; save shape", () => {
  const rows = [
    { id: "a", title: "A" },
    { id: "b", title: "B" },
    { id: "c", title: "C" },
  ];
  const list = editRows(rows, { pinned: ["c"], hidden: ["actor:x", "z"], order: [], specialsPerDay: 3 }, { z: "Zed" });
  assert.deepEqual(list.map((r) => [r.id, r.title, r.pinned, r.hidden]), [
    ["c", "C", true, false],
    ["a", "A", false, false],
    ["b", "B", false, false],
    ["actor:x", "Actor row", false, true],
    ["z", "Zed", false, true],
  ]);
  const moved = move(list, "b", -1);
  assert.deepEqual(moved.map((r) => r.id), ["c", "b", "a", "actor:x", "z"]);
  assert.equal(move(list, "c", -1), list);
  assert.deepEqual(homeFrom(moved, 2), { pinned: ["c"], hidden: ["actor:x", "z"], order: ["c", "b", "a"], specialsPerDay: 2 });
  assert.equal(rowLabel("plain"), "A row");
});

test("taste: shares sorted and bounded, ids checked", () => {
  const t = parseTaste({ genres: [{ name: "Drama", share: 0.2 }, { name: "Crime", share: 0.5 }, { name: "", share: 1 }, { name: "X", share: -1 }], topRated: [J, "bad"], watchedCount: 41.6, hours: 88.5 })!;
  assert.deepEqual(t.genres.map((g) => g.name), ["Crime", "Drama"]);
  assert.deepEqual(t.topRated, [J]);
  assert.equal(t.watchedCount, 42);
  assert.deepEqual(t.directors, []);
  assert.equal(parseTaste({}), null);
});

test("follows: any of the plausible shapes", () => {
  assert.deepEqual(parseFollows([J, "bad"]), [J]);
  assert.deepEqual(parseFollows({ follows: [{ personId: J }, { personId: J }] }), [J]);
  assert.deepEqual(parseFollows(null), []);
});
