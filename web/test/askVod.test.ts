import { test } from "node:test";
import assert from "node:assert/strict";
import { chipsFromData, parseAsk } from "../src/lib/askParse.ts";
import { askIptvPicks, TMDB_GENRES, vodKeep, vodNote, vodQueries, VOD_LIMIT, type VodView } from "../src/lib/askVod.ts";
import { parseVodItems } from "../src/lib/iptvVod.ts";

const NOW = new Date("2026-11-06T00:00:00Z");
const chips = (q: string) => parseAsk(q, NOW).chips;
const v = (o: Partial<VodView>): VodView => ({ kind: "movie", year: 2000, genreIds: [], certification: null, runtime: null, played: null, ...o });
const G = TMDB_GENRES;

test("one IPTV query per kind; the route gets ratings, length, rating floor and the year span", () => {
  const q = vodQueries(chips("for the kids, under 2 hours, well rated 90s"));
  assert.deepEqual(q.map((x) => x.kind), ["movie", "series"]);
  assert.deepEqual(q[0].params, {
    kind: "movie", limit: String(VOD_LIMIT), sort: "rating", certifications: "G|PG|TV-Y|TV-Y7|TV-G|TV-PG",
    maxRuntime: "120", minRating: "7", year: "1990-1999", genreIds: "10751,16",
  });
  assert.deepEqual(vodQueries(chips("a funny movie")).map((x) => x.kind), ["movie"]);
  assert.deepEqual(vodQueries(chips("a short series")).map((x) => x.kind), ["series"]);
  // Two year chips: the route gets their span, the exact years are checked here
  assert.equal(vodQueries(chips("80s or 2010 to 2015"))[0].params.year, "1980-2015");
});

test("genres on the route (any-of, ids of that kind): one mood, kids genres, left-out genres; a kind that can't match is skipped", () => {
  // One mood: its genres; Horror has no TV id, so 'scary' searches films only
  assert.deepEqual(vodQueries(chips("scary")).map((q) => [q.kind, q.params.genreIds]), [["movie", "27"]]);
  // Sci-fi: the movie id for films, the TV one for series
  assert.deepEqual(vodQueries(chips("sci-fi")).map((q) => [q.kind, q.params.genreIds]), [["movie", "878"], ["series", "10765"]]);
  // Ricki's sentence: kids genres minus the left-out ones on the route, Animation / Horror excluded (Horror only for films)
  const r = vodQueries(chips("I'm here with my two kids, want something non animated not scary that would work for all"));
  assert.deepEqual(r.map((q) => [q.kind, q.params.genreIds, q.params.excludeGenreIds]), [["movie", "10751", "16,27"], ["series", "10751,10762", "16"]]);
  // Several moods: none on the route (each must match, checked here)
  assert.equal(vodQueries(chips("funny and scary"))[0].params.genreIds, undefined);
});

test("a person chip: IPTV isn't searched (no cast in the catalogue), and the note says so", () => {
  const c = chipsFromData([{ t: "person", id: "d1", name: "C", role: "Director" }]);
  assert.deepEqual(vodQueries(c), []);
  assert.match(vodNote(c) ?? "", /director or actor/);
});

test("client check: moods, left-out genres, kids genres, exact years, played", () => {
  const ricki = chips("I'm here with my two kids, want something non animated not scary that would work for all");
  assert.ok(vodKeep(v({ genreIds: [G.family, G.adventure] }), ricki));
  assert.ok(!vodKeep(v({ genreIds: [G.family, G.animation] }), ricki), "animated");
  assert.ok(!vodKeep(v({ genreIds: [G.kids, G.horror] }), ricki), "horror");
  assert.ok(!vodKeep(v({ genreIds: [G.drama] }), ricki), "not a kids genre");
  // TV genre ids count too (Sci-Fi & Fantasy for 'sci-fi')
  assert.ok(vodKeep(v({ kind: "series", genreIds: [G["sci-fi & fantasy"]] }), chips("sci-fi")));
  assert.ok(!vodKeep(v({ genreIds: [G.comedy] }), chips("sci-fi")));
  // Exact years inside the span
  const yrs = chips("80s or 2010 to 2015");
  assert.ok(vodKeep(v({ year: 1985 }), yrs));
  assert.ok(!vodKeep(v({ year: 1995 }), yrs));
  assert.ok(!vodKeep(v({ year: null }), yrs));
  assert.ok(!vodKeep(v({ played: true }), chips("unwatched")));
  assert.ok(vodKeep(v({ played: null }), chips("unwatched")));
});

test("kids with no US rating left: no IPTV at all (never an unrated query); lengths the route can't take are dropped", () => {
  assert.deepEqual(vodQueries(chipsFromData([{ t: "kids", ratings: ["U"], genres: ["Family"] }])), []);
  const p = vodQueries(chips("under 2000 hours"))[0].params;
  assert.equal(p.maxRuntime, undefined);
  assert.equal(vodQueries(chips("under 90 minutes"))[0].params.maxRuntime, "90");
});

test("notes: what IPTV can't know", () => {
  assert.match(vodNote(chipsFromData([{ t: "genre", genres: ["Anime"] }])) ?? "", /can't match “Anime”/);
  assert.match(vodNote(chips("for the kids")) ?? "", /without a known age rating/);
  assert.match(vodNote(chips("under 90 minutes")) ?? "", /without a known length/);
  assert.equal(vodNote(chips("funny")), null);
});

test("Ask's IPTV half: checked again, twins of a library pick left out, capped; genreIds parsed from the list route", () => {
  const v = (id: string, tmdbId: number | null, genreIds: number[], kind: "movie" | "series" = "movie") => ({ id, kind, tmdbId, year: 2001, genreIds, played: false });
  const c = chips("not scary");
  const fetched = [v("p:m:1", 1, [35]), v("p:m:2", 2, [27]), v("p:m:3", 3, [35]), v("p:m:1", 1, [35]), v("p:s:4", 3, [35], "series")];
  const r = askIptvPicks(fetched, c, new Set(["movie:3"]), 24);
  // Horror dropped, duplicate id dropped, movie 3 is a library pick (the series with tmdb 3 is a different title)
  assert.deepEqual(r.shown.map((x) => x.id), ["p:m:1", "p:s:4"]);
  assert.equal(r.kept, 3);
  assert.equal(askIptvPicks(fetched, c, new Set(), 1).shown.length, 1);
  assert.deepEqual(parseVodItems([{ id: "abc:m:1", title: "T", kind: "movie", genreIds: [35, "x", 18] }])[0].genreIds, [35, 18]);
  assert.equal(parseVodItems([{ id: "abc:m:1", title: "T", kind: "movie" }])[0].genreIds, undefined);
});
