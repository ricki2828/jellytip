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
