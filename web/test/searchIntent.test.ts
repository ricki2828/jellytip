import { test } from "node:test";
import assert from "node:assert/strict";
import { hasFilters, jellyfinParams, parseIntent, serverQuery } from "../src/lib/searchIntent.ts";

test("watch / play puts the title first and flags play", () => {
  for (const [q, text] of [["watch Heat", "Heat"], ["Play The Office", "The Office"], ["stream Dune", "Dune"], ["put on Alien", "Alien"]] as const) {
    const i = parseIntent(q);
    assert.equal(i.play, true, q);
    assert.equal(i.text, text, q);
  }
  assert.equal(parseIntent("Playtime").play, false);
  assert.equal(parseIntent("Watchmen").play, false);
  assert.equal(serverQuery(parseIntent("watch Heat")), "Heat");
});

test("season and episode forms", () => {
  const cases: [string, string, number, number | null][] = [
    ["breaking bad s2e5", "breaking bad", 2, 5],
    ["Breaking Bad S02E05", "Breaking Bad", 2, 5],
    ["Succession season 3", "Succession", 3, null],
    ["lost 3x04", "lost", 3, 4],
    ["The Office s2", "The Office", 2, null],
    ["season 4 episode 2 fargo", "fargo", 4, 2],
  ];
  for (const [q, text, s, e] of cases) {
    const i = parseIntent(q);
    assert.deepEqual([i.text, i.season, i.episode], [text, s, e], q);
  }
  for (const q of ["Se7en", "S1mone", "Seasons", "Sex Education"]) assert.equal(parseIntent(q).season, null, q);
});

test("person intents: strong with 'with/by', weak with 'films'", () => {
  assert.deepEqual(parseIntent("movies with Tom Hanks").person, { name: "Tom Hanks", strong: true });
  assert.deepEqual(parseIntent("films starring Meryl Streep").person, { name: "Meryl Streep", strong: true });
  assert.deepEqual(parseIntent("directed by Nolan").person, { name: "Nolan", strong: true });
  assert.deepEqual(parseIntent("movies by Kubrick").person, { name: "Kubrick", strong: true });
  assert.deepEqual(parseIntent("Tom Hanks films").person, { name: "Tom Hanks", strong: false });
  assert.equal(parseIntent("Tom Hanks films").text, "Tom Hanks");
  // genre / decade + films is not a person
  assert.equal(parseIntent("action films").person, null);
  assert.equal(parseIntent("90s movies").person, null);
});

test("live", () => {
  assert.equal(parseIntent("live arsenal").live, true);
  assert.equal(parseIntent("live arsenal").text, "arsenal");
  assert.equal(parseIntent("sky sports live").live, true);
  assert.equal(parseIntent("channel BBC One").text, "BBC One");
  assert.equal(parseIntent("Alive").live, false);
  assert.equal(parseIntent("Live and Let Die").live, true); // known, harmless: the server still returns the film
});

test("years, decades, genre and kind", () => {
  const a = parseIntent("90s thrillers");
  assert.deepEqual([a.years, a.genre, a.text], [{ from: 1990, to: 1999 }, "Thriller", ""]);
  assert.deepEqual(parseIntent("2019").years, { from: 2019, to: 2019 });
  assert.deepEqual(parseIntent("1980s horror movies").years, { from: 1980, to: 1989 });
  assert.equal(parseIntent("1980s horror movies").kind, "movie");
  assert.deepEqual(parseIntent("'00s comedies").years, { from: 2000, to: 2009 });
  assert.deepEqual(parseIntent("2010s drama shows").years, { from: 2010, to: 2019 });
  assert.equal(parseIntent("2010s drama shows").kind, "series");
  assert.deepEqual(parseIntent("heat 1995").years, { from: 1995, to: 1995 });
  assert.equal(parseIntent("heat 1995").text, "heat");
  // a bare genre word may be a title
  const t = parseIntent("thriller");
  assert.deepEqual([t.genre, t.text], [null, "thriller"]);
  assert.equal(parseIntent("action films").genre, "Action");
  assert.equal(parseIntent("blade runner 2049").years, null);
});

test("jellyfin params carry Years and Genres server-side", () => {
  const p = jellyfinParams(parseIntent("90s thrillers"));
  assert.equal(p.Years, "1990,1991,1992,1993,1994,1995,1996,1997,1998,1999");
  assert.equal(p.Genres, "Thriller");
  assert.equal(p.SearchTerm, undefined);
  assert.equal(p.SortBy, "CommunityRating,SortName");
  const q = jellyfinParams(parseIntent("heat 1995"));
  assert.deepEqual([q.Years, q.SearchTerm], ["1995", "heat"]);
  assert.equal(jellyfinParams(parseIntent("2010s drama shows")).IncludeItemTypes, "Series");
  assert.equal(hasFilters(parseIntent("heat")), false);
  assert.equal(hasFilters(parseIntent("2019")), true);
});

test("plain titles are untouched", () => {
  for (const q of ["Heat", "The Godfather", "Alien", "Seven"]) {
    const i = parseIntent(q);
    assert.deepEqual([i.text, i.play, i.live, i.season, i.person, i.years, i.genre], [q, false, false, null, null, null, null], q);
  }
});
