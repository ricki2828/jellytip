import { test } from "node:test";
import assert from "node:assert/strict";
import { filterChips, isEmpty, parseSearch, withoutFilter } from "../src/lib/search.ts";

const J = "0123456789abcdef0123456789abcdef";

test("parse: groups typed, malformed items dropped, missing groups empty", () => {
  const s = parseSearch({
    q: "90s french crime",
    parsed: { text: "crime", filters: { decade: 1990, country: "FR", genre: null, unwatched: false } },
    groups: [
      { type: "films", items: [{ id: J.toUpperCase(), name: "La Haine", year: 1995, kind: "Movie" }, { id: "x", name: "bad" }] },
      { type: "people", items: [{ personId: J, name: "Vincent Cassel", filmCount: 4 }] },
      { type: "iptv", items: [{ iptvId: "3:m:9", name: "Léon", tech: ["4K", 1], poster: "v1/iptv/img/p", quality: ["1080p"], rating: 8.5 }, { iptvId: "../x", name: "no" }] },
      { type: "discover", items: [{ tmdbId: 11, mediaType: "tv", title: "Engrenages" }] },
      { type: "weird", items: [{}] },
    ],
    techs: { [J]: ["4K"] },
  })!;
  assert.equal(s.films[0].id, J);
  assert.equal(s.films.length, 1);
  assert.equal(s.people[0].filmCount, 4);
  assert.deepEqual(s.iptv, [{ iptvId: "3:m:9", name: "Léon", year: null, kind: "movie", tech: ["4K"], poster: "v1/iptv/img/p", backdrop: null, quality: ["1080p"], rating: 8.5 }]);
  assert.equal(s.discover[0].mediaType, "tv");
  assert.deepEqual(s.shows, []);
  assert.equal(s.text, "crime");
  assert.deepEqual([s.filters.decade, s.filters.country, s.filters.unwatched], [1990, "FR", null]);
  assert.equal(isEmpty(s), false);
  assert.equal(parseSearch({ error: "x" }), null);
  assert.equal(isEmpty(parseSearch({ groups: [] })!), true);
});

test("chips: readable labels for the parsed filters", () => {
  const chips = filterChips({ decade: 1990, country: "FR", genre: "Crime", runtimeMax: 120, unwatched: true, person: "Kubrick", tech: ["ATMOS"] });
  assert.deepEqual(chips.map((c) => c.label), ["90s", "French", "Crime", "under 2h", "Unwatched", "with Kubrick", "Atmos"]);
  assert.deepEqual(filterChips({ decade: 2010 }).map((c) => c.label), ["2010s"]);
  assert.equal(filterChips({ country: "ZZ" })[0].phrase, null);
});

test("removing a chip rebuilds the query from what stays", () => {
  const f = { decade: 1990, country: "FR", genre: "Crime" };
  assert.equal(withoutFilter("heist", f, "country"), "crime heist 1990s");
  assert.equal(withoutFilter("", f, "decade"), "crime french");
  assert.equal(withoutFilter("", { runtimeMax: 90 }, "runtimeMax"), "");
});

test("live shapes: country + language is one chip; year, runtimeMin, kind", () => {
  const f = { decade: 1990, country: "FR", language: "fr", genre: "Crime" };
  assert.deepEqual(filterChips(f).map((c) => c.label), ["90s", "French", "Crime"]);
  assert.equal(withoutFilter("", f, "country"), "crime 1990s");
  assert.deepEqual(filterChips({ language: "ko", year: 2019, runtimeMin: 150, kind: "series" }).map((c) => c.label), ["2019", "Korean", "over 150 min", "Shows"]);
  const s = parseSearch({ parsed: { text: "", filters: { year: 2019, language: "ko", runtimeMin: 150, kind: "series" } }, groups: [] })!;
  assert.deepEqual([s.filters.year, s.filters.language, s.filters.runtimeMin, s.filters.kind], [2019, "ko", 150, "series"]);
});

test("collections: inLibrary as a count or as the parts list", () => {
  const s = parseSearch({ groups: [{ type: "collections", items: [
    { tmdbCollectionId: 645, name: "Bond", inLibrary: [{ itemId: "a" }, { itemId: null }, { itemId: "b" }] },
    { tmdbCollectionId: 9, name: "Other", inLibrary: 2, total: 5 },
    { tmdbCollectionId: 10, name: "None known", inLibrary: [{ itemId: null }, { itemId: null }] },
  ] }] })!;
  assert.deepEqual(s.collections.map((c) => [c.inLibrary, c.total]), [[2, 3], [2, 5], [undefined, 2]]);
});

test("formats: a list (or an old single string); removing one keeps the other", () => {
  const s = parseSearch({ parsed: { text: "", filters: { tech: ["4K", "ATMOS"] } }, groups: [] })!;
  assert.deepEqual(s.filters.tech, ["4K", "ATMOS"]);
  assert.deepEqual(filterChips(s.filters).map((c) => [c.id, c.label]), [["tech:4K", "4K"], ["tech:ATMOS", "Atmos"]]);
  assert.equal(withoutFilter("", s.filters, "tech:4K"), "atmos");
  assert.deepEqual(parseSearch({ parsed: { filters: { tech: "DV" } }, groups: [] })!.filters.tech, ["DV"]);
});

import { parseResults, seasonHint } from "../src/lib/search.ts";

const HEXID = "a".repeat(32);

test("ranked results: parsed, sorted by score, top kept, unusable entries dropped", () => {
  const { results, top } = parseResults(
    [
      { kind: "film", source: "iptv", iptvId: "3:m:9", title: "Léon", score: 5, year: 1994, quality: ["1080p"] },
      { kind: "series", source: "library", id: HEXID.toUpperCase(), title: "The Bear", score: 9, seasons: 3, episodes: 28, season: 2 },
      { kind: "person", source: "library", personId: "b".repeat(32), name: "Jean Reno", score: 7, filmCount: 12 },
      { kind: "film", source: "library", id: "nope", title: "Bad id", score: 99 },
      { kind: "film", source: "discover", tmdbId: 5, title: "Gone", score: 1, posterUrl: "https://x/p.jpg" },
      { kind: "film", source: "discover", title: "No tmdb", score: 1 },
      { kind: "collection", source: "library", title: "Saga", score: 2 },
    ],
    { kind: "film", source: "iptv", iptvId: "3:m:9", title: "Léon", score: 5 },
  );
  assert.deepEqual(results!.map((e) => e.key), [`l:${HEXID}`, `p:${"b".repeat(32)}`, "i:3:m:9", "d:film:5"]);
  assert.equal(top?.key, "i:3:m:9");
  assert.equal(parseResults([], null).top, null);
  assert.equal(parseResults(undefined, null).results, null);
  assert.equal(parseResults([{ kind: "film", source: "iptv", iptvId: "3:m:9", title: "A", score: 1 }], null).top?.title, "A");
});

test("season hint: the named season, else the counts", () => {
  assert.equal(seasonHint({ kind: "series", season: 2, episode: 3 }), "S2 E3");
  assert.equal(seasonHint({ kind: "series", season: 2 }), "Season 2");
  assert.equal(seasonHint({ kind: "series", seasons: 3, episodes: 28 }), "3 seasons · 28 episodes");
  assert.equal(seasonHint({ kind: "film", seasons: 3 }), null);
});

import { livePill, parseChannelHits, parseLiveHits } from "../src/lib/search.ts";

const LH = (o: Record<string, unknown>) => ({ title: "Black Caps v India", start: 1000, stop: 9000, channel: { id: "3:77", name: "Sky Sport 1", num: 5, logo: "v1/iptv/img/x", quality: { bucket: "fhd" } }, ...o });

test("live search rows: parse hits and channels, sport query, pills", () => {
  const hits = parseLiveHits([LH({}), LH({}), LH({ title: "", start: 5 }), LH({ channel: { id: "bad" } }), LH({ replay: true, start: 2000, alternateChannels: [{ id: "3:78", name: "Sky 2" }, { id: "x" }] })]);
  assert.equal(hits.length, 2);
  assert.equal(hits[1].replay, true);
  assert.deepEqual(hits[1].alternates, [{ id: "3:78", name: "Sky 2" }]);
  assert.equal(parseLiveHits(Array.from({ length: 40 }, (_, i) => LH({ start: i }))).length, 30);
  const ch = parseChannelHits([{ id: "3:9", name: "ESPN", group: "US", now: { title: "SportsCenter", phase: "live" } }, { id: "3:9", name: "dup" }, { name: "no id" }]);
  assert.equal(ch.length, 1);
  assert.equal(ch[0].now?.title, "SportsCenter");
  // as groups, with the sport filter making it a sport query
  const r = parseSearch({ groups: [{ type: "live", items: [LH({})] }, { type: "channels", items: [{ id: "3:9", name: "ESPN" }] }], parsed: { filters: { sport: "cricket" } } })!;
  assert.equal(r.live?.length, 1);
  assert.equal(r.channels?.length, 1);
  assert.equal(r.sportQuery, true);
  // absent groups mean absent rows
  const none = parseSearch({ groups: [] })!;
  assert.equal(none.live, null);
  assert.equal(none.channels, null);
  assert.equal(none.sportQuery, false);
  assert.equal(isEmpty(r), false);
  // top-level lists work too
  assert.equal(parseSearch({ groups: [], live: [LH({})] })!.live?.length, 1);
  const now = 5000;
  assert.deepEqual(livePill(hits[0], now), { text: "LIVE", tone: "rec" });
  assert.equal(livePill(hits[1], now).text, "REPLAY");
  assert.equal(livePill(parseLiveHits([LH({ start: 4000 + 3000 * 1, stop: 20000, startsAt: new Date((now + 600) * 1000).toISOString(), phase: "pre" })])[0], now).tone, "pre");
  assert.equal(livePill(parseLiveHits([LH({ start: now + 90_000, stop: now + 100_000 })])[0], now).tone, "time");
});
