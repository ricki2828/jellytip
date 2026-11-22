import { test } from "node:test";
import assert from "node:assert/strict";
import { chipsFromData, parseAsk } from "../src/lib/askParse.ts";
import { TMDB_GENRES, vodKeep, vodNote, vodQueries, VOD_LIMIT, type VodView } from "../src/lib/askVod.ts";

const NOW = new Date("2026-11-06T00:00:00Z");
const chips = (q: string) => parseAsk(q, NOW).chips;
const v = (o: Partial<VodView>): VodView => ({ kind: "movie", year: 2000, genreIds: [], certification: null, runtime: null, played: null, ...o });
const G = TMDB_GENRES;

test("one IPTV query per kind; the route gets ratings, length, rating floor and the year span", () => {
  const q = vodQueries(chips("for the kids, under 2 hours, well rated 90s"));
  assert.deepEqual(q.map((x) => x.kind), ["movie", "series"]);
  assert.deepEqual(q[0].params, {
    kind: "movie", limit: String(VOD_LIMIT), sort: "rating", certifications: "G|PG|TV-Y|TV-Y7|TV-G|TV-PG",
    maxRuntime: "120", minRating: "7", year: "1990-1999",
  });
  assert.deepEqual(vodQueries(chips("a funny movie")).map((x) => x.kind), ["movie"]);
  assert.deepEqual(vodQueries(chips("a short series")).map((x) => x.kind), ["series"]);
  // Two year chips: the route gets their span, the exact years are checked here
  assert.equal(vodQueries(chips("80s or 2010 to 2015"))[0].params.year, "1980-2015");
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
