import { test } from "node:test";
import assert from "node:assert/strict";
import { applyConstraints, candidates, chipsFor, nextThree, ranked, shownFor, type Pool } from "../src/lib/tonight.ts";
import { buildContinue } from "../src/lib/continueRow.ts";
import type { Item } from "../src/api/jellyfin.ts";
import type { VodItem } from "../src/lib/iptvVod.ts";

const T = 600_000_000;
const film = (id: string, o: Partial<Item> & { min?: number; pos?: number } = {}): Item =>
  ({ Id: id, Name: `Film ${id}`, Type: "Movie", RunTimeTicks: (o.min ?? 100) * T, CommunityRating: o.CommunityRating, Genres: o.Genres ?? ["Drama"], OfficialRating: o.OfficialRating, UserData: { PlaybackPositionTicks: (o.pos ?? 0) * T, Played: o.UserData?.Played, LastPlayedDate: o.pos ? "2026-11-01T10:00:00Z" : undefined } }) as Item;
const ep = (id: string, series: string, s: number, e: number, pos = 0): Item =>
  ({ Id: id, Name: `Ep ${e}`, Type: "Episode", SeriesId: series, SeriesName: `Show ${series}`, ParentIndexNumber: s, IndexNumber: e, RunTimeTicks: 50 * T, UserData: { PlaybackPositionTicks: pos * T, LastPlayedDate: pos ? "2026-11-02T10:00:00Z" : undefined } }) as Item;
const pool = (o: Partial<Pool> & { resume?: Item[]; next?: Item[] } = {}): Pool => ({
  entries: buildContinue(o.resume ?? [], o.next ?? [], [], []),
  recs: o.recs ?? [],
  rated: o.rated ?? [],
  iptv: o.iptv ?? [],
  hidden: o.hidden ?? new Set(),
  blocked: o.blocked ?? new Set(),
});
const vod = (id: string, o: Partial<VodItem> = {}): VodItem => ({ id, source: "iptv", kind: "movie", title: id, quality: [], tech: [], ...o });

test("ranking: resume, next episode, recs, rated, in that order", () => {
  const c = candidates(
    pool({
      resume: [film("r1", { min: 100, pos: 40 })],
      next: [ep("n1", "s1", 1, 2)],
      recs: [{ item: film("c1"), from: { title: "Because you loved X" } }],
      rated: [film("g1", { CommunityRating: 8.1 })],
    }),
  );
  assert.deepEqual(ranked(c).map((x) => x.source), ["resume", "nextup", "recs", "rated"]);
});

test("reasons are factual strings", () => {
  const c = candidates(
    pool({
      resume: [film("r1", { min: 100, pos: 40 })],
      next: [ep("n1", "s1", 1, 2)],
      recs: [{ item: film("c1"), from: { title: "Because you loved X" } }, { item: film("c2"), from: { title: "Row", list: "Best of 90s" } }],
      rated: [film("g1", { CommunityRating: 8.1 })],
    }),
  );
  const by = Object.fromEntries(c.map((x) => [x.key, x.reason]));
  assert.equal(by.r1, "You're 40 min into this");
  assert.equal(by.n1, "Next episode of Show s1");
  assert.equal(by.c1, 'In your "Because you loved X" row');
  assert.equal(by.c2, 'On your Letterboxd list "Best of 90s"');
  assert.equal(by.g1, "Rated 8.1, unwatched");
});

test("exclusions: under 10 min left, watched, started, hidden, blocked, low rated, duplicates", () => {
  const c = candidates(
    pool({
      resume: [film("almost", { min: 100, pos: 95 }), film("ok", { min: 100, pos: 40 })],
      recs: [
        { item: film("seen", { UserData: { Played: true } }), from: { title: "R" } },
        { item: film("half", { pos: 20 }), from: { title: "R" } },
        { item: film("hid"), from: { title: "R" } },
        { item: film("blk"), from: { title: "R" } },
        { item: film("dup"), from: { title: "R" } },
      ],
      rated: [film("dup", { CommunityRating: 9 }), film("low", { CommunityRating: 6.9 }), film("good", { CommunityRating: 7.5 })],
      hidden: new Set(["lib:hid"]),
      blocked: new Set(["blk"]),
    }),
  );
  assert.deepEqual(c.map((x) => x.key).sort(), ["dup", "good", "ok"]);
  assert.equal(c.find((x) => x.key === "dup")!.source, "recs");
});

test("hidden Continue Watching tiles are not offered", () => {
  const dismissed = { "lib:r1": { at: 9e9, t: 1 } };
  const entries = buildContinue([film("r1", { min: 100, pos: 40 })], [], [], [], { dismissed });
  assert.equal(candidates({ ...pool(), entries }).length, 0);
});

test("IPTV films only when the rest is thin, owned providers' items only", () => {
  const rated = Array.from({ length: 6 }, (_, i) => film(`g${i}`, { CommunityRating: 8 }));
  assert.equal(candidates(pool({ rated, iptv: [vod("p:m:1")] })).some((x) => x.source === "iptv"), false);
  const thin = candidates(pool({ rated: rated.slice(0, 2), iptv: [vod("p:m:1", { rating: 7.2, providerName: "Prov" }), vod("p:m:2", { played: true }), vod("p:s:3", { kind: "series" })] }));
  const i = thin.filter((x) => x.source === "iptv");
  assert.equal(i.length, 1);
  assert.equal(i[0].reason, "Rated 7.2 on Prov");
  assert.equal(i[0].play, "/iptv/play?vod=p%3Am%3A1&t=p%3Am%3A1");
});

test("constraints map to askParse chips", () => {
  assert.deepEqual(chipsFor(["short"]).map((c) => c.data.t), ["maxRuntime"]);
  assert.deepEqual(chipsFor(["new"]).map((c) => c.data.t), ["unplayed"]);
  assert.deepEqual(chipsFor(["kids"]).map((c) => c.data.t), ["kids"]);
  assert.ok(chipsFor(["light"]).every((c) => c.data.t === "genre"));
  assert.equal(chipsFor([]).length, 0);
});

test("under 2 hours uses time left for a resume and drops unknown lengths", () => {
  const c = candidates(pool({ resume: [film("long", { min: 200, pos: 120 })], recs: [{ item: film("big", { min: 150 }), from: { title: "R" } }, { item: film("small", { min: 95 }), from: { title: "R" } }, { item: { ...film("unk"), RunTimeTicks: undefined }, from: { title: "R" } }] }));
  assert.deepEqual(applyConstraints(c, ["short"]).map((x) => x.key).sort(), ["long", "small"]);
});

test("light, new and kids", () => {
  const c = candidates(
    pool({
      resume: [film("res", { min: 90, pos: 30, Genres: ["Comedy"] })],
      recs: [
        { item: film("com", { Genres: ["Comedy"] }), from: { title: "R" } },
        { item: film("dra", { Genres: ["Drama"] }), from: { title: "R" } },
        { item: film("kid", { Genres: ["Animation"], OfficialRating: "G" }), from: { title: "R" } },
        { item: film("kidx", { Genres: ["Animation"], OfficialRating: "R" }), from: { title: "R" } },
        { item: film("norate", { Genres: ["Animation"] }), from: { title: "R" } },
      ],
    }),
  );
  assert.deepEqual(applyConstraints(c, ["light"]).map((x) => x.key).sort(), ["com", "kid", "kidx", "norate", "res"]);
  assert.equal(applyConstraints(c, ["new"]).some((x) => x.key === "res"), false);
  assert.deepEqual(applyConstraints(c, ["kids"]).map((x) => x.key), ["kid"]);
  assert.deepEqual(applyConstraints(c, ["kids", "new"]).map((x) => x.key), ["kid"]);
});

test("exactly three, then a different three that never repeats", () => {
  const rated = Array.from({ length: 8 }, (_, i) => film(`g${i}`, { CommunityRating: 9 - i * 0.1 }));
  const c = candidates(pool({ rated }));
  const store = new Map<string, Set<string>>();
  const a = nextThree(c, [], shownFor(store, []));
  const b = nextThree(c, [], shownFor(store, []));
  const d = nextThree(c, [], shownFor(store, []));
  assert.equal(a.length, 3);
  assert.equal(b.length, 3);
  assert.equal(d.length, 2);
  const all = [...a, ...b, ...d].map((x) => x.key);
  assert.equal(new Set(all).size, 8);
  assert.deepEqual(nextThree(c, [], shownFor(store, [])), []);
  // a different constraint set starts fresh, and the order is deterministic
  assert.deepEqual(nextThree(c, ["new"], shownFor(store, ["new"])).map((x) => x.key), a.map((x) => x.key));
});

test("kids + light still need a Family/Animation/Kids genre", () => {
  const c = candidates(
    pool({
      recs: [
        { item: film("pgcom", { Genres: ["Comedy"], OfficialRating: "PG" }), from: { title: "R" } },
        { item: film("fam", { Genres: ["Comedy", "Family"], OfficialRating: "PG" }), from: { title: "R" } },
      ],
    }),
  );
  assert.deepEqual(applyConstraints(c, ["kids", "light"]).map((x) => x.key), ["fam"]);
});

test("a not-interested series blocks its episodes", () => {
  const c = candidates(pool({ next: [ep("n1", "s1", 1, 2), ep("n2", "s2", 1, 2)], blocked: new Set(["s1"]) }));
  assert.deepEqual(c.map((x) => x.key), ["n2"]);
});

test("resume under two minutes in says started recently", () => {
  const c = candidates(pool({ resume: [{ ...film("r1", { min: 100 }), UserData: { PlaybackPositionTicks: 1 * T, LastPlayedDate: "2026-11-01T10:00:00Z" } } as Item] }));
  assert.equal(c[0].reason, "Started recently");
});
