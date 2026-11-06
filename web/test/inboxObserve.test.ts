import { test } from "node:test";
import assert from "node:assert/strict";
import { MAX_IPTV_PER_LOOK, PARALLEL, REQUEST_IPTV_AGE_MS, observeEpisodes, observeTitles, pool, type EpisodeDeps, type RawEpisode, type TitleDeps } from "../src/lib/inboxObserve.ts";
import { applyObservation, emptyStore, reqKey, type RequestRec } from "../src/lib/inbox.ts";

const NOW = 1_800_000_000_000;
const raw = (id: string, extra: Partial<RawEpisode> = {}): RawEpisode => ({ id, name: id, seriesName: "Show", season: 1, episode: 1, ...extra });

test("pool: bounded parallelism, order kept, a throw is undefined", async () => {
  let live = 0;
  let peak = 0;
  const r = await pool([1, 2, 3, 4, 5, 6, 7, 8], PARALLEL, async (n) => {
    live++;
    peak = Math.max(peak, live);
    await new Promise((res) => setTimeout(res, 5));
    live--;
    if (n === 3) throw new Error("x");
    return n * 2;
  });
  assert.ok(peak <= PARALLEL && peak > 1);
  assert.deepEqual(r, [2, 4, undefined, 8, 10, 12, 14, 16]);
});

test("episodes: only followed series are asked; placeholders skipped; a failing series is absent (carried by the snapshot)", async () => {
  const asked: string[] = [];
  const deps: EpisodeDeps = {
    favouriteSeries: async () => [{ id: "s1", name: "One" }],
    nextUpSeries: async () => [{ id: "s2", name: "Two" }, { id: "s3", name: "Three" }],
    episodesOf: async (id) => {
      asked.push(id);
      if (id === "s3") throw new Error("down");
      return [raw(`${id}a`), raw(`${id}b`, { virtual: true })];
    },
  };
  const o = (await observeEpisodes(deps, {}))!;
  assert.deepEqual(asked.sort(), ["s1", "s2", "s3"]);
  assert.deepEqual(Object.keys(o.eps).sort(), ["s1", "s2"]);
  assert.deepEqual(o.eps.s1.map((e) => e.id), ["s1a"]);
  assert.deepEqual(o.checked.sort(), ["s1", "s2"]);
  // carry-through into the snapshot, and the failed series is still known afterwards
  let s = applyObservation({ ...emptyStore("u"), snap: { titles: {}, eps: { s3: ["s3a"] } }, seeded: true }, o, NOW);
  assert.deepEqual(s.snap.eps.s3, ["s3a"]);
});

test("episodes: capped per look, least recently checked first, so looks go round-robin", async () => {
  const asked: string[] = [];
  const deps: EpisodeDeps = {
    favouriteSeries: async () => ["a", "b", "c", "d"].map((id) => ({ id, name: id })),
    nextUpSeries: async () => [],
    episodesOf: async (id) => (asked.push(id), []),
  };
  const first = (await observeEpisodes(deps, {}, 2))!;
  assert.equal(first.checked.length, 2);
  const checked = Object.fromEntries(first.checked.map((k) => [k, NOW]));
  asked.length = 0;
  const second = (await observeEpisodes(deps, checked, 2))!;
  assert.deepEqual(second.checked.sort(), ["a", "b", "c", "d"].filter((k) => !first.checked.includes(k)));
});

test("episodes: both followed-series lookups failing = unreachable, not 'nothing followed'", async () => {
  const boom = async () => {
    throw new Error("x");
  };
  assert.equal(await observeEpisodes({ favouriteSeries: boom, nextUpSeries: boom, episodesOf: boom }, {}), undefined);
});

const req = (tmdbId: number, extra: Partial<RequestRec> = {}): RequestRec => ({ key: reqKey("movie", tmdbId), tmdbId, media: "movie", title: `T${tmdbId}`, at: NOW - 1000, ...extra });
const vod = (id: string, tmdbId: number) => ({ id, source: "iptv" as const, kind: "movie" as const, tmdbId, title: "T", quality: [], tech: [] });

test("titles: library wins; IPTV only for requests under 60 days, capped, own providers only", async () => {
  const searched: string[] = [];
  const deps: TitleDeps = {
    libraryByTmdb: async () => [{ media: "movie", tmdbId: 1, id: "lib1" }],
    providerIds: async () => [3],
    vodSearch: async (_m, title) => (searched.push(title), title === "T2" ? [vod("3:m:2", 2), vod("9:m:2", 2)] : title === "T3" ? [vod("9:m:3", 3)] : []),
  };
  const reqs = [req(1), req(2), req(3), req(4, { at: NOW - REQUEST_IPTV_AGE_MS - 1 })];
  const o = (await observeTitles(deps, reqs, {}, NOW))!;
  assert.equal(o.titles["movie:1"].r, 2);
  assert.equal(o.titles["movie:2"].r, 1);
  assert.equal(o.titles["movie:2"].iptvId, "3:m:2");
  assert.equal(o.titles["movie:3"].r, 0); // only on someone else's provider
  assert.equal(o.titles["movie:3"].partial, undefined);
  assert.ok(!searched.includes("T1") && !searched.includes("T4"));
  assert.equal(o.titles["movie:4"].partial, true); // too old to search: keeps whatever rank it had
});

test("titles: per-look cap goes round-robin; unchecked ones are partial", async () => {
  const searched: string[] = [];
  const deps: TitleDeps = { libraryByTmdb: async () => [], providerIds: async () => [3], vodSearch: async (_m, t) => (searched.push(t), []) };
  const reqs = Array.from({ length: MAX_IPTV_PER_LOOK + 3 }, (_, i) => req(i + 1));
  const a = (await observeTitles(deps, reqs, {}, NOW))!;
  assert.equal(searched.length, MAX_IPTV_PER_LOOK);
  assert.equal(a.checked.length, MAX_IPTV_PER_LOOK);
  const left = reqs.filter((r) => !a.checked.includes(r.key)).map((r) => r.key);
  assert.equal(left.length, 3);
  assert.ok(left.every((k) => a.titles[k].partial === true));
  searched.length = 0;
  const b = (await observeTitles(deps, reqs, Object.fromEntries(a.checked.map((k) => [k, NOW])), NOW))!;
  assert.ok(left.every((k) => b.checked.includes(k)));
});

test("titles: library unreachable = undefined; failed IPTV search is partial", async () => {
  const bad: TitleDeps = { libraryByTmdb: async () => { throw new Error("x"); }, providerIds: async () => [3], vodSearch: async () => [] };
  assert.equal(await observeTitles(bad, [req(1)], {}, NOW), undefined);
  const iptvDown: TitleDeps = { libraryByTmdb: async () => [], providerIds: async () => [3], vodSearch: async () => null };
  const o = (await observeTitles(iptvDown, [req(1)], {}, NOW))!;
  assert.equal(o.titles["movie:1"].partial, true);
  assert.deepEqual(o.checked, []);
});
