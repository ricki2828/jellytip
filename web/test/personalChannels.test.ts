import { test } from "node:test";
import assert from "node:assert/strict";
import { CHANNELS, TICKS_PER_SECOND, affinityWeight, channelById, lineup, sample, schedule, poolOf, setPersonalDefs, EPOCH_MS, type ChanItem } from "../src/lib/channels.ts";
import { personalDefs, personalItems, FOR_YOU_ID, GENRE_ID, SHOWS_ID } from "../src/lib/personalChannels.ts";
import { orderByAffinity, visibleChannels } from "../src/lib/channelsRow.ts";
import { parseProfile } from "../src/lib/tasteProfile.ts";

const MIN = 60 * TICKS_PER_SECOND;
const film = (n: number, aff: number | undefined, G = ["Thriller"]): ChanItem => ({ Id: `f${String(n).padStart(3, "0")}`, Name: `F${n}`, Type: "Movie", RunTimeTicks: 100 * MIN, Aff: aff, G });
const ep = (series: string, n: number, aff: number | undefined, G = ["Comedy"]): ChanItem => ({ Id: `${series}-e${n}`, Name: `E${n}`, Type: "Episode", SeriesId: series, SeriesName: series, ParentIndexNumber: 1, IndexNumber: n, RunTimeTicks: 25 * MIN, Aff: aff, G });
const profile = parseProfile({ genres: { thriller: 0.9, comedy: 0.4 }, decades: {}, media: { movie: 0.5, series: 0.3 } })!;

test("sample: without affinities it is the old hash sample; with them it is deterministic and favours the liked", () => {
  const plain = Array.from({ length: 200 }, (_, i) => film(i, undefined));
  assert.deepEqual(sample(plain, 50, "x").map((i) => i.Id).sort(), sample([...plain].reverse(), 50, "x").map((i) => i.Id).sort());
  const mixed = Array.from({ length: 400 }, (_, i) => film(i, i % 2 ? 0.95 : 0.05));
  const a = sample(mixed, 100, "x");
  assert.deepEqual(a.map((i) => i.Id), sample([...mixed].reverse(), 100, "x").map((i) => i.Id)); // server order doesn't matter
  const liked = a.filter((i) => (i.Aff ?? 0) > 0.5).length;
  assert.ok(liked > 75, `liked kept ${liked}/100`);
});

test("affinityWeight: neutral 1, liked > 1 > disliked", () => {
  assert.equal(affinityWeight(undefined), 1);
  assert.equal(affinityWeight(0.5), 1);
  assert.ok(affinityWeight(1) > 3.9 && affinityWeight(0) < 0.26);
});

test("lineup: same pool + cycle → same order; same members; liked come earlier on average; no Aff → unchanged path", () => {
  const pool = Array.from({ length: 60 }, (_, i) => film(i, i < 30 ? 0.95 : 0.05)).sort((a, b) => (a.Id < b.Id ? -1 : 1));
  const l1 = lineup(pool, "c", 3);
  assert.deepEqual(l1.map((i) => i.Id), lineup(pool, "c", 3).map((i) => i.Id));
  assert.deepEqual(l1.map((i) => i.Id).sort(), pool.map((i) => i.Id));
  let early = 0;
  for (let c = 0; c < 20; c++) early += lineup(pool, "c", c).slice(0, 20).filter((i) => (i.Aff ?? 0) > 0.5).length;
  assert.ok(early / 20 > 14, `avg liked in first 20 = ${early / 20}`);
  const bare = pool.map((i) => ({ ...i, Aff: undefined }));
  assert.deepEqual(lineup(bare, "c", 3).map((i) => i.Id), lineup(bare.map((i) => ({ ...i })), "c", 3).map((i) => i.Id));
});

test("schedule stays a pure function of the pool and the clock with affinities", () => {
  const pool = poolOf(Array.from({ length: 12 }, (_, i) => film(i, (i % 5) / 4)));
  const t = EPOCH_MS + 123_456_789;
  assert.deepEqual(schedule(pool, "c", t, 3).slots.map((s) => s.item.Id), schedule(poolOf([...pool].reverse()), "c", t, 3).slots.map((s) => s.item.Id));
  const s = schedule(pool, "c", t, 5);
  for (let k = 1; k < s.slots.length; k++) assert.equal(s.slots[k].startMs, s.slots[k - 1].endMs);
});

const base = () => {
  const comedy = [...Array.from({ length: 8 }, (_, i) => ep(`showA`, i + 1, 0.8)), ...Array.from({ length: 8 }, (_, i) => ep(`showB`, i + 1, 0.3))];
  return { "sci-fi-nights": [film(1, 0.9), film(2, 0.9), film(3, 0.8), film(5, 0.7), film(6, 0.7), film(7, 0.6), film(4, 0.2)], comedy };
};

test("personalDefs: none without a profile", () => {
  assert.deepEqual(personalDefs(null, base()), []);
});

test("personalDefs: For You, genre channel, Your shows built from the pools; hidden when under 3 items", () => {
  const defs = personalDefs(profile, base());
  assert.deepEqual(defs.map((d) => d.id), [FOR_YOU_ID, GENRE_ID, SHOWS_ID]);
  assert.equal(defs[1].name, "Thriller for you");
  const fy = personalItems(defs[0], base());
  assert.ok(fy.some((i) => !i.SeriesId) && fy.some((i) => i.SeriesId), "mixes films and episodes");
  assert.ok(fy.every((i) => (i.Aff ?? 0) >= 0.55));
  assert.ok(!fy.some((i) => i.SeriesId === "showB"), "low-fit show left out");
  assert.deepEqual(personalItems(defs[2], base()).map((i) => i.SeriesId).filter((s) => s !== "showA"), []);
  // thin pools → nothing defined
  assert.deepEqual(personalDefs(profile, { comedy: [film(1, 0.9), film(2, 0.9)] }), []);
});

test("personal items are stable for the same pools (schedule stays put)", () => {
  const d = personalDefs(profile, base());
  const a = d.map((x) => personalItems(x, base(), x.genres?.[0]).map((i) => i.Id));
  const b = d.map((x) => personalItems(x, { comedy: [...base().comedy].reverse(), "sci-fi-nights": [...base()["sci-fi-nights"]].reverse() }, x.genres?.[0]).map((i) => i.Id));
  assert.deepEqual(a.map((l) => [...l].sort()), b.map((l) => [...l].sort()));
});

test("channelById finds registered personal channels, not unknown ones", () => {
  const d = personalDefs(profile, base());
  setPersonalDefs(d);
  assert.equal(channelById(FOR_YOU_ID)?.personal, "foryou");
  assert.equal(channelById("nope"), null);
  assert.equal(channelById("comedy")?.id, "comedy");
  setPersonalDefs([]);
  assert.equal(channelById(FOR_YOU_ID), null);
});

test("orderByAffinity: best fit on now first, ties keep order, no affinity anywhere → untouched", () => {
  const pick = (id: string, aff?: number): ChanItem[] => Array.from({ length: 4 }, (_, i) => film(i + id.length * 10, aff, ["x"]));
  const defs = CHANNELS.slice(0, 3);
  const loads = { [defs[0].id]: pick("a", 0.2), [defs[1].id]: pick("b", 0.9), [defs[2].id]: pick("c", 0.9) };
  assert.deepEqual(orderByAffinity(defs, loads, EPOCH_MS + 5_000).map((d) => d.id), [defs[1].id, defs[2].id, defs[0].id]);
  const bare = { [defs[0].id]: pick("a"), [defs[1].id]: pick("b"), [defs[2].id]: pick("c") };
  assert.deepEqual(orderByAffinity(defs, bare, EPOCH_MS).map((d) => d.id), defs.map((d) => d.id));
});

test("visibleChannels takes a defs list", () => {
  const defs = personalDefs(profile, base());
  const loads = { [FOR_YOU_ID]: personalItems(defs[0], base()) };
  assert.deepEqual(visibleChannels(loads, [], defs).map((d) => d.id), [FOR_YOU_ID]);
});
