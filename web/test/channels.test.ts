import { test } from "node:test";
import assert from "node:assert/strict";
import { CHANNELS, EPOCH_MS, MIN_ITEMS, TICKS_PER_SECOND, channelById, channelViable, cycleSeconds, chainBudget, chainTarget, hashString, IPTV_CAP, isIptvId, iptvPoolItems, iptvEpisodeItems, mixEpisodes, withinYearTo, isIptvEpisodeId, BLOCK_MAX, BLOCK_MIN, itemLabel, mixItems, lineup, rng, parseHidden, playedEnough, playable, poolOf, sample, sampleByGroup, schedule, seedFor, toggleHidden, tunePath, tuneTarget, yearsParam, fromStartTarget, rejoinLive, type ChanItem } from "../src/lib/channels.ts";

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
  assert.ok(CHANNELS.length >= 5 && CHANNELS.length <= 9);
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

test("iptv channels: every channel declares TMDB genres (films and episodes both draw from IPTV)", () => {
  for (const c of CHANNELS) assert.ok(c.tmdbGenres?.length, c.id);
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

// ---------- IPTV episodes in channels ----------

const E = (series: number, n: number, o: Record<string, unknown> = {}) => ({
  kind: "episode", id: `p:e:${series}x${n}`, seriesId: `p:s:${series}`, tmdbId: 5000 + series, seriesTitle: `Show ${series}`, title: `Ep ${n}`, season: 1, episode: n, year: 2001, runtime: 22 * 60,
  backdrop: "https://image.tmdb.org/t/p/w780/e.jpg", ...o,
});
const epPool = (series: number, n: number) => Array.from({ length: n }, (_, i) => E(series, i + 1));

test("iptvEpisodeItems: maps episodes, drops bad ids, specials, odd runtimes, dupes and langUnknown", () => {
  const r = iptvEpisodeItems({
    items: [E(1, 1), E(1, 1), E(1, 2, { runtime: 30 }), E(1, 3, { season: 0 }), E(1, 4, { id: "p:m:4" }), E(1, 5, { seriesId: "p:m:1" }), E(1, 6, { seriesTitle: "" }), E(1, 7, { langUnknown: true }), E(1, 8, { runtime: 400 * 60 }), E(1, 9, { title: "" }), null, "x"],
    pending: 2,
  });
  assert.deepEqual(r.items.map((x) => x.Id), ["p:e:1x1", "p:e:1x9"]);
  assert.equal(r.pending, 2);
  const e = r.items[0];
  assert.equal(e.Type, "Episode");
  assert.equal(e.SeriesId, "p:s:1");
  assert.equal(e.SeriesName, "Show 1");
  assert.equal(e.SeriesTmdb, "5001");
  assert.equal(e.RunTimeTicks, 22 * 60 * TICKS_PER_SECOND);
  assert.equal(r.items[1].Name, "Episode 9");
  assert.equal(playable([e]).length, 1);
  assert.equal(isIptvId(e.Id), true);
  assert.equal(isIptvEpisodeId("a".repeat(32)), false);
});

test("mixEpisodes: a library series wins over an IPTV series with the same TMDB id", () => {
  const lib: ChanItem[] = [{ Id: "l1".padEnd(32, "0"), Name: "x", Type: "Episode", SeriesId: "s".repeat(32), SeriesTmdb: "5001", ParentIndexNumber: 1, IndexNumber: 1, RunTimeTicks: 22 * MIN }];
  const iptv = iptvEpisodeItems({ items: [...epPool(1, 5), ...epPool(2, 5), ...epPool(3, 4).map((e) => ({ ...e, tmdbId: 5002 }))] }).items;
  const mixed = mixEpisodes(lib, iptv);
  const series = new Set(mixed.filter((x) => isIptvId(x.Id)).map((x) => x.SeriesId));
  assert.deepEqual([...series], ["p:s:2"]); // 1 = in the library; 3 = same TMDB as 2, collapses to the first id
  assert.equal(mixed.filter((x) => x.SeriesId === "p:s:2").length, 5);
  assert.equal(mixed.length, 6);
});

test("mixEpisodes: cap keeps whole series, in a stable order independent of input order", () => {
  const iptv = iptvEpisodeItems({ items: Array.from({ length: 30 }, (_, s) => epPool(s + 1, 10)).flat() }).items;
  const a = mixEpisodes([], iptv, 55);
  const b = mixEpisodes([], [...iptv].reverse(), 55);
  assert.deepEqual(a.map((x) => x.Id), b.map((x) => x.Id));
  assert.ok(a.length <= 55 && a.length >= 50);
  const per = new Map<string, number>();
  for (const x of a) per.set(x.SeriesId!, (per.get(x.SeriesId!) ?? 0) + 1);
  assert.ok([...per.values()].every((n) => n === 10));
});

test("lineup: episodes of a series play in order in blocks of 2-4; it is a permutation and deterministic", () => {
  const pool = poolOf(iptvEpisodeItems({ items: [...epPool(1, 13), ...epPool(2, 9), ...epPool(3, 1), ...epPool(4, 6)] }).items);
  const l1 = lineup(pool, "sc", 7);
  assert.deepEqual(l1.map((x) => x.Id), lineup(pool, "sc", 7).map((x) => x.Id));
  assert.deepEqual(l1.map((x) => x.Id).sort(), pool.map((x) => x.Id).sort());
  assert.notDeepEqual(l1.map((x) => x.Id), lineup(pool, "sc", 8).map((x) => x.Id));
  // reading the list: consecutive same-series runs; every run is ascending, no run longer than BLOCK_MAX+1 unless blocks of one show happen to touch
  const runs: ChanItem[][] = [];
  for (const x of l1) (runs.at(-1)?.[0].SeriesId === x.SeriesId ? runs.at(-1)! : runs[runs.push([])-1]).push(x);
  for (const r of runs) assert.deepEqual(r.map((x) => x.IndexNumber), [...r.map((x) => x.IndexNumber)].sort((p, q) => p! - q!));
  // each series is cut into blocks of BLOCK_MIN..BLOCK_MAX+1 (a stranded single joins the previous block), except a series of one
  const sizes = new Map<string, number[]>();
  const seen: ChanItem[] = [];
  for (const x of l1) seen.push(x);
  assert.equal(BLOCK_MIN, 2);
  assert.equal(BLOCK_MAX, 4);
  for (const sid of ["p:s:1", "p:s:2", "p:s:4"]) {
    const eps = l1.filter((x) => x.SeriesId === sid).map((x) => x.IndexNumber!);
    // the series' episodes, grouped by gaps in the list order, are consecutive index runs
    let cur: number[] = [];
    const groups: number[][] = [];
    for (const x of l1) {
      if (x.SeriesId !== sid) { if (cur.length) groups.push(cur); cur = []; continue; }
      cur.push(x.IndexNumber!);
    }
    if (cur.length) groups.push(cur);
    for (const g of groups) assert.ok(g.every((n, i) => i === 0 || n === g[i - 1] + 1), `${sid} ${g}`);
    assert.equal(eps.length, sid === "p:s:1" ? 13 : sid === "p:s:2" ? 9 : 6);
    sizes.set(sid, groups.map((g) => g.length));
  }
  // block sizes (merged adjacent blocks of one show can add up, so only the lower bound is exact)
  for (const g of [...sizes.values()].flat()) assert.ok(g >= BLOCK_MIN);
});

test("lineup: a films-only pool shuffles exactly as before (no block logic)", () => {
  const films = Array.from({ length: 12 }, (_, i) => it(`f${String(i).padStart(2, "0")}`, 90));
  const r = rng(seedFor("c", 3));
  const a = [...films];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  assert.deepEqual(lineup(films, "c", 3).map((x) => x.Id), a.map((x) => x.Id));
});

test("an IPTV episode slot tunes through the IPTV player with series + episode ids at the clock offset", () => {
  const pool = poolOf(iptvEpisodeItems({ items: [...epPool(1, 6), ...epPool(2, 6)] }).items);
  const now = EP + 3 * 3600_000 + 7 * 60_000 + 3000;
  const sch = schedule(pool, "sc", now, 4);
  const t = tuneTarget(sch.slots, sch.offsetS)!;
  assert.ok(isIptvEpisodeId(t.id));
  assert.match(t.title, /^Show \d · S1 E\d · Ep \d$/);
  assert.equal(t.seriesId, sch.slots.find((s) => s.item.Id === t.id)!.item.SeriesId);
  const path = tunePath({ id: "p:e:1x2", seriesId: "p:s:1", startS: 437, thenId: null, title: "Show 1 · S1 E2 · A & B" }, "sc");
  assert.equal(path, "/iptv/play?series=p%3As%3A1&episode=p%3Ae%3A1x2&t=Show%201%20%C2%B7%20S1%20E2%20%C2%B7%20A%20%26%20B&resume=437&ch=sc");
  assert.ok(tunePath({ id: "p:e:1x2", seriesId: "p:s:1", startS: 0, thenId: null, title: "T" }, "sc", true).endsWith("&ch=sc&off=1"));
  assert.ok(!tunePath({ id: "p:e:1x2", seriesId: "p:s:1", startS: 0, thenId: null, title: "T" }, "sc").includes("resume"));
  const fs = fromStartTarget(sch.slots)!;
  assert.equal(fs.startS, 0);
  assert.ok(fs.seriesId);
  // the chain never re-tunes the episode that just ended
  const c = chainTarget(sch.slots, sch.offsetS, t.id)!;
  assert.notEqual(c.id, t.id);
});

test("episode channels: mixed library + IPTV pool is viable and hides under 3 schedulable items", () => {
  const def = CHANNELS.find((c) => c.id === "sitcom-classics")!;
  assert.equal(def.type, "Episode");
  assert.ok(CHANNELS.some((c) => c.id === "light-bites"));
  const two = iptvEpisodeItems({ items: [E(1, 1), E(1, 2)] }).items;
  assert.equal(channelViable(mixEpisodes([], two), def), false);
  const three = iptvEpisodeItems({ items: [E(1, 1), E(1, 2), E(1, 3)] }).items;
  assert.equal(channelViable(mixEpisodes([], three), def), true);
  // 22 min fits Classics (20-35) but not a 10 min bite channel floor of 15? it does; a 12 min episode is out of both
  assert.equal(channelViable(iptvEpisodeItems({ items: [E(1, 1, { runtime: 12 * 60 }), E(1, 2, { runtime: 12 * 60 }), E(1, 3, { runtime: 12 * 60 })] }).items, def), false);
});

test("Sitcom Classics: Comedy shows from 2005 or earlier; unknown year is out; Sunday Sitcoms has no year cap", () => {
  const def = CHANNELS.find((c) => c.id === "sitcom-classics")!;
  assert.equal(def.yearTo, 2005);
  assert.equal(CHANNELS.find((c) => c.id === "sunday-sitcoms")!.yearTo, undefined);
  const eps = iptvEpisodeItems({ items: [E(1, 1, { year: 1998 }), E(2, 1, { year: 2005 }), E(3, 1, { year: 2006 }), E(4, 1, { year: undefined })] }).items;
  assert.deepEqual(withinYearTo(eps, def).map((x) => x.SeriesId), ["p:s:1", "p:s:2"]);
  assert.equal(withinYearTo(eps, {}).length, 4);
});
