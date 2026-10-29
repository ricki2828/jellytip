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
      { type: "iptv", items: [{ iptvId: "3:m:9", name: "Léon", tech: ["4K", 1] }, { iptvId: "../x", name: "no" }] },
      { type: "discover", items: [{ tmdbId: 11, mediaType: "tv", title: "Engrenages" }] },
      { type: "weird", items: [{}] },
    ],
    techs: { [J]: ["4K"] },
  })!;
  assert.equal(s.films[0].id, J);
  assert.equal(s.films.length, 1);
  assert.equal(s.people[0].filmCount, 4);
  assert.deepEqual(s.iptv, [{ iptvId: "3:m:9", name: "Léon", year: null, kind: "movie", tech: ["4K"] }]);
  assert.equal(s.discover[0].mediaType, "tv");
  assert.deepEqual(s.shows, []);
  assert.equal(s.text, "crime");
  assert.deepEqual([s.filters.decade, s.filters.country, s.filters.unwatched], [1990, "FR", null]);
  assert.equal(isEmpty(s), false);
  assert.equal(parseSearch({ error: "x" }), null);
  assert.equal(isEmpty(parseSearch({ groups: [] })!), true);
});

test("chips: readable labels for the parsed filters", () => {
  const chips = filterChips({ decade: 1990, country: "FR", genre: "Crime", runtimeMax: 120, unwatched: true, person: "Kubrick", tech: "ATMOS" });
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
