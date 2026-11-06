import { test } from "node:test";
import assert from "node:assert/strict";
import {
  buildEntries, createJourneyCache, directorDef, franchiseDef, journeyId, journeyTitle, listDef, nextUp, orderByRelease, parseJourneyId, progress, summaries,
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
  assert.deepEqual(d.parts.map((p) => p.title), ["A", "B", "B2", "C", "X"]);
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
  assert.equal(journeyTitle(d), "Hitchcock in order · 6 films");
});

test("list: list order kept, duplicates dropped, capped at 8; one film is no journey", () => {
  const ids = Array.from({ length: 12 }, (_, i) => `i${i}`);
  const d = listDef({ slug: "best", name: "Best", itemIds: ["i0", "i0", ...ids] })!;
  assert.equal(d.parts.length, LIST_CAP);
  assert.deepEqual(d.parts.map((p) => p.itemId), ids.slice(0, 8));
  assert.equal(listDef({ slug: "s", name: "S", itemIds: ["a", "a"] }), null);
  assert.equal(journeyTitle(d), "Best · 8 films");
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
  assert.deepEqual(g.director.map((s) => [s.title, s.films]), [["Lots in order · 6 films", 6], ["Four in order · 4 films", 4]]);
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
  for (const bad of ["f:../x", "d:123", "x:1", "l:", "f:1/2", "f:abc", "l:a b"]) assert.equal(parseJourneyId(bad), null, bad);
});

test("routes: #/journeys and #/journeys/<id> (percent-encoded colon)", () => {
  assert.equal(parseHash("#/journeys").name, "journeys");
  const r = parseHash("#/journeys/f%3A10");
  assert.deepEqual([r.name, r.params.id], ["journey", "f:10"]);
  assert.equal(parseHash("#/journeys/f%3A..%2Fx").name, "journeys");
  assert.equal(parseHash("#/journeys/%E0%A4%A").name, "journeys");
});

test("definitions are cached per user and cleared on session change", async () => {
  const c = createJourneyCache(() => 0);
  const def = (name: string): JourneyDef => ({ id: "l:x", kind: "list", name, parts: [] });
  assert.equal((await c.get("a", "l:x", async () => def("A")))!.name, "A");
  assert.equal((await c.get("b", "l:x", async () => def("B")))!.name, "B");
  assert.equal((await c.get("a", "l:x", async () => def("again")))!.name, "A");
  c.clear();
  assert.equal((await c.get("a", "l:x", async () => def("fresh")))!.name, "fresh");
});

test("orderByRelease is stable", () => {
  assert.deepEqual(orderByRelease([{ year: 2, k: "a" }, { year: 1, k: "b" }, { year: 2, k: "c" }]).map((x) => x.k), ["b", "a", "c"]);
});
