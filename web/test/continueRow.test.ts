import { test } from "node:test";
import assert from "node:assert/strict";
import { actionsFor, buildContinue, leftText, nearlyDone, optimisticHide, readDismissed, rowLabel, writeDismissed } from "../src/lib/continueRow.ts";
import type { ContinueEntry } from "../src/lib/continue.ts";
import type { Item } from "../src/api/jellyfin.ts";

const T = 10_000_000;
const min = (m: number) => m * 60 * T;
const ep = (id: string, series: string, s: number, e: number, o: { pos?: number; runMin?: number; played?: string } = {}): Item =>
  ({ Id: id, Name: `E${e}`, Type: "Episode", SeriesId: series, SeriesName: series, ParentIndexNumber: s, IndexNumber: e, RunTimeTicks: min(o.runMin ?? 50), UserData: { PlaybackPositionTicks: min(o.pos ?? 0), LastPlayedDate: o.played } }) as Item;
const movie = (id: string, o: { pos?: number; runMin?: number; played?: string } = {}): Item =>
  ({ Id: id, Name: id, Type: "Movie", RunTimeTicks: min(o.runMin ?? 100), UserData: { PlaybackPositionTicks: min(o.pos ?? 0), LastPlayedDate: o.played } }) as Item;
const iptv = (o: Partial<ContinueEntry> & { id: string }): ContinueEntry => ({ kind: "movie", seriesId: null, title: o.id, seriesTitle: null, season: null, episode: null, pos: 0, dur: 0, at: 0, poster: null, backdrop: null, tech: [], ...o });

test("labels: episode left, film left, next, unknown length", () => {
  assert.equal(rowLabel({ next: false, episode: "S2 E5", pos: 27 * 60, dur: 50 * 60 }), "S2 E5 · 23 min left");
  assert.equal(rowLabel({ next: false, episode: null, pos: 0, dur: 65 * 60 }), "1h 5m left");
  assert.equal(rowLabel({ next: true, episode: "S2 E6", pos: 0, dur: 0 }), "Next: S2 E6");
  assert.equal(rowLabel({ next: false, episode: null, pos: 5, dur: 0 }), "In progress");
  assert.equal(leftText(10), "1 min left");
  assert.equal(leftText(3600), "1h left");
});

test("near-finished: 95 % or under 2 min left; unknown length is never finished", () => {
  assert.equal(nearlyDone(9500, 10000), true);
  assert.equal(nearlyDone(9400, 10000), false);
  assert.equal(nearlyDone(3500, 3600), true); // 100 s left
  assert.equal(nearlyDone(3400, 3600), false);
  assert.equal(nearlyDone(500, 0), false);
});

test("resume beats next up: a series shows once, at the in-progress episode", () => {
  const rows = buildContinue([ep("e5", "S", 2, 5, { pos: 27, played: "2026-11-05T10:00:00Z" })], [ep("e6", "S", 2, 6), ep("o1", "Other", 1, 1)], [], []);
  assert.deepEqual(rows.map((r) => [r.key, r.label]), [["lib:e5", "S2 E5 · 23 min left"], ["lib:o1", "Next: S1 E1"]]);
});

test("two resumes of one series: the most recently played wins", () => {
  const rows = buildContinue([ep("a", "S", 1, 1, { pos: 10, played: "2026-11-01T00:00:00Z" }), ep("b", "S", 1, 2, { pos: 10, played: "2026-11-04T00:00:00Z" })], [], [], []);
  assert.deepEqual(rows.map((r) => r.key), ["lib:b"]);
});

test("library and IPTV in progress sort together by last played; next up follows", () => {
  const rows = buildContinue(
    [movie("old", { pos: 10, played: "2026-11-01T00:00:00Z" }), movie("new", { pos: 10, played: "2026-11-05T00:00:00Z" })],
    [ep("n1", "X", 1, 2)],
    [iptv({ id: "mid", pos: 600, dur: 6000, at: Date.parse("2026-11-03T00:00:00Z") / 1000 })],
    [iptv({ id: "x:e:1", kind: "episode", seriesId: "x:s:1", season: 3, episode: 4 })],
  );
  assert.deepEqual(rows.map((r) => r.key), ["lib:new", "iptv:movie:mid", "lib:old", "lib:n1", "iptv:episode:x:e:1"]);
  assert.equal(rows[4].label, "Next: S3 E4");
});

test("IPTV next up for a series already in progress is dropped; dupes in next collapse", () => {
  const rows = buildContinue(
    [],
    [ep("n1", "X", 1, 2), ep("n1b", "X", 1, 3)],
    [iptv({ id: "s:e:1", kind: "episode", seriesId: "s:s:1", season: 1, episode: 1, pos: 100, dur: 2000, at: 5 })],
    [iptv({ id: "s:e:2", kind: "episode", seriesId: "s:s:1", season: 1, episode: 2 })],
  );
  assert.deepEqual(rows.map((r) => r.key), ["iptv:episode:s:e:1", "lib:n1"]);
});

test("finished titles are left out of the row (and do not suppress their series' next up)", () => {
  const rows = buildContinue([movie("done", { pos: 97 }), ep("e1", "S", 1, 1, { pos: 49 })], [ep("e2", "S", 1, 2)], [iptv({ id: "tail", pos: 5950, dur: 6000, at: 1 })], []);
  assert.deepEqual(rows.map((r) => r.key), ["lib:e2"]);
});

test("undated resume items keep Jellyfin's newest-first order", () => {
  const rows = buildContinue([movie("a", { pos: 10 }), movie("b", { pos: 10 }), movie("c", { pos: 10 })], [], [], []);
  assert.deepEqual(rows.map((r) => r.key), ["lib:a", "lib:b", "lib:c"]);
});

test("dashed ids key the same as undashed", () => {
  const rows = buildContinue([movie("ab-cd", { pos: 10 })], [], [], []);
  assert.equal(rows[0].key, "lib:abcd");
});

test("hidden keys and removals hide a tile; playing it again after the removal brings it back", () => {
  const items = [movie("a", { pos: 10, played: "2026-11-05T00:00:00Z" })];
  assert.equal(buildContinue(items, [], [], [], { hidden: new Set(["lib:a"]) }).length, 0);
  const removedAt = Date.parse("2026-11-06T00:00:00Z");
  assert.equal(buildContinue(items, [], [], [], { dismissed: { "lib:a": removedAt } }).length, 0);
  assert.equal(buildContinue([movie("a", { pos: 10, played: "2026-11-07T00:00:00Z" })], [], [], [], { dismissed: { "lib:a": removedAt } }).length, 1);
  // next-up episodes carry no play time: removed stays removed until the next-up episode changes
  assert.equal(buildContinue([], [ep("n", "S", 1, 2)], [], [], { dismissed: { "lib:n": removedAt } }).length, 0);
  assert.equal(buildContinue([], [ep("n2", "S", 1, 3)], [], [], { dismissed: { "lib:n": removedAt } }).length, 1);
});

test("actions: IPTV has no Remove; watched needs a length and an in-progress tile", () => {
  const [lib] = buildContinue([movie("a", { pos: 10 })], [], [], []);
  assert.deepEqual(actionsFor(lib), { remove: true, watched: true, restart: true });
  const [libNext] = buildContinue([], [ep("n", "S", 1, 2)], [], []);
  assert.deepEqual(actionsFor(libNext), { remove: true, watched: true, restart: false });
  const [tv] = buildContinue([], [], [iptv({ id: "m", pos: 10, dur: 6000, at: 1 })], []);
  assert.deepEqual(actionsFor(tv), { remove: false, watched: true, restart: true });
  const [noLen] = buildContinue([], [], [iptv({ id: "m", pos: 10, dur: 0, at: 1 })], []);
  assert.equal(actionsFor(noLen).watched, false);
  const [tvNext] = buildContinue([], [], [], [iptv({ id: "n:e:2", kind: "episode", seriesId: "n:s:1" })]);
  assert.deepEqual(actionsFor(tvNext), { remove: false, watched: false, restart: false });
});

function harness() {
  const hidden = new Set<string>();
  const notes: string[] = [];
  return { hidden, notes, io: { hide: (k: string) => void hidden.add(k), unhide: (k: string) => void hidden.delete(k), notify: (m: string) => void notes.push(m) } };
}

test("optimistic: hidden at once, stays hidden on success, notes success", async () => {
  const h = harness();
  let seenDuring: boolean | null = null;
  const ok = await optimisticHide(h.io, "k", async () => void (seenDuring = h.hidden.has("k")), "bad", "done");
  assert.equal(ok, true);
  assert.equal(seenDuring, true);
  assert.deepEqual([...h.hidden], ["k"]);
  assert.deepEqual(h.notes, ["done"]);
});

test("optimistic: failure rolls the tile back and toasts", async () => {
  const h = harness();
  const ok = await optimisticHide(h.io, "k", async () => Promise.reject(new Error("500")), "Couldn't remove it", "done");
  assert.equal(ok, false);
  assert.equal(h.hidden.has("k"), false);
  assert.deepEqual(h.notes, ["Couldn't remove it"]);
});

test("optimistic: a failure rolls back only its own tile while another action succeeds", async () => {
  const h = harness();
  const [a, b] = await Promise.all([
    optimisticHide(h.io, "a", () => new Promise((_, rej) => setTimeout(() => rej(new Error("x")), 5)), "fail a"),
    optimisticHide(h.io, "b", () => new Promise((res) => setTimeout(res, 20)), "fail b"),
  ]);
  assert.deepEqual([a, b], [false, true]);
  assert.deepEqual([...h.hidden], ["b"]);
});

test("dismissed list is per user, bounded, and tolerates bad storage", () => {
  const mem = new Map<string, string>();
  const store = { getItem: (k: string) => mem.get(k) ?? null, setItem: (k: string, v: string) => void mem.set(k, v) };
  writeDismissed(store, "u1", "lib:a", true, 100);
  assert.deepEqual(readDismissed(store, "u1"), { "lib:a": 100 });
  assert.deepEqual(readDismissed(store, "u2"), {}); // another user sees nothing
  assert.deepEqual(readDismissed(store, ""), {});
  writeDismissed(store, "u1", "lib:a", false);
  assert.deepEqual(readDismissed(store, "u1"), {});
  for (let i = 0; i < 230; i++) writeDismissed(store, "u1", `lib:${i}`, true, i);
  assert.equal(Object.keys(readDismissed(store, "u1")).length, 200);
  assert.ok("lib:229" in readDismissed(store, "u1"));
  mem.set("jellytip.continue.dismissed.u3", "{bad");
  assert.deepEqual(readDismissed(store, "u3"), {});
});
