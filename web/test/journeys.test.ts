import { test } from "node:test";
import assert from "node:assert/strict";
import {
  buildEntries, buildJourney, createJourneyCache, directorQuery, type BuildDeps, type BuildResult, directorDef, franchiseDef, journeyId, journeyTitle, listDef, nextUp, orderByRelease, parseJourneyId, progress, summaries,
  DIRECTOR_CAP, LIST_CAP, type JourneyDef,
} from "../src/lib/journeys.ts";
import { parseHash } from "../src/lib/routeParse.ts";
import { parseHub } from "../src/lib/collections.ts";

const id = (n: number) => n.toString(16).padStart(32, "0");

test("franchise: ordered by release year, ties and unknown years keep series order, missing parts stay flagged", () => {
  const d = franchiseDef(10, {
    name: "Alien",
    partsOrder: [
      { title: "C", year: 1992, itemId: "c" },
      { title: "A", year: 1979, itemId: "a" },
      { title: "X", year: null, itemId: null },
      { title: "B", year: 1986, itemId: null },
      { title: "B2", year: 1986, itemId: "b2" },
    ],
  })!;
  assert.deepEqual(d.parts.map((p) => p.title), ["A", "B", "X", "B2", "C"]); // X (no year) keeps its companion slot
  assert.equal(d.parts[1].itemId, null);
  assert.equal(journeyTitle(d), "Alien in order · 5 films");
});

test("franchise with fewer than 2 films in the library is no journey", () => {
  assert.equal(franchiseDef(1, { name: "F", partsOrder: [{ title: "a", itemId: "a" }, { title: "b", itemId: null }] }), null);
});

test("missing parts are skipped by next-up and not playable; virtual placeholders count as missing", () => {
  const d = franchiseDef(1, { name: "F", partsOrder: [{ title: "a", year: 1, itemId: "a" }, { title: "b", year: 2, itemId: "b" }, { title: "c", year: 3, itemId: "c" }] })!;
  const e = buildEntries(d, [
    { Id: "a", Name: "A", UserData: { Played: true } },
    { Id: "b", Name: "B", LocationType: "Virtual" },
    { Id: "c", Name: "C" },
  ]);
  assert.deepEqual(e.map((x) => x.playable), [true, false, true]);
  assert.equal(nextUp(e)!.entry.itemId, "c");
  assert.deepEqual(progress(e), { watched: 1, playable: 2, total: 3 });
});

test("next-up is the first unwatched playable; resume only when it is part-watched; null when all watched", () => {
  const d: JourneyDef = { id: "l:x", kind: "list", name: "X", parts: ["a", "b", "c"].map((itemId) => ({ itemId, title: "", year: null })) };
  let e = buildEntries(d, [
    { Id: "a", Name: "A", UserData: { Played: true } },
    { Id: "b", Name: "B", UserData: { PlaybackPositionTicks: 500 } },
    { Id: "c", Name: "C", UserData: { PlaybackPositionTicks: 900 } },
  ]);
  const n = nextUp(e)!;
  assert.equal(n.entry.itemId, "b");
  assert.equal(n.resume, true);
  e = buildEntries(d, [{ Id: "a", UserData: { Played: true } }, { Id: "b", UserData: { Played: true } }, { Id: "c", UserData: { Played: true } }]);
  assert.equal(nextUp(e), null);
  e = buildEntries(d, [{ Id: "a" }, { Id: "b" }, { Id: "c" }]);
  assert.equal(nextUp(e)!.resume, false);
});

test("item ids match with or without dashes, any case", () => {
  const d: JourneyDef = { id: "l:x", kind: "list", name: "X", parts: [{ itemId: "ABCD", title: "", year: null }] };
  assert.equal(buildEntries(d, [{ Id: "ab-cd", Name: "T" }])[0].playable, true);
});

test("director: needs 4 films, chronological, capped at 6, de-duplicated, virtual excluded", () => {
  const film = (n: number, y?: number, extra = {}) => ({ Id: id(n), Name: `F${n}`, ProductionYear: y, ...extra });
  assert.equal(directorDef(id(1), "Hitchcock", [film(1, 1950), film(2, 1951), film(3, 1952)]), null);
  assert.equal(directorDef(id(1), "Hitchcock", [film(1, 1950), film(2, 1951), film(3, 1952), film(4, 1953, { LocationType: "Virtual" })]), null);
  assert.equal(directorDef(id(1), "Hitchcock", [film(1, 1950), film(2, 1951), film(3, 1952), film(3, 1952)]), null);
  const many = Array.from({ length: 9 }, (_, i) => film(i + 1, 2000 - i));
  const d = directorDef(id(1), "Hitchcock", many)!;
  assert.equal(d.parts.length, DIRECTOR_CAP);
  assert.deepEqual(d.parts.map((p) => p.year), [1992, 1993, 1994, 1995, 1996, 1997]);
  assert.equal(journeyTitle(d), "Hitchcock: earliest 6 of 9 films");
  assert.equal(directorDef(id(1), "H", many.slice(0, 6))!.parts.length, 6);
  assert.equal(journeyTitle(directorDef(id(1), "H", many.slice(0, 5))!), "H in order · 5 films");
  assert.equal(journeyTitle(directorDef(id(1), "H", many.slice(0, 6), 14)!), "H: earliest 6 of 14 films");
});

test("list: list order kept, duplicates dropped, capped at 8; one film is no journey", () => {
  const ids = Array.from({ length: 12 }, (_, i) => `i${i}`);
  const d = listDef({ slug: "best", name: "Best", itemIds: ["i0", "i0", ...ids] })!;
  assert.equal(d.parts.length, LIST_CAP);
  assert.deepEqual(d.parts.map((p) => p.itemId), ids.slice(0, 8));
  assert.equal(listDef({ slug: "s", name: "S", itemIds: ["a", "a"] }), null);
  assert.equal(journeyTitle(d), "Best: first 8 of 12 films");
});

test("journeys page: directors under 4 films and TV are dropped, groups capped and biggest first, lists need 2 films", () => {
  const card = (cid: string, title: string, libCount: number, person?: string, mediaType = "movie") => ({ id: cid, title, total: libCount, libCount, iptvCount: 0, mediaType, ...(person ? { art: { person: { id: person } } } : {}) });
  const hub = parseHub({
    sections: [
      { key: "franchises", title: "F", items: [card("tmdb:1", "Small", 2), card("tmdb:2", "Big", 9), card("tmdb:3", "One", 1), card("tmdb:4", "Show", 5, undefined, "tv"), card("weird", "W", 5)] },
      { key: "directors", title: "D", items: [card("dir:1", "Few", 3, id(1)), card("dir:2", "Lots", 20, id(2)), card("dir:3", "Four", 4, id(3)), card("dir:4", "NoPerson", 8)] },
    ],
  });
  const g = summaries(hub, [{ slug: "a", name: "A", itemIds: ["1", "2", "3"] }, { slug: "b", name: "B", itemIds: ["1"] }, { slug: "bad slug", name: "C", itemIds: ["1", "2"] }]);
  assert.deepEqual(g.franchise.map((s) => s.title), ["Big in order · 9 films", "Small in order · 2 films"]);
  assert.deepEqual(g.director.map((s) => [s.kicker, s.title]), [["Director", "Lots"], ["Director", "Four"]]); // no film count on director cards
  assert.deepEqual(g.list.map((s) => s.id), ["l:a"]);
  assert.deepEqual(summaries(null, null), { franchise: [], director: [], list: [] });
});

test("summary caps bound the number of journeys listed", () => {
  const items = Array.from({ length: 40 }, (_, i) => ({ id: `tmdb:${i + 1}`, title: `F${i}`, total: 5, libCount: 5, iptvCount: 0 }));
  const lists = Array.from({ length: 40 }, (_, i) => ({ slug: `s${i}`, name: `L${i}`, itemIds: ["a", "b"] }));
  const g = summaries(parseHub({ sections: [{ key: "franchises", title: "F", items }] }), lists);
  assert.equal(g.franchise.length, 12);
  assert.equal(g.list.length, 10);
});

test("ids round-trip and reject anything that could escape a path", () => {
  assert.deepEqual(parseJourneyId(journeyId("franchise", "10")), { kind: "franchise", key: "10" });
  assert.deepEqual(parseJourneyId(journeyId("director", id(5).toUpperCase())), { kind: "director", key: id(5) });
  assert.deepEqual(parseJourneyId("l:best-of_2020"), { kind: "list", key: "best-of_2020" });
  assert.deepEqual(parseJourneyId("F:10"), { kind: "franchise", key: "10" });
  assert.deepEqual(parseJourneyId("D:" + id(5).replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, "$1-$2-$3-$4-$5")), { kind: "director", key: id(5) });
  for (const bad of ["f:../x", "d:123", "x:1", "l:", "f:1/2", "f:abc", "l:a b"]) assert.equal(parseJourneyId(bad), null, bad);
});

test("routes: #/journeys and #/journeys/<id> (percent-encoded colon)", () => {
  assert.equal(parseHash("#/journeys").name, "journeys");
  const r = parseHash("#/journeys/f%3A10");
  assert.deepEqual([r.name, r.params.id], ["journey", "f:10"]);
  assert.equal(parseHash("#/journeys/f%3A..%2Fx").name, "journeys");
  assert.equal(parseHash("#/journeys/d%3A" + id(5).replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, "$1-$2-$3-$4-$5")).name, "journey");
  assert.equal(parseHash("#/journeys/%E0%A4%A").name, "journeys");
});

test("results are cached per user and cleared on session change; errors are never cached", async () => {
  const c = createJourneyCache(() => 0);
  const ok = (name: string): BuildResult => ({ kind: "ok", def: { id: "l:x", kind: "list", name, parts: [] } });
  const nm = (r: BuildResult) => (r.kind === "ok" ? r.def.name : r.kind);
  assert.equal(nm(await c.get("a", "l:x", async () => ok("A"))), "A");
  assert.equal(nm(await c.get("b", "l:x", async () => ok("B"))), "B");
  assert.equal(nm(await c.get("a", "l:x", async () => ok("again"))), "A");
  c.clear();
  assert.equal(nm(await c.get("a", "l:x", async () => ok("fresh"))), "fresh");
  assert.equal(nm(await c.get("a", "e", async () => { throw new Error("x"); })), "error");
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(nm(await c.get("a", "e", async () => ok("recovered"))), "recovered");
  assert.equal(nm(await c.get("a", "n", async () => ({ kind: "none" }))), "none"); // "not found" is cached
});

// ---------- build() with injected fetchers ----------

const calls: string[] = [];
const deps = (over: Partial<BuildDeps> = {}): BuildDeps => ({
  collectionPage: async () => ({ name: "Alien", partsOrder: [{ title: "a", year: 1979, itemId: "a" }, { title: "b", year: 1986, itemId: "b" }, { title: "c", year: 1992, itemId: null }] }),
  directorFilms: async (p) => (calls.push(`films:${p}`), { items: Array.from({ length: 5 }, (_, i) => ({ Id: id(i + 1), Name: `F${i}`, ProductionYear: 1950 + i })) }),
  personName: async () => "Hitchcock",
  list: async (slug) => (slug === "best" ? { slug, name: "Best", itemIds: ["a", "b", "c"] } : null),
  ...over,
});

test("director query: credited as Director, movies, oldest first, bounded, one call", async () => {
  assert.deepEqual(directorQuery(id(7)), { PersonIds: id(7), PersonTypes: "Director", Recursive: true, IncludeItemTypes: "Movie", SortBy: "PremiereDate,SortName", SortOrder: "Ascending", Limit: 60, Fields: "" });
  calls.length = 0;
  const r = await buildJourney("d:" + id(7), deps());
  assert.deepEqual(calls, ["films:" + id(7)]);
  assert.equal(r.kind === "ok" && journeyTitle(r.def), "Hitchcock in order · 5 films");
});

test("director with a truncated result reports the library total from the server", async () => {
  const items = Array.from({ length: 60 }, (_, i) => ({ Id: id(i + 1), Name: `F${i}`, ProductionYear: 1900 + i }));
  const r = await buildJourney("d:" + id(7), deps({ directorFilms: async () => ({ items, total: 75 }) }));
  assert.equal(r.kind === "ok" && journeyTitle(r.def), "Hitchcock: earliest 6 of 75 films");
});

test("director with under 4 playable films is 'short', not 'none'", async () => {
  const r = await buildJourney("d:" + id(7), deps({ directorFilms: async () => ({ items: [{ Id: id(1) }, { Id: id(2) }, { Id: id(3) }, { Id: id(4), LocationType: "Virtual" }] }) }));
  assert.deepEqual(r, { kind: "short", personId: id(7), films: 3 });
});

test("franchise path builds from the companion page; a 404 is 'none'", async () => {
  const r = await buildJourney("f:10", deps());
  assert.equal(r.kind === "ok" && r.def.parts.length, 3);
  assert.deepEqual(await buildJourney("f:10", deps({ collectionPage: async () => null })), { kind: "none" });
});

test("a failed fetch is 'error', an empty result is 'none' or 'short'", async () => {
  const boom = async () => { throw new Error("net"); };
  assert.deepEqual(await buildJourney("f:10", deps({ collectionPage: boom })), { kind: "error" });
  assert.deepEqual(await buildJourney("d:" + id(7), deps({ directorFilms: boom })), { kind: "error" });
  assert.deepEqual(await buildJourney("d:" + id(7), deps({ personName: boom })), { kind: "error" });
  assert.deepEqual(await buildJourney("l:best", deps({ list: boom })), { kind: "error" });
  assert.deepEqual(await buildJourney("l:nope", deps()), { kind: "none" });
  assert.deepEqual((await buildJourney("d:" + id(7), deps({ directorFilms: async () => ({ items: [] }) }))).kind, "short");
  assert.deepEqual(await buildJourney("garbage", deps()), { kind: "none" });
});

test("orderByRelease is stable", () => {
  assert.deepEqual(orderByRelease([{ year: 2, k: "a" }, { year: 1, k: "b" }, { year: 2, k: "c" }]).map((x) => x.k), ["b", "a", "c"]);
});
