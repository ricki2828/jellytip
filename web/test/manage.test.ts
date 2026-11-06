import { test } from "node:test";
import assert from "node:assert/strict";
import { buildItemsQuery, buildCountQuery, classify, createTapGate, errorText, mapLibraries, mapTasks, matchesFilter, phraseOk, refreshPath, runningTasks, startIntervalPoll, type IntervalEnv, type Library, type PollResult } from "../src/lib/manage.ts";

const ID = "a".repeat(32);
const lib: Library = { id: ID, name: "Films", kind: "movies" };

test("query building: server-side filters, bounded paging", () => {
  const q = buildItemsQuery(lib, "no-overview", 100, 50);
  assert.equal(q.ParentId, ID);
  assert.equal(q.HasOverview, false);
  assert.equal(q.IncludeItemTypes, "Movie");
  assert.equal(q.StartIndex, 100);
  assert.equal(q.Limit, 50);
  assert.equal(buildItemsQuery(lib, "no-poster", 0, 99999).Limit, 200);
  const r = buildItemsQuery(lib, "recent", 40);
  assert.equal(r.SortBy, "DateCreated");
  assert.equal(r.StartIndex, 0);
  assert.equal(r.Limit, 12);
  assert.match(String(buildItemsQuery(lib, "no-subs").Fields), /MediaStreams/);
  assert.doesNotMatch(String(buildItemsQuery(lib, "no-poster").Fields), /MediaStreams/);
  assert.equal(buildCountQuery(lib).Limit, 0);
  assert.throws(() => buildItemsQuery({ ...lib, id: "x/../y" }, "recent"));
});

test("libraries: invalid rows dropped", () => {
  const l = mapLibraries([{ ItemId: ID, Name: "TV", CollectionType: "tvshows" }, { ItemId: "bad", Name: "x" }, null]);
  assert.deepEqual(l, [{ id: ID, name: "TV", kind: "tvshows" }]);
  assert.deepEqual(mapLibraries({}), []);
});

test("classification: overview, poster, subtitle language", () => {
  const base = { Id: ID, Name: "A", Type: "Movie" };
  assert.deepEqual(classify({ ...base, Overview: "  ", ImageTags: {} }, "eng"), { overview: true, poster: true, subs: null });
  const withSubs = { ...base, Overview: "x", ImageTags: { Primary: "t" }, MediaStreams: [{ Type: "Subtitle", Language: "en" }, { Type: "Audio", Language: "fra" }] };
  assert.deepEqual(classify(withSubs, "eng"), { overview: false, poster: false, subs: false });
  assert.equal(classify(withSubs, "fre").subs, true); // only an audio track is French
  assert.equal(classify({ ...withSubs, MediaStreams: [{ Type: "Subtitle", Language: "fra" }] }, "fre").subs, false);
  assert.equal(classify({ ...base, Type: "Series", MediaStreams: [] }, "eng").subs, null); // unknown is not missing
  assert.equal(matchesFilter({ ...base }, "no-subs", "eng"), false); // streams not loaded
  assert.equal(matchesFilter(withSubs, "no-subs", "ger"), true);
  assert.equal(matchesFilter(withSubs, "no-poster", "eng"), false);
  assert.equal(matchesFilter({ ...base }, "no-poster", "eng"), true);
});

test("refresh path never replaces by default", () => {
  assert.match(refreshPath(ID), /metadataRefreshMode=Default/);
  assert.match(refreshPath(ID), /replaceAllImages=false/);
  assert.match(refreshPath(ID, { full: true }), /metadataRefreshMode=FullRefresh/);
  assert.match(refreshPath(ID, { full: true }), /replaceAllMetadata=false/);
  assert.throws(() => refreshPath("../Users"));
});

test("errors: 403 says admin only", () => {
  assert.match(errorText({ status: 403 }, "Scan"), /Admin only/);
  assert.match(errorText({ status: 500 }, "Scan"), /500/);
  assert.match(errorText(new Error("x"), "Scan"), /no connection/);
});

test("confirm gate: phrase and tap", () => {
  assert.equal(phraseOk(" DELETE ", "DELETE"), true);
  assert.equal(phraseOk("delete", "DELETE"), false);
  const g = createTapGate(1000);
  assert.equal(g.confirm("scan", 0), false); // never armed
  g.arm("scan", 0);
  assert.equal(g.confirm("other", 10), false); // wrong target, and disarms
  g.arm("scan", 0);
  assert.equal(g.confirm("scan", 1001), false); // late
  g.arm("scan", 0);
  assert.equal(g.confirm("scan", 500), true);
  assert.equal(g.confirm("scan", 600), false); // once only
});

test("tasks mapping", () => {
  const t = mapTasks([{ Id: "1", Name: "Scan", State: "Running", CurrentProgressPercentage: 41.6, Category: "Library" }, { Id: "2", Name: "Idle" }, { nope: 1 }]);
  assert.equal(t.length, 2);
  assert.equal(t[0].percent, 42);
  assert.equal(t[1].percent, null);
  assert.deepEqual(runningTasks(t).map((x) => x.id), ["1"]);
});

function fakeEnv() {
  let hidden = false;
  let id = 0;
  const timers = new Map<number, { fn: () => void; ms: number }>();
  let vis: (() => void) | null = null;
  const env: IntervalEnv = {
    setTimer: (fn, ms) => (timers.set(++id, { fn, ms }), id),
    clearTimer: (h) => void timers.delete(h as number),
    hidden: () => hidden,
    onVisible: (cb) => ((vis = cb), () => (vis = null)),
  };
  const tick = () => new Promise((r) => setImmediate(r));
  return {
    env,
    timers,
    async fire() {
      const [k, v] = [...timers.entries()][0] ?? [];
      if (k == null) return;
      timers.delete(k);
      v!.fn();
      await tick();
    },
    setHidden(h: boolean) {
      hidden = h;
      if (!h) vis?.();
    },
    tick,
  };
}

test("poller: 10 s cadence, pauses hidden, stops on denied, cancel drops late answers", async () => {
  const f = fakeEnv();
  const seen: string[] = [];
  let n = 0;
  const p = startIntervalPoll<number>({ env: f.env, fetch: async () => ({ kind: "ok", value: ++n }), onResult: (r) => seen.push(r.kind + (r.kind === "ok" ? r.value : "")) });
  await f.tick();
  assert.deepEqual(seen, ["ok1"]);
  assert.equal([...f.timers.values()][0].ms, 10_000);
  await f.fire();
  assert.deepEqual(seen, ["ok1", "ok2"]);
  f.setHidden(true);
  await f.fire(); // timer fires while hidden: no fetch
  assert.equal(n, 2);
  f.setHidden(false); // back: immediate refresh
  await f.tick();
  assert.equal(n, 3);
  p.cancel();
  assert.equal(f.timers.size, 0);

  const g = fakeEnv();
  const seen2: string[] = [];
  startIntervalPoll<number>({ env: g.env, fetch: async () => ({ kind: "denied" }), onResult: (r) => seen2.push(r.kind) });
  await g.tick();
  assert.deepEqual(seen2, ["denied"]);
  assert.equal(g.timers.size, 0); // no retry after 403

  const h = fakeEnv();
  const seen3: string[] = [];
  let release!: (r: PollResult<number>) => void;
  const p3 = startIntervalPoll<number>({ env: h.env, fetch: () => new Promise((r) => (release = r)), onResult: (r) => seen3.push(r.kind) });
  p3.cancel();
  release({ kind: "ok", value: 1 });
  await h.tick();
  assert.deepEqual(seen3, []);
  assert.equal(h.timers.size, 0);
});
