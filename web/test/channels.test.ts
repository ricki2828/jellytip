import { test } from "node:test";
import assert from "node:assert/strict";
import { CHANNELS, MIN_ITEMS, TICKS_PER_SECOND, WEEK_MS, channelViable, cycleSeconds, hashString, itemLabel, lineup, parseHidden, playable, schedule, seedFor, toggleHidden, tunePath, tuneTarget, weekNumber, weekStart, yearsParam, type ChanItem } from "../src/lib/channels.ts";

const MIN = 60 * TICKS_PER_SECOND;
const it = (id: string, min: number | undefined, o: Partial<ChanItem> = {}): ChanItem => ({ Id: id, Name: `Item ${id}`, Type: "Movie", RunTimeTicks: min == null ? undefined : min * MIN, ...o });
// Monday 2026-11-02 00:00 UTC
const MON = Date.UTC(2026, 10, 2);

test("weekStart is Monday 00:00 UTC and stable within the week", () => {
  assert.equal(weekStart(MON), MON);
  assert.equal(weekStart(MON + WEEK_MS - 1), MON);
  assert.equal(weekStart(MON + WEEK_MS), MON + WEEK_MS);
  assert.equal(new Date(weekStart(MON + 3 * 86400000 + 5000)).getUTCDay(), 1);
  assert.equal(weekNumber(MON + WEEK_MS) - weekNumber(MON), 1);
});

test("seed changes with channel and week, not within a week", () => {
  assert.equal(seedFor("a", MON), seedFor("a", MON + 5 * 86400000));
  assert.notEqual(seedFor("a", MON), seedFor("a", MON + WEEK_MS));
  assert.notEqual(seedFor("a", MON), seedFor("b", MON));
  assert.equal(hashString("x"), hashString("x"));
});

test("playable skips missing/zero/tiny runtimes, duplicates, and over-long items", () => {
  const items = [it("a", 90), it("b", undefined), it("c", 0), it("a", 90), it("d", 0.5), it("e", 120), { ...it("f", 10), RunTimeTicks: NaN }];
  assert.deepEqual(playable(items).map((x) => x.Id), ["a", "e"]);
  assert.deepEqual(playable(items, { maxMinutes: 100 }).map((x) => x.Id), ["a"]);
});

test("channel viable only with at least MIN_ITEMS playable items", () => {
  assert.equal(MIN_ITEMS, 3);
  assert.equal(channelViable([it("a", 90), it("b", 90)]), false);
  assert.equal(channelViable([it("a", 90), it("b", 90), it("c", undefined)]), false);
  assert.equal(channelViable([it("a", 90), it("b", 90), it("c", 80)]), true);
});

test("lineup is deterministic, independent of input order, and a permutation", () => {
  const items = Array.from({ length: 20 }, (_, i) => it(`id${String(i).padStart(2, "0")}`, 60 + i));
  const a = lineup(items, 42).map((x) => x.Id);
  const b = lineup([...items].reverse(), 42).map((x) => x.Id);
  assert.deepEqual(a, b);
  assert.deepEqual([...a].sort(), items.map((x) => x.Id));
  assert.notDeepEqual(a, lineup(items, 43).map((x) => x.Id));
  assert.notDeepEqual(a, items.map((x) => x.Id));
});

test("lineup does not mutate its input", () => {
  const items = [it("c", 60), it("a", 60), it("b", 60)];
  lineup(items, 1);
  assert.deepEqual(items.map((x) => x.Id), ["c", "a", "b"]);
});

const L = [it("a", 100), it("b", 50), it("c", 30)]; // 180 min cycle

test("schedule: at week start the first item is on at offset 0", () => {
  const s = schedule(L, MON, 4);
  assert.equal(s.slots[0].item.Id, "a");
  assert.equal(s.offsetS, 0);
  assert.equal(s.progress, 0);
  assert.equal(s.slots[0].startMs, MON);
  assert.equal(s.slots[0].endMs, MON + 100 * 60000);
});

test("schedule: offset within an item, and the next items follow back to back", () => {
  const now = MON + 110 * 60000 + 30000; // 10 min 30 s into "b"
  const s = schedule(L, now, 4);
  assert.equal(s.slots[0].item.Id, "b");
  assert.equal(s.offsetS, 10 * 60 + 30);
  assert.equal(s.slots[0].startMs, MON + 100 * 60000);
  assert.equal(s.slots[0].endMs, MON + 150 * 60000);
  assert.equal(s.slots[1].item.Id, "c");
  assert.equal(s.slots[1].startMs, s.slots[0].endMs);
  assert.ok(Math.abs(s.progress - 630 / 3000) < 1e-9);
});

test("schedule: exact boundary starts the next item", () => {
  const s = schedule(L, MON + 100 * 60000, 2);
  assert.equal(s.slots[0].item.Id, "b");
  assert.equal(s.offsetS, 0);
});

test("schedule: wraps around the lineup, in 'now' and in 'next'", () => {
  const s = schedule(L, MON + 170 * 60000, 4); // in "c", 20 min before the end of the cycle
  assert.deepEqual(s.slots.map((x) => x.item.Id), ["c", "a", "b", "c"]);
  assert.equal(s.slots[1].startMs, MON + 180 * 60000);
  const w = schedule(L, MON + 180 * 60000 * 5 + 10 * 60000, 1); // five full cycles later
  assert.equal(w.slots[0].item.Id, "a");
  assert.equal(w.offsetS, 600);
});

test("schedule: same instant gives the same answer; sub-second times keep slot starts aligned", () => {
  const now = MON + 7_654_321;
  assert.deepEqual(schedule(L, now), schedule(L, now));
  const s = schedule(L, now, 2);
  assert.equal(s.slots[0].startMs + Math.floor(s.offsetS) * 1000 <= now, true);
  assert.equal(now - s.slots[0].startMs, s.offsetS * 1000 + ((now - MON) % 1000));
});

test("schedule: week boundary restarts the new week's lineup at its first item", () => {
  const s = schedule(L, MON + WEEK_MS + 5000, 1);
  assert.equal(s.slots[0].item.Id, "a");
  assert.equal(s.offsetS, 5);
});

test("schedule: empty or runtime-less lists give nothing", () => {
  assert.deepEqual(schedule([], MON), { slots: [], offsetS: 0, progress: 0 });
  assert.equal(schedule([it("a", undefined)], MON).slots.length, 0);
});

test("schedule: a single-item list loops on itself", () => {
  const s = schedule([it("a", 10)], MON + 25 * 60000, 3);
  assert.deepEqual(s.slots.map((x) => x.item.Id), ["a", "a", "a"]);
  assert.equal(s.offsetS, 300);
});

test("cycleSeconds sums runtimes", () => {
  assert.equal(cycleSeconds(L), 180 * 60);
});

test("tuneTarget: current item at its offset, chained to the next", () => {
  const s = schedule(L, MON + 110 * 60000 + 30000, 4);
  const t = tuneTarget(s.slots, s.offsetS)!;
  assert.deepEqual(t, { id: "b", startS: 630, thenId: "c" });
  assert.equal(tunePath(t), "/play/b?start=630&then=c");
});

test("tuneTarget: nearly over → next item from the start", () => {
  const s = schedule(L, MON + 100 * 60000 - 5000, 4); // 5 s left of "a"
  assert.deepEqual(tuneTarget(s.slots, s.offsetS), { id: "b", startS: 0, thenId: "c" });
  assert.equal(tuneTarget([], 0), null);
});

test("itemLabel: films show the year, episodes show series and code", () => {
  assert.deepEqual(itemLabel(it("a", 90, { ProductionYear: 1994 })), { title: "Item a", sub: "1994" });
  assert.deepEqual(itemLabel({ Id: "e", Name: "Pilot", Type: "Episode", SeriesName: "Show", ParentIndexNumber: 1, IndexNumber: 2 }), { title: "Show", sub: "S1E2 · Pilot" });
});

test("hidden list: tolerant parse and toggle", () => {
  assert.deepEqual(parseHidden(null), []);
  assert.deepEqual(parseHidden("nope"), []);
  assert.deepEqual(parseHidden('{"a":1}'), []);
  assert.deepEqual(parseHidden('["a",3,"b"]'), ["a", "b"]);
  assert.deepEqual(toggleHidden(["a"], "b"), ["a", "b"]);
  assert.deepEqual(toggleHidden(["a", "b"], "a"), ["b"]);
});

test("yearsParam expands a range and refuses nonsense", () => {
  assert.equal(yearsParam({ yearFrom: 1990, yearTo: 1992 }), "1990,1991,1992");
  assert.equal(yearsParam({}), undefined);
  assert.equal(yearsParam({ yearFrom: 2000, yearTo: 1990 }), undefined);
});

test("built-in channel ids are unique", () => {
  assert.equal(new Set(CHANNELS.map((c) => c.id)).size, CHANNELS.length);
  assert.ok(CHANNELS.length >= 5 && CHANNELS.length <= 6);
});
