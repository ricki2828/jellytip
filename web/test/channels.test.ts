import { test } from "node:test";
import assert from "node:assert/strict";
import { CHANNELS, EPOCH_MS, MIN_ITEMS, TICKS_PER_SECOND, channelById, channelViable, cycleSeconds, chainBudget, chainTarget, hashString, IPTV_CAP, isIptvId, iptvPoolItems, itemLabel, mixItems, lineup, parseHidden, playedEnough, playable, poolOf, sample, sampleByGroup, schedule, seedFor, toggleHidden, tunePath, tuneTarget, yearsParam, fromStartTarget, rejoinLive, type ChanItem } from "../src/lib/channels.ts";

const MIN = 60 * TICKS_PER_SECOND;
const it = (id: string, min: number | undefined, o: Partial<ChanItem> = {}): ChanItem => ({ Id: id, Name: `Item ${id}`, Type: "Movie", RunTimeTicks: min == null ? undefined : min * MIN, ...o });
const EP = EPOCH_MS;

test("seed changes with channel and cycle", () => {
  assert.equal(seedFor("a", 3), seedFor("a", 3));
  assert.notEqual(seedFor("a", 3), seedFor("a", 4));
  assert.notEqual(seedFor("a", 3), seedFor("b", 3));
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

test("lineup is deterministic per cycle, a permutation, and differs between cycles", () => {
  const items = Array.from({ length: 20 }, (_, i) => it(`id${String(i).padStart(2, "0")}`, 60 + i));
  const pool = poolOf(items);
  const a = lineup(pool, "ch", 7).map((x) => x.Id);
  assert.deepEqual(a, lineup(poolOf([...items].reverse()), "ch", 7).map((x) => x.Id));
  assert.deepEqual([...a].sort(), items.map((x) => x.Id));
  assert.notDeepEqual(a, lineup(pool, "ch", 8).map((x) => x.Id));
});

test("lineup and poolOf do not mutate their input", () => {
  const items = [it("c", 60), it("a", 60), it("b", 60)];
  const pool = poolOf(items);
  lineup(pool, "x", 0);
  assert.deepEqual(items.map((x) => x.Id), ["c", "a", "b"]);
  assert.deepEqual(pool.map((x) => x.Id), ["a", "b", "c"]);
});

// pool a(100) b(50) c(30): a 180 min pass whatever the order
const POOL = poolOf([it("a", 100), it("b", 50), it("c", 30)]);
const T = 180 * 60000;
const orderAt = (c: number) => lineup(POOL, "ch", c).map((x) => x.Id);

test("schedule: pass number comes from the clock; first item of a pass is on at offset 0", () => {
  const now = EP + 5 * T;
  const s = schedule(POOL, "ch", now, 3);
  assert.equal(s.slots[0].item.Id, orderAt(5)[0]);
  assert.equal(s.offsetS, 0);
  assert.equal(s.progress, 0);
  assert.equal(s.slots[0].startMs, now);
});

test("schedule: offset inside an item; slots are back to back", () => {
  const now = EP + 2 * T + 3 * 60000 + 30000;
  const s = schedule(POOL, "ch", now, 3);
  const o = orderAt(2);
  assert.equal(s.slots[0].item.Id, o[0]);
  assert.equal(s.offsetS, 3 * 60 + 30);
  assert.equal(s.slots[0].startMs, EP + 2 * T);
  assert.equal(s.slots[1].item.Id, o[1]);
  assert.equal(s.slots[1].startMs, s.slots[0].endMs);
  assert.equal(s.slots[2].startMs, s.slots[1].endMs);
});

test("schedule: boundary between items starts the next one", () => {
  const o = orderAt(0);
  const len = (id: string) => POOL.find((x) => x.Id === id)!.RunTimeTicks! / MIN;
  const s = schedule(POOL, "ch", EP + len(o[0]) * 60000, 1);
  assert.equal(s.slots[0].item.Id, o[1]);
  assert.equal(s.offsetS, 0);
});

test("schedule: the item playing across a pass boundary is not cut, and up-next comes from the next pass", () => {
  const o = orderAt(4);
  const last = o[2];
  const lastLen = POOL.find((x) => x.Id === last)!.RunTimeTicks! / MIN;
  const now = EP + 5 * T - 60000; // one minute before pass 5 starts, inside the last item of pass 4 (min 30 min long)
  const s = schedule(POOL, "ch", now, 3);
  assert.equal(s.slots[0].item.Id, last);
  assert.equal(s.slots[0].endMs, EP + 5 * T);
  assert.equal(s.offsetS, lastLen * 60 - 60);
  assert.equal(s.slots[1].item.Id, orderAt(5)[0]);
  assert.equal(s.slots[1].startMs, EP + 5 * T);
  assert.equal(s.slots[2].item.Id, orderAt(5)[1]);
});

test("schedule: continuous across every minute of several passes (slot starts only ever move forward at item ends)", () => {
  let prev = schedule(POOL, "ch", EP + 3 * T, 2).slots[0];
  for (let m = 1; m < 3 * 180; m++) {
    const s = schedule(POOL, "ch", EP + 3 * T + m * 60000, 2);
    const cur = s.slots[0];
    if (cur.startMs === prev.startMs) assert.equal(cur.item.Id, prev.item.Id);
    else assert.equal(cur.startMs, prev.endMs);
    assert.ok(cur.startMs <= EP + 3 * T + m * 60000 && EP + 3 * T + m * 60000 < cur.endMs);
    prev = cur;
  }
});

test("schedule: same instant, same answer; sub-second times keep slot starts consistent", () => {
  const now = EP + 7_654_321;
  assert.deepEqual(schedule(POOL, "ch", now), schedule(POOL, "ch", now));
  const s = schedule(POOL, "ch", now, 2);
  assert.equal(now - s.slots[0].startMs, s.offsetS * 1000 + (now % 1000));
});

test("schedule: empty or runtime-less pools give nothing; a single item loops", () => {
  assert.deepEqual(schedule([], "ch", EP), { slots: [], offsetS: 0, progress: 0 });
  assert.equal(schedule(poolOf([it("a", undefined)]), "ch", EP).slots.length, 0);
  const s = schedule(poolOf([it("a", 10)]), "ch", EP + 25 * 60000, 3);
  assert.deepEqual(s.slots.map((x) => x.item.Id), ["a", "a", "a"]);
  assert.equal(s.offsetS, 300);
});

test("cycleSeconds sums runtimes", () => {
  assert.equal(cycleSeconds(POOL), 180 * 60);
});

test("tuneTarget: current item at its offset, chained to the next, with the channel", () => {
  const now = EP + T + 12 * 60000 + 30000;
  const s = schedule(POOL, "ch", now, 4);
  const o = orderAt(1);
  const t = tuneTarget(s.slots, s.offsetS)!;
  assert.deepEqual({ id: t.id, startS: t.startS, thenId: t.thenId }, { id: o[0], startS: 750, thenId: o[1] });
  assert.equal(tunePath(t, "ch"), `/play/${o[0]}?start=750&then=${o[1]}&ch=ch`);
});

test("tuneTarget: nearly over → next item from the start; next across a pass boundary", () => {
  const now = EP + 2 * T - 5000; // 5 s left of the last item of pass 1
  const s = schedule(POOL, "ch", now, 4);
  const t2 = tuneTarget(s.slots, s.offsetS)!;
  assert.deepEqual({ id: t2.id, startS: t2.startS, thenId: t2.thenId }, { id: orderAt(2)[0], startS: 0, thenId: orderAt(2)[1] });
  assert.equal(tuneTarget([], 0), null);
});

test("sample: stable, order-independent, capped; sampleByGroup caps per group and drops ungrouped", () => {
  const items = Array.from({ length: 50 }, (_, i) => ({ Id: `i${i}`, g: i < 30 ? "x" : i < 45 ? "y" : undefined }));
  const a = sample(items, 10, "s").map((x) => x.Id);
  assert.equal(a.length, 10);
  assert.deepEqual(a, sample([...items].reverse(), 10, "s").map((x) => x.Id).sort((p, q) => a.indexOf(p) - a.indexOf(q)));
  assert.equal(sample(items, 100).length, 50);
  const g = sampleByGroup(items, (x) => x.g, 5, 100, "s");
  assert.equal(g.length, 10);
  assert.ok(g.every((x) => x.g));
  assert.equal(sampleByGroup(items, (x) => x.g, 5, 7, "s").length, 7);
});

test("channelById accepts built-ins only", () => {
  assert.equal(channelById("comedy")?.name, "Comedy");
  assert.equal(channelById("nope"), null);
  assert.equal(channelById(null), null);
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

// ---------- IPTV films in channels ----------

const V = (n: number, o: Record<string, unknown> = {}) => ({ id: `p:m:${n}`, title: `Film ${n}`, tmdbId: 1000 + n, year: 1995, runtime: 100 * 60, backdrop: "https://image.tmdb.org/t/p/w780/x.jpg", ...o });

test("iptvPoolItems: keeps films with a trustworthy runtime, drops the rest", () => {
  const r = iptvPoolItems({
    items: [V(1), V(2, { runtime: 10 * 60 }), V(3, { runtime: 400 * 60 }), V(4, { runtime: undefined }), V(5, { langUnknown: true }), V(6, { id: "bad id" }), V(7, { title: "" }), V(1), V(8, { id: "p:s:8" }), null, "x"],
    pending: 3,
  });
  assert.deepEqual(r.items.map((x) => x.Id), ["p:m:1"]);
  assert.equal(r.pending, 3);
  const f = r.items[0];
  assert.equal(f.RunTimeTicks, 100 * 60 * TICKS_PER_SECOND);
  assert.equal(f.Tmdb, "1001");
  assert.equal(f.Type, "Movie");
  assert.equal(f.Art, "https://image.tmdb.org/t/p/w780/x.jpg");
  assert.equal(iptvPoolItems(V(1)).items.length, 0);
  assert.equal(iptvPoolItems(undefined).pending, 0);
});

test("iptvPoolItems: art only from TMDB https, and an IPTV film passes playable()", () => {
  const f = iptvPoolItems({ items: [V(1, { backdrop: "http://evil/x.jpg", poster: "https://provider.example/p.jpg" })] }).items[0];
  assert.equal(f.Art, undefined);
  assert.equal(playable([f]).length, 1);
});

test("mixItems: the library copy wins when both have the TMDB title; IPTV-only dupes collapse", () => {
  const lib = [it("a".repeat(32), 100, { Tmdb: "1001" }), it("b".repeat(32), 100)];
  const iptv = iptvPoolItems({ items: [V(1), V(2), V(2, { id: "q:m:2" }), V(3, { tmdbId: undefined })] }).items;
  const mixed = mixItems(lib, iptv);
  assert.deepEqual(mixed.map((x) => x.Id).sort(), ["a".repeat(32), "b".repeat(32), "p:m:2", "p:m:3"].sort());
});

test("mixItems: caps IPTV films with a stable sample, library untouched", () => {
  const lib = [it("a", 100)];
  const iptv = iptvPoolItems({ items: Array.from({ length: 300 }, (_, i) => V(i)) }).items;
  const m1 = mixItems(lib, iptv);
  assert.equal(m1.length, 1 + IPTV_CAP);
  assert.deepEqual(m1.map((x) => x.Id).sort(), mixItems(lib, [...iptv].reverse()).map((x) => x.Id).sort());
  assert.equal(mixItems(lib, iptv, 5).length, 6);
});

test("mix is schedulable: a mixed pool schedules, and an IPTV slot tunes through the IPTV player at its offset", () => {
  const lib = [it("a".repeat(32), 60), it("b".repeat(32), 60)];
  const iptv = iptvPoolItems({ items: [V(1), V(2)] }).items;
  const pool = poolOf(mixItems(lib, iptv), { maxMinutes: undefined });
  assert.equal(pool.length, 4);
  const now = EP + 5 * 3600_000 + 17 * 60_000 + 5000;
  const s = schedule(pool, "c1", now, 4);
  const iptvSlot = s.slots.find((x) => isIptvId(x.item.Id))!;
  assert.ok(iptvSlot);
  const path = tunePath({ id: iptvSlot.item.Id, startS: 1037, thenId: "z".repeat(32), title: "A & B" }, "c1");
  assert.equal(path, `/iptv/play?vod=${encodeURIComponent(iptvSlot.item.Id)}&t=A%20%26%20B&resume=1037&ch=c1`);
  assert.equal(tunePath({ id: iptvSlot.item.Id, startS: 0, thenId: null, title: "T" }, "c1"), `/iptv/play?vod=${encodeURIComponent(iptvSlot.item.Id)}&t=T&ch=c1`);
});

test("tunePath: a library item never chains with an IPTV id as then= (the player follows the clock)", () => {
  assert.equal(tunePath({ id: "a".repeat(32), startS: 5, thenId: "p:m:1" }, "c"), `/play/${"a".repeat(32)}?start=5&ch=c`);
});

test("iptv channels: every Movie channel declares TMDB genres, episode channels do not", () => {
  for (const c of CHANNELS) assert.equal(!!c.tmdbGenres?.length, c.type === "Movie", c.id);
});

test("chainTarget: never the item that just ended (file shorter than its runtime), otherwise the tune target", () => {
  const now = EP + T + 12 * 60000;
  const s = schedule(POOL, "ch", now, 4);
  const cur = s.slots[0].item.Id;
  assert.equal(chainTarget(s.slots, s.offsetS, "other")!.id, tuneTarget(s.slots, s.offsetS)!.id);
  const t = chainTarget(s.slots, s.offsetS, cur)!;
  assert.equal(t.id, s.slots[1].item.Id);
  assert.equal(t.startS, 0);
  assert.equal(chainTarget([], 0, "x"), null);
  assert.equal(chainTarget(s.slots.slice(0, 1), s.offsetS, cur), null);
});

test("chainBudget: the 4th hop inside two minutes is refused; old hops age out", () => {
  let st: number[] = [];
  for (let i = 0; i < 3; i++) {
    const r = chainBudget(st, 1000 + i);
    assert.equal(r.ok, true);
    st = r.stamps;
  }
  assert.equal(chainBudget(st, 5000).ok, false);
  assert.equal(chainBudget(st, 1000 + 121_000).ok, true);
});

test("playedEnough: a few seconds of a long film is a stub; a genuine short tail is fine", () => {
  assert.equal(playedEnough(8, 6000), false);
  assert.equal(playedEnough(60, 6000), true);
  assert.equal(playedEnough(30, 35), true);
  assert.equal(playedEnough(2, 0), true);
});

test("From start: current item from 0, off the clock, then-id is the next slot", () => {
  const pool = poolOf([it("a", 30), it("b", 30), it("c", 30), it("d", 30)]);
  const { slots, offsetS } = schedule(pool, "x", EP + 100 * 60_000 + 7 * 60_000, 4);
  const t = fromStartTarget(slots)!;
  assert.equal(t.id, slots[0].item.Id);
  assert.equal(t.startS, 0);
  assert.equal(t.thenId, slots[1].item.Id);
  assert.ok(offsetS > 0);
  const p = tunePath(t, "x", true);
  assert.match(p, /start=0/);
  assert.match(p, /&off=1$/);
  assert.doesNotMatch(tunePath(t, "x"), /off=1/);
  assert.equal(fromStartTarget([]), null);
});

test("From start on an IPTV film: no resume, off flag kept", () => {
  const p = tunePath({ id: "p:m:1", startS: 0, thenId: null, title: "Film" }, "x", true);
  assert.doesNotMatch(p, /resume=/);
  assert.match(p, /&ch=x&off=1$/);
});

test("Back to live: seek to the clock offset when the item is still on, else open what is on now", () => {
  const pool = poolOf([it("a", 30), it("b", 30), it("c", 30), it("d", 30)]);
  const { slots, offsetS } = schedule(pool, "x", EP + 45 * 60_000, 4);
  const cur = slots[0].item.Id;
  const same = rejoinLive(slots, offsetS, cur)!;
  assert.equal(same.kind, "seek");
  if (same.kind === "seek") assert.equal(same.s, offsetS);
  const other = rejoinLive(slots, offsetS, "zzz")!;
  assert.equal(other.kind, "go");
  if (other.kind === "go") assert.equal(other.target.id, cur);
  assert.equal(rejoinLive([], 0, cur), null);
});

test("Item on its last seconds: rejoin from an earlier item goes to the next slot (tune target rule)", () => {
  const pool = poolOf([it("a", 30), it("b", 30), it("c", 30), it("d", 30)]);
  const { slots, offsetS } = schedule(pool, "x", EP + 30 * 60_000 - 5_000, 4);
  const r = rejoinLive(slots, offsetS, slots[0].item.Id)!;
  assert.equal(r.kind, "go");
  if (r.kind === "go") assert.equal(r.target.id, slots[1].item.Id);
});
