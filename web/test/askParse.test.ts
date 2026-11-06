import { test } from "node:test";
import assert from "node:assert/strict";
import { activeChips, appliedNotes, buildQuery, filterItems, FETCH_LIMIT, parseAsk, pickFrom, removeChip, EXAMPLES, PICKS } from "../src/lib/askParse.ts";
import type { Item } from "../src/api/jellyfin.ts";

const NOW = new Date("2026-11-06T00:00:00Z");
const ids = (q: string) => parseAsk(q, NOW).chips.map((c) => c.id);
const item = (o: Partial<Item> & { mins?: number }): Item => ({ Id: String(Math.random()), Name: "x", Type: "Movie", RunTimeTicks: o.mins != null ? o.mins * 600_000_000 : undefined, ...o });

test("funny under 90 minutes", () => {
  const r = parseAsk("something funny under 90 minutes", NOW);
  assert.deepEqual(r.chips.map((c) => c.label), ["under 90 min", "Comedy"]);
  assert.deepEqual(r.ignored, []);
});

test("a 70s thriller I haven't seen", () => {
  const r = parseAsk("a 70s thriller I haven't seen", NOW);
  assert.deepEqual(r.chips.map((c) => c.id).sort(), ["genre:Thriller", "unplayed", "years:1970-1979"]);
  assert.deepEqual(r.ignored, []);
  const q = buildQuery(r.chips);
  assert.equal(q.Years, "1970,1971,1972,1973,1974,1975,1976,1977,1978,1979");
  assert.equal(q.Filters, "IsUnplayed");
  assert.equal(q.Genres, "Thriller");
});

test("feel-good for the kids shows what was applied", () => {
  const r = parseAsk("feel-good for the kids", NOW);
  assert.deepEqual(r.chips.map((c) => c.id).sort(), ["genre:Feel-good", "kids"]);
  const q = buildQuery(r.chips);
  assert.equal(q.Genres, "Comedy|Family|Animation");
  assert.equal(q.OfficialRatings, "G|PG|TV-Y|TV-Y7|TV-G|TV-PG|U");
  assert.match(appliedNotes(r.chips).join(" "), /rated G, PG/);
});

test("kids alone adds Family|Animation, and the note says so", () => {
  const r = parseAsk("something for the kids", NOW);
  assert.equal(buildQuery(r.chips).Genres, "Family|Animation");
  assert.match(appliedNotes(r.chips)[0], /Family or Animation/);
});

test("mood map", () => {
  const cases: [string, string][] = [
    ["scary", "Horror"], ["tense", "Thriller"], ["mind-bending", "Science Fiction|Mystery"], ["mindbending", "Science Fiction|Mystery"],
    ["hilarious", "Comedy"], ["romantic", "Romance"], ["sci-fi", "Science Fiction"], ["cartoon", "Animation"], ["heartwarming", "Comedy|Family|Animation"],
  ];
  for (const [w, g] of cases) assert.equal(buildQuery(parseAsk(w, NOW).chips).Genres, g, w);
});

test("decades in all spellings", () => {
  for (const [q, id] of [["90s comedy", "years:1990-1999"], ["'80s", "years:1980-1989"], ["the seventies", "years:1970-1979"], ["1960s", "years:1960-1969"], ["2010s drama", "years:2010-2019"], ["from 1994", "years:1994-1994"]] as const) {
    assert.ok(ids(q).includes(id), q);
  }
  assert.ok(ids("after 2010").includes("years:2011-2026"));
  assert.ok(ids("before 2000").includes("years:1900-1999"));
  assert.ok(ids("recent").includes("years:2022-2026"));
});

test("runtime phrases", () => {
  assert.ok(ids("under 90 minutes").includes("maxrt:90"));
  assert.ok(ids("less than 2 hours").includes("maxrt:120"));
  assert.ok(ids("under an hour").includes("maxrt:60"));
  assert.ok(ids("90 mins or less").includes("maxrt:90"));
  assert.ok(ids("under 1.5 hours").includes("maxrt:90"));
  assert.ok(ids("over 2 hours").includes("minrt:120"));
});

test("unwatched phrasings", () => {
  for (const q of ["I haven't seen", "havent watched", "never seen it", "unwatched", "something unseen", "new to me", "not seen yet"]) assert.ok(ids(q).includes("unplayed"), q);
  assert.ok(ids("a rewatch").includes("played"));
  assert.equal(buildQuery(parseAsk("rewatch", NOW).chips).Filters, "IsPlayed");
});

test("rating phrases", () => {
  assert.equal(buildQuery(parseAsk("well rated", NOW).chips).MinCommunityRating, 7);
  assert.equal(buildQuery(parseAsk("rated over 8", NOW).chips).MinCommunityRating, 8);
  assert.equal(buildQuery(parseAsk("rated 7.5+", NOW).chips).MinCommunityRating, 7.5);
  assert.equal(buildQuery(parseAsk("critically acclaimed", NOW).chips).MinCommunityRating, 7.5);
  assert.equal(buildQuery(parseAsk("rated 90s", NOW).chips).MinCommunityRating, undefined);
});

test("kind and short series", () => {
  assert.equal(buildQuery(parseAsk("a movie", NOW).chips).IncludeItemTypes, "Movie");
  assert.equal(buildQuery(parseAsk("a tv show", NOW).chips).IncludeItemTypes, "Series");
  assert.equal(buildQuery(parseAsk("something", NOW).chips).IncludeItemTypes, "Movie,Series");
  const r = parseAsk("short series, mind-bending", NOW);
  assert.ok(r.chips.some((c) => c.id === "maxseasons:3"));
  assert.equal(buildQuery(r.chips).IncludeItemTypes, "Series");
  assert.ok(ids("a miniseries").includes("maxseasons:1"));
  // "show me" is a request, not the word show
  assert.deepEqual(ids("show me something funny"), ["genre:Comedy"]);
});

test("unparseable words are ignored and listed, filler is silent", () => {
  const r = parseAsk("I want something funny with zebra and quokka", NOW);
  assert.deepEqual(r.ignored, ["zebra", "quokka"]);
  assert.deepEqual(r.chips.map((c) => c.id), ["genre:Comedy"]);
});

test("nothing readable", () => {
  const r = parseAsk("xyzzy plugh", NOW);
  assert.equal(r.chips.length, 0);
  assert.deepEqual(r.ignored, ["xyzzy", "plugh"]);
  assert.deepEqual(parseAsk("", NOW), { chips: [], ignored: [] });
  assert.deepEqual(parseAsk("   ", NOW), { chips: [], ignored: [] });
});

test("every example prompt reads cleanly", () => {
  assert.equal(EXAMPLES.length, 6);
  for (const q of EXAMPLES) {
    const r = parseAsk(q, NOW);
    assert.ok(r.chips.length >= 1, q);
    assert.deepEqual(r.ignored, [], q);
  }
});

test("chip removal drops the filter from the query and stays removed", () => {
  const r = parseAsk("something funny under 90 minutes unwatched", NOW);
  let removed: string[] = [];
  removed = removeChip(removed, "unplayed");
  removed = removeChip(removed, "unplayed");
  assert.deepEqual(removed, ["unplayed"]);
  const chips = activeChips(r, removed);
  assert.equal(chips.some((c) => c.id === "unplayed"), false);
  assert.equal(buildQuery(chips).Filters, undefined);
  assert.equal(buildQuery(activeChips(r, removeChip(removed, "genre:Comedy"))).Genres, undefined);
});

test("query: sort, shuffle, limit", () => {
  const chips = parseAsk("funny", NOW).chips;
  const a = buildQuery(chips);
  assert.equal(a.SortBy, "CommunityRating,SortName");
  assert.equal(a.Limit, FETCH_LIMIT);
  const b = buildQuery(chips, { shuffle: true });
  assert.equal(b.SortBy, "Random");
  assert.equal(b.SortOrder, undefined);
  assert.equal(a.Recursive, true);
});

test("runtime filtering: under N, unknown length excluded, series by episode length", () => {
  const chips = parseAsk("under 90 minutes", NOW).chips;
  const keep = filterItems([item({ mins: 85 }), item({ mins: 90 }), item({ mins: 91 }), item({}), item({ Type: "Series", mins: 30 })], chips);
  assert.equal(keep.length, 3);
  const over = filterItems([item({ mins: 100 }), item({ mins: 130 })], parseAsk("over 2 hours", NOW).chips);
  assert.equal(over.length, 1);
});

test("every mood chip must match, not just any", () => {
  const chips = parseAsk("scary comedy", NOW).chips;
  assert.equal(buildQuery(chips).Genres, "Comedy|Horror");
  const out = filterItems([item({ Genres: ["Horror"] }), item({ Genres: ["Comedy", "Horror"] }), item({ Genres: ["comedy"] })], chips);
  assert.equal(out.length, 1);
});

test("season limit", () => {
  const chips = parseAsk("short series", NOW).chips;
  const out = filterItems([item({ Type: "Series", ChildCount: 2 }), item({ Type: "Series", ChildCount: 3 }), item({ Type: "Series", ChildCount: 4 }), item({ Type: "Series" })], chips);
  assert.equal(out.length, 2);
});

test("coverage disclosure never implies more than was checked", () => {
  const chips = parseAsk("funny under 90 minutes", NOW).chips;
  const page = Array.from({ length: FETCH_LIMIT }, () => item({ mins: 80, Genres: ["Comedy"] }));
  const p = pickFrom(page, 1200, chips, false);
  assert.equal(p.items.length, PICKS);
  assert.match(p.note, /the first 300 of 1200 matches/);
  assert.match(p.note, /only checked within those/);
  assert.match(p.note, /Showing 24 of 300/);
  assert.match(pickFrom(page, 1200, chips, true).note, /a random 300 of 1200/);
  // whole library fit in the page: say so
  const small = pickFrom([item({ mins: 80, Genres: ["Comedy"] }), item({ mins: 120, Genres: ["Comedy"] })], 2, chips, false);
  assert.match(small.note, /Checked all 2 matches/);
  assert.equal(small.items.length, 1);
  // no filter that needs the page: plain wording
  assert.match(pickFrom([item({})], 1, parseAsk("funny", NOW).chips, false).note, /^All 1 matches/);
  // total unknown but page full: still partial
  assert.match(pickFrom(page, undefined, parseAsk("funny", NOW).chips, false).note, /the first 300 matches/);
});
