import { test } from "node:test";
import assert from "node:assert/strict";
import { activeChips, clearAll, DEFAULT_FILTERS, isFiltered, loadFilters, parseFacets, parseFilters, removeChip, saveFilters, stateKey, toQuery, type LibraryFilters, type StorageLike } from "../src/lib/libraryFilters.ts";

const mem = (): StorageLike & { data: Map<string, string> } => {
  const data = new Map<string, string>();
  return { data, getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v), removeItem: (k) => void data.delete(k) };
};
const full: LibraryFilters = { sort: "rating", genres: ["Action", "Sci-Fi"], years: [2021, 1999], ratings: ["R"], minRating: 7, watched: "unplayed", favorite: true };

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
