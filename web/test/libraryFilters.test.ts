import { test } from "node:test";
import assert from "node:assert/strict";
import { activeChips, clearAll, DEFAULT_FILTERS, effectiveDir, filtersFromParams, filtersToParams, flipDir, isFiltered, loadFilters, needsClientFilter, parseFacets, parseFilters, parseSearchItems, removeChip, runtimeOk, saveFilters, setSort, SORTS, stateKey, toQuery, type LibraryFilters, type StorageLike } from "../src/lib/libraryFilters.ts";

const mem = (): StorageLike & { data: Map<string, string> } => {
  const data = new Map<string, string>();
  return { data, getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v), removeItem: (k) => void data.delete(k) };
};
const full: LibraryFilters = { ...DEFAULT_FILTERS, sort: "rating", genres: ["Action", "Sci-Fi"], years: [2021, 1999], ratings: ["R"], minRating: 7, watched: "unplayed", favorite: true };

test("default filters send no filter params", () => {
  const q = toQuery("movie", DEFAULT_FILTERS, 0);
  assert.equal(q.IncludeItemTypes, "Movie");
  assert.equal(q.Limit, 60);
  for (const k of ["Genres", "Years", "OfficialRatings", "MinCommunityRating", "Filters"]) assert.equal(q[k], undefined, k);
});

test("filters map to Jellyfin Items params", () => {
  const q = toQuery("tv", full, 120);
  assert.equal(q.IncludeItemTypes, "Series");
  assert.equal(q.Genres, "Action|Sci-Fi");
  assert.equal(q.Years, "1999,2021");
  assert.equal(q.OfficialRatings, "R");
  assert.equal(q.MinCommunityRating, 7);
  assert.equal(q.Filters, "IsUnplayed,IsFavorite");
  assert.equal(q.SortBy, "CommunityRating,SortName");
  assert.equal(q.StartIndex, 120);
});

test("watched maps to IsPlayed and random ignores paging", () => {
  assert.equal(toQuery("movie", { ...DEFAULT_FILTERS, watched: "played" }, 0).Filters, "IsPlayed");
  const r = toQuery("movie", { ...DEFAULT_FILTERS, sort: "random" }, 600);
  assert.equal(r.StartIndex, 0);
  assert.equal(r.Limit, 2000);
});

test("chips list every active filter, remove one, clear all keeps the sort", () => {
  assert.equal(activeChips(full).length, 2 + 2 + 1 + 1 + 1 + 1);
  assert.equal(activeChips(DEFAULT_FILTERS).length, 0);
  const less = removeChip(full, "g:Action");
  assert.deepEqual(less.genres, ["Sci-Fi"]);
  assert.equal(removeChip(full, "min").minRating, null);
  assert.equal(removeChip(full, "w").watched, "all");
  const cleared = clearAll(full);
  assert.equal(isFiltered(cleared), false);
  assert.equal(cleared.sort, "rating");
});

test("state round-trips through storage and bad data falls back", () => {
  const s = mem();
  saveFilters(s, "u1", "movie", full);
  assert.deepEqual(loadFilters(s, "u1", "movie"), full);
  assert.deepEqual(loadFilters(s, "u1", "tv"), DEFAULT_FILTERS);
  s.setItem(stateKey("u1", "tv")!, "{not json");
  assert.deepEqual(loadFilters(s, "u1", "tv"), DEFAULT_FILTERS);
  const p = parseFilters({ sort: "nope", genres: ["A", 3, "A"], years: ["x", 2000], minRating: "9", watched: "weird", favorite: "yes" });
  assert.deepEqual(p, { ...DEFAULT_FILTERS, genres: ["A"], years: [2000] });
});

test("saved state is scoped per user and never read without one", () => {
  const s = mem();
  saveFilters(s, "alice", "movie", full);
  assert.deepEqual(loadFilters(s, "bob", "movie"), DEFAULT_FILTERS);
  saveFilters(s, "", "movie", full);
  assert.equal(s.data.size, 1);
  assert.deepEqual(loadFilters(s, "", "movie"), DEFAULT_FILTERS);
  assert.equal(loadFilters(null, "alice", "movie").sort, "added");
});

test("facets are parsed defensively", () => {
  const f = parseFacets({ Genres: ["Drama", "Action", 4], Years: [1999, 2021, 2021, "x"], OfficialRatings: ["R", "PG"] });
  assert.deepEqual(f, { genres: ["Action", "Drama"], years: [2021, 1999], ratings: ["PG", "R"] });
  assert.deepEqual(parseFacets(null), { genres: [], years: [], ratings: [] });
});

const ID1 = "a".repeat(32);
const ID2 = "b".repeat(32);
const sortOf = (key: string, dir: LibraryFilters["dir"] = null) => toQuery("movie", { ...DEFAULT_FILTERS, sort: key, dir }, 0);

test("every sort maps to a Jellyfin ItemSortBy field with its default direction", () => {
  const want: Record<string, [string, string]> = {
    added: ["DateCreated,SortName", "Descending,Ascending"],
    az: ["SortName", "Ascending"],
    release: ["PremiereDate,SortName", "Descending,Ascending"],
    rating: ["CommunityRating,SortName", "Descending,Ascending"],
    critic: ["CriticRating,SortName", "Descending,Ascending"],
    runtime: ["Runtime,SortName", "Descending,Ascending"],
    plays: ["PlayCount,SortName", "Descending,Ascending"],
    watched: ["DatePlayed,SortName", "Descending,Ascending"],
    random: ["Random", "Ascending"],
  };
  assert.deepEqual(SORTS.map((s) => s.key).sort(), Object.keys(want).sort());
  for (const [k, [by, order]] of Object.entries(want)) {
    const q = sortOf(k);
    assert.equal(q.SortBy, by, k);
    assert.equal(q.SortOrder, order, k);
  }
});

test("direction toggle: oldest first, shortest first, date added ascending, A-Z reversed; random ignores it", () => {
  assert.equal(sortOf("release", "asc").SortOrder, "Ascending,Ascending");
  assert.equal(sortOf("runtime", "asc").SortOrder, "Ascending,Ascending");
  assert.equal(sortOf("added", "asc").SortOrder, "Ascending,Ascending");
  assert.equal(sortOf("az", "desc").SortOrder, "Descending");
  assert.equal(sortOf("random", "desc").SortOrder, "Ascending");
  const f = setSort(DEFAULT_FILTERS, "release");
  assert.equal(effectiveDir(f), "desc");
  const flipped = flipDir(f);
  assert.equal(flipped.dir, "asc");
  assert.equal(flipDir(flipped).dir, null); // back to the default is stored as null
  assert.equal(setSort(flipped, "az").dir, null); // a new sort starts in its own default direction
});

test("decades expand to every year and merge with explicit years", () => {
  assert.equal(toQuery("movie", { ...DEFAULT_FILTERS, decades: [1990] }, 0).Years, "1990,1991,1992,1993,1994,1995,1996,1997,1998,1999");
  const q = toQuery("movie", { ...DEFAULT_FILTERS, decades: [2010], years: [1985, 2012] }, 0).Years as string;
  assert.equal(q.split(",").length, 11);
  assert.ok(q.startsWith("1985,2010") && q.endsWith("2019"));
});

test("4K / HD / subtitles / studio / person map to Items params", () => {
  const q = toQuery("movie", { ...DEFAULT_FILTERS, res: "4k", subs: true, studios: [{ id: ID1, name: "A24" }, { id: ID2, name: "Neon" }], people: [{ id: ID2, name: "Greta Gerwig" }] }, 0);
  assert.equal(q.Is4K, true);
  assert.equal(q.IsHD, undefined);
  assert.equal(q.HasSubtitles, true);
  assert.equal(q.StudioIds, `${ID1}|${ID2}`);
  assert.equal(q.PersonIds, ID2);
  assert.equal(toQuery("movie", { ...DEFAULT_FILTERS, res: "hd" }, 0).IsHD, true);
});

test("runtime bands are client-side only (no Items param), movies only", () => {
  const f = { ...DEFAULT_FILTERS, runtime: "short" as const };
  const q = toQuery("movie", f, 0);
  assert.equal(Object.keys(q).some((k) => /runtime/i.test(k) && k !== "SortBy"), false);
  assert.equal(needsClientFilter("movie", f), true);
  assert.equal(needsClientFilter("tv", f), false);
  const min = (m: number) => m * 600_000_000;
  assert.equal(runtimeOk("short", min(89)), true);
  assert.equal(runtimeOk("short", min(90)), false);
  assert.equal(runtimeOk("mid", min(90)), true);
  assert.equal(runtimeOk("mid", min(120)), true);
  assert.equal(runtimeOk("mid", min(121)), false);
  assert.equal(runtimeOk("long", min(121)), true);
  assert.equal(runtimeOk("long", undefined), false);
  assert.equal(runtimeOk("", undefined), true);
});

test("new filters show as chips, remove one by one, and Clear all keeps sort + direction", () => {
  const f: LibraryFilters = { ...DEFAULT_FILTERS, sort: "release", dir: "asc", decades: [1990], runtime: "mid", res: "4k", subs: true, studios: [{ id: ID1, name: "A24" }], people: [{ id: ID2, name: "Greta Gerwig" }] };
  assert.deepEqual(activeChips(f).map((c) => c.label), ["1990s", "90–120 min", "4K", "Has subtitles", "A24", "Greta Gerwig"]);
  assert.equal(isFiltered(f), true);
  for (const c of activeChips(f)) assert.equal(activeChips(removeChip(f, c.id)).length, 5, c.id);
  const cleared = clearAll(f);
  assert.equal(isFiltered(cleared), false);
  assert.equal(cleared.sort, "release");
  assert.equal(cleared.dir, "asc");
});

test("URL round-trip keeps every filter; no filter keys means null", () => {
  const f: LibraryFilters = { ...DEFAULT_FILTERS, sort: "runtime", dir: "asc", genres: ["Sci-Fi", "Drama"], years: [1999], decades: [1980, 2010], ratings: ["PG-13"], minRating: 7, watched: "unplayed", favorite: true, runtime: "long", res: "hd", subs: true, studios: [{ id: ID1, name: "A24" }], people: [{ id: ID2, name: "Greta Gerwig" }] };
  const back = filtersFromParams(new URLSearchParams(filtersToParams(f).toString()));
  assert.deepEqual(back, f);
  assert.equal(filtersFromParams(new URLSearchParams("")), null);
  assert.equal(filtersFromParams(new URLSearchParams("utm=x")), null);
  assert.equal(filtersToParams(DEFAULT_FILTERS).toString(), "");
});

test("bad URL / stored values are dropped, not trusted", () => {
  const f = filtersFromParams(new URLSearchParams("sort=nope&dir=sideways&d=1995,2020&rt=huge&res=8k&st=notanid~X&pp=" + ID1 + "~Ok"))!;
  assert.equal(f.sort, "added");
  assert.equal(f.dir, null);
  assert.deepEqual(f.decades, [2020]);
  assert.equal(f.runtime, "");
  assert.equal(f.res, "");
  assert.deepEqual(f.studios, []);
  assert.deepEqual(f.people, [{ id: ID1, name: "Ok" }]);
  assert.equal(parseFilters({ sort: "random", dir: "desc" }).dir, null);
});

test("studio / person search results are parsed defensively", () => {
  assert.deepEqual(parseSearchItems({ Items: [{ Id: ID1.toUpperCase(), Name: "A24" }, { Id: "x", Name: "Bad" }, { Id: ID2 }, null] }), [{ id: ID1, name: "A24" }]);
  assert.deepEqual(parseSearchItems(null), []);
});

test("routes: /movies/all and /shows/all parse to their own non-root routes with the query kept", async () => {
  const { parseHash } = await import("../src/lib/routeParse.ts");
  const { isRootRoute } = await import("../src/lib/router.ts").catch(() => ({ isRootRoute: undefined }));
  const m = parseHash("#/movies/all?sort=runtime&dir=asc");
  assert.equal(m.name, "moviesall");
  assert.equal(m.query.get("sort"), "runtime");
  assert.equal(parseHash("#/shows/all").name, "showsall");
  assert.equal(parseHash("#/movies").name, "movies");
  assert.equal(parseHash("#/shows/").name, "shows");
  if (isRootRoute) assert.equal(isRootRoute("moviesall"), false); // detail-style screen: shows the back arrow
});
