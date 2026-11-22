import { test } from "node:test";
import assert from "node:assert/strict";
import { activeChips, appliedNotes, buildQuery, chipFor, chipsFromData, moodLabel, MOOD_TABLE, refineOptions, filterItems, FETCH_LIMIT, parseAsk, pickFrom, removeChip, EXAMPLES, PICKS } from "../src/lib/askParse.ts";
import type { Item } from "../src/api/jellyfin.ts";
import { readFileSync } from "node:fs";

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
  assert.equal(q.Genres, "Comedy|Family|Animation|Kids");
  assert.equal(q.OfficialRatings, "G|PG|TV-Y|TV-Y7|TV-G|TV-PG|U");
  assert.match(appliedNotes(r.chips).join(" "), /rated G, PG/);
});

test("kids alone adds Family|Animation, and the note says so", () => {
  const r = parseAsk("something for the kids", NOW);
  assert.equal(buildQuery(r.chips).Genres, "Family|Animation|Kids");
  assert.match(appliedNotes(r.chips)[0], /Family or Animation/);
});

test("mood map", () => {
  const cases: [string, string][] = [
    ["scary", "Horror"], ["tense", "Thriller"], ["mind-bending", "Science Fiction|Sci-Fi & Fantasy|Mystery"], ["mindbending", "Science Fiction|Sci-Fi & Fantasy|Mystery"],
    ["hilarious", "Comedy"], ["romantic", "Romance"], ["sci-fi", "Science Fiction|Sci-Fi & Fantasy"], ["war", "War|War & Politics"], ["action", "Action|Action & Adventure"], ["family", "Family|Kids"], ["cartoon", "Animation"], ["heartwarming", "Comedy|Family|Animation|Kids"],
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
  assert.deepEqual(parseAsk("", NOW), { chips: [], ignored: [], notes: [] });
  assert.deepEqual(parseAsk("   ", NOW), { chips: [], ignored: [], notes: [] });
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
  assert.equal(a.Limit, PICKS);
  assert.equal(buildQuery(chips, { limit: 50 }).Limit, 50);
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

test("negated genres leave those genres out; other negatives are left out with a note", () => {
  for (const [q, id] of [["not scary", "notgenre:Horror"], ["no horror", "notgenre:Horror"], ["anything but comedy", "notgenre:Comedy"], ["non-animated", "notgenre:Animation"], ["non animated", "notgenre:Animation"], ["nothing scary", "notgenre:Horror"], ["not too scary", "notgenre:Horror"], ["except horror", "notgenre:Horror"]] as const) {
    const r = parseAsk(q, NOW);
    assert.deepEqual(r.chips.map((c) => c.id), [id], q);
    assert.deepEqual(r.ignored, [], q);
    assert.equal(r.notes.length, 0, q);
  }
  // Not a genre ("sad" would drop every drama), or not a mood at all: never guessed
  for (const [q, ign] of [["not sad", "not sad"], ["not very long", "not very long"], ["without violence", "without violence"]] as const) {
    const r = parseAsk(q, NOW);
    assert.equal(r.chips.length, 0, q);
    assert.deepEqual(r.ignored, [ign], q);
    assert.match(r.notes.join(" "), /Negatives only work for genres/, q);
  }
  const r = parseAsk("funny but not for kids", NOW);
  assert.deepEqual(r.chips.map((c) => c.id), ["genre:Comedy"]);
  assert.equal(buildQuery(r.chips).OfficialRatings, undefined);
  assert.ok(r.ignored.includes("but not for kids"));
  // supported phrases that contain "not"/"no" still work, and "none" is not "non"
  assert.ok(ids("not seen yet").includes("unplayed"));
  assert.ok(ids("no more than 90 minutes").includes("maxrt:90"));
  assert.ok(!parseAsk("funny", NOW).notes.length);
  assert.deepEqual(ids("none"), []);
});

test("Ricki 22-Nov: with the kids, nothing animated, nothing scary, works for everyone", () => {
  const r = parseAsk("I'm here with my two kids, want something non animated not scary that would work for all", NOW);
  assert.deepEqual(r.chips.map((c) => c.id).sort(), ["kids", "notgenre:Animation", "notgenre:Horror"]);
  const q = buildQuery(r.chips);
  // The kids rule no longer brings Animation back, and both left-out genres are checked over a full page
  assert.equal(q.Genres, "Family|Kids");
  assert.equal(q.OfficialRatings, "G|PG|TV-Y|TV-Y7|TV-G|TV-PG|U");
  assert.equal(q.Fields, "PrimaryImageAspectRatio,Genres");
  assert.equal(q.Limit, FETCH_LIMIT);
  const out = filterItems(
    [item({ Genres: ["Family", "Adventure"] }), item({ Genres: ["Family", "Animation"] }), item({ Genres: ["Kids", "Horror"] }), item({})],
    r.chips,
  );
  // Live-action family film kept; animated and horror out; unknown genres kept (nothing says it is either)
  assert.equal(out.length, 2);
  const notes = appliedNotes(r.chips).join(" ");
  assert.match(notes, /genres Family or Kids/);
  assert.match(notes, /Left out: titles in Animation, Horror \(titles with no genres listed/);
  assert.match(pickFrom(Array.from({ length: FETCH_LIMIT }, () => item({ Genres: ["Family"] })), 900, r.chips, false).note, /left-out-genre check is only checked within those/);
});

test("a negation never turns into the genre it rules out (articles, or-lists)", () => {
  const sorted = (q: string) => ids(q).sort();
  assert.deepEqual(sorted("not a scary movie"), ["kind:Movie", "notgenre:Horror"]);
  assert.deepEqual(sorted("nothing scary or animated"), ["notgenre:Animation", "notgenre:Horror"]);
  assert.deepEqual(sorted("nothing that's scary"), ["notgenre:Horror"]);
  assert.deepEqual(sorted("anything but a horror"), ["notgenre:Horror"]);
  assert.deepEqual(sorted("no horror, or cartoons"), ["notgenre:Animation", "notgenre:Horror"]);
  assert.deepEqual(sorted("not scary nor animated nor romantic"), ["notgenre:Animation", "notgenre:Horror", "notgenre:Romance"]);
  // "and" does not carry the negation; a non-mood ends the list and is read as usual
  assert.deepEqual(sorted("not scary and funny"), ["genre:Comedy", "notgenre:Horror"]);
  assert.deepEqual(sorted("nothing scary or long, a comedy"), ["genre:Comedy", "notgenre:Horror"]);
  // "not X or Y" is neither, whatever X is: never read back as a positive filter
  assert.deepEqual(sorted("not too long or funny"), ["notgenre:Comedy"]);
  assert.deepEqual(sorted("nothing too long or scary"), ["notgenre:Horror"]);
  assert.deepEqual(sorted("no sequels or horror"), ["notgenre:Horror"]);
  assert.deepEqual(sorted("nothing scary or long or animated"), ["notgenre:Animation", "notgenre:Horror"]);
  assert.deepEqual(sorted("no horror or sequels or not animated"), ["notgenre:Animation", "notgenre:Horror"]);
  // List items skip the same lead-in words as the first one
  assert.deepEqual(sorted("not too long or too scary"), ["notgenre:Horror"]);
  assert.deepEqual(sorted("no horror or any cartoons"), ["notgenre:Animation", "notgenre:Horror"]);
  assert.deepEqual(sorted("nothing for kids or anything scary"), ["notgenre:Horror"]);
  const r = parseAsk("nothing scary or long or animated", NOW);
  assert.deepEqual(r.ignored, ["or long"]);
  assert.match(r.notes.join(" "), /Negatives only work for genres/);
});

test("a mood and its negation: the later one wins, and a negated genre is never asked for", () => {
  const r = parseAsk("feel-good but not animated", NOW);
  assert.deepEqual(r.chips.map((c) => c.id).sort(), ["genre:Feel-good", "notgenre:Animation"]);
  assert.ok(!String(buildQuery(r.chips).Genres).split("|").includes("Animation"));
  const c = parseAsk("not scary, scary", NOW);
  assert.deepEqual(c.chips.map((x) => x.id), ["genre:Horror"]);
  assert.match(c.notes.join(" "), /contradict/);
});

test("chipFor / chipsFromData: a query made outside the parser (refine chips, an LLM) uses the same filters", () => {
  const chips = chipsFromData([
    { t: "kids", ratings: ["G", "PG"], genres: ["Family", "Kids"] },
    { t: "notGenre", genres: ["Animation"] },
    { t: "notGenre", genres: ["Animation"] },
    { t: "years", from: 1990, to: 1999 },
    { t: "maxRuntime", min: 100 },
  ]);
  assert.deepEqual(chips.map((c) => c.id), ["kids", "notgenre:Animation", "years:1990-1999", "maxrt:100"]);
  assert.deepEqual(chips.map((c) => c.label), ["for kids (rated G, PG)", "no animation", "1990s", "under 100 min"]);
  const q = buildQuery(chips);
  assert.equal(q.Genres, "Family|Kids");
  assert.equal(q.OfficialRatings, "G|PG");
  // Same id as the parser's, so a parsed chip and a refine chip never double up
  assert.equal(chipFor({ t: "notGenre", genres: ["Horror"] }).id, parseAsk("not scary", NOW).chips[0].id);
  assert.equal(chipFor({ t: "notGenre", genres: ["Science Fiction", "Sci-Fi & Fantasy"] }).id, parseAsk("no sci-fi", NOW).chips[0].id);
  assert.equal(chipFor({ t: "genre", genres: ["Comedy"] }).id, parseAsk("funny", NOW).chips[0].id);
  assert.equal(chipFor({ t: "genre", genres: ["Kids"] }).label, "Kids");
});

test("several year ranges are unioned", () => {
  const years = (q: string) => buildQuery(parseAsk(q, NOW).chips).Years;
  const dec = (a: number, b: number) => Array.from({ length: b - a + 1 }, (_, i) => a + i).join(",");
  assert.equal(years("80s or 90s"), dec(1980, 1999));
  assert.equal(years("1980s and 1990s"), dec(1980, 1999));
  assert.equal(years("2010 to 2015"), dec(2010, 2015));
});

test("contradictions keep the later one and say so", () => {
  const b = parseAsk("movie or series", NOW);
  assert.deepEqual(b.chips.map((c) => c.id), ["kind:Series"]);
  assert.ok(b.ignored.includes("movie"));
  assert.match(b.notes.join(" "), /contradict/);
  const c = parseAsk("rewatch but unwatched", NOW);
  assert.deepEqual(c.chips.map((x) => x.id), ["unplayed"]);
  assert.ok(c.ignored.includes("rewatch"));
});

test("fields and limit are minimal", () => {
  const plain = buildQuery(parseAsk("funny", NOW).chips);
  assert.equal(plain.Fields, "PrimaryImageAspectRatio");
  assert.equal(plain.Limit, PICKS);
  const two = buildQuery(parseAsk("scary comedy", NOW).chips);
  assert.equal(two.Fields, "PrimaryImageAspectRatio,Genres");
  assert.equal(two.Limit, FETCH_LIMIT);
  const se = buildQuery(parseAsk("short series", NOW).chips);
  assert.equal(se.Fields, "PrimaryImageAspectRatio,ChildCount");
  assert.equal(buildQuery(parseAsk("under 90 minutes", NOW).chips).Limit, FETCH_LIMIT);
});

test("TV genre aliases satisfy the client mood check", () => {
  const chips = parseAsk("sci-fi action", NOW).chips;
  const out = filterItems([item({ Type: "Series", Genres: ["Sci-Fi & Fantasy", "Action & Adventure"] }), item({ Genres: ["Science Fiction"] })], chips);
  assert.equal(out.length, 1);
});

test("disclosure mentions the multi-mood check and unrated exclusion", () => {
  const chips = parseAsk("scary comedy well rated", NOW).chips;
  const page = Array.from({ length: FETCH_LIMIT }, () => item({ Genres: ["Horror", "Comedy"] }));
  assert.match(pickFrom(page, 900, chips, false).note, /all-moods check is only checked within those/);
  assert.match(appliedNotes(chips).join(" "), /must match each one/);
  assert.match(appliedNotes(chips).join(" "), /without a community rating/);
});

test("refine chips: people shared by picks, genres to keep / leave out, decades, kind, length, unwatched", () => {
  const p = (Id: string, Name: string, Type: string) => ({ Id, Name, Type });
  const kept = [
    item({ Genres: ["Family", "Adventure"], ProductionYear: 1993, mins: 120, UserData: { Played: false }, People: [p("d1", "Chris Columbus", "Director"), p("a1", "Robin Williams", "Actor")] }),
    item({ Genres: ["Family", "Adventure"], ProductionYear: 1995, mins: 104, UserData: { Played: false }, People: [p("d1", "Chris Columbus", "Director"), p("a1", "Robin Williams", "Actor")] }),
    item({ Genres: ["Family", "Comedy"], ProductionYear: 1997, mins: 90, UserData: { Played: true }, People: [p("a1", "Robin Williams", "Actor")] }),
    item({ Genres: ["Family", "Adventure"], ProductionYear: 2004, mins: 150, UserData: { Played: false } }),
    item({ Type: "Series", Genres: ["Family", "Comedy"], ProductionYear: 2001, mins: 30, UserData: { Played: false } }),
    item({ Genres: ["Family", "Comedy"], ProductionYear: 1999, mins: 95, UserData: { Played: false } }),
  ];
  const chips = parseAsk("for the kids, nothing scary", NOW).chips;
  const ids = refineOptions(kept, kept, chips).map((c) => c.id);
  // People first (a director before actors), only if shared by 2+ picks and not by all
  assert.equal(ids[0], "person:d1");
  assert.ok(ids.includes("person:a1"));
  // Family is on all 6: no use as a filter; Adventure and Comedy split them
  assert.ok(!ids.includes("genre:Family") && !ids.includes("notgenre:Family"));
  assert.ok(ids.includes("genre:Adventure") && ids.includes("notgenre:Adventure"));
  assert.ok(ids.includes("years:1990-1999"));
  // Five movies of six split them; the cap keeps the first 8 (people, genres, decades, kind): length and unwatched are cut
  assert.equal(ids.length, 8);
  assert.equal(ids[7], "kind:Movie");
  assert.ok(!ids.includes("maxrt:120") && !ids.includes("unplayed"));
  // Without the people they fit: five of six under 2 h, five of six unwatched
  const noPeople = refineOptions(kept, [], chips).map((c) => c.id);
  assert.ok(noPeople.includes("maxrt:120") && noPeople.includes("unplayed"));
  // One series among movies: below the 3-title floor, no "shows" chip
  assert.ok(!ids.includes("kind:Series"));
  // Already applied ("nothing scary") is never offered again; at most REFINE_MAX
  assert.ok(!ids.includes("notgenre:Horror"));
  assert.ok(ids.length <= 8);
  const labels = refineOptions(kept, kept, chips).map((c) => c.label);
  assert.ok(labels.includes("directed by Chris Columbus") && labels.includes("with Robin Williams"));
});

test("a person chip asks Jellyfin for that person; refine asks for genres", () => {
  const chips = chipsFromData([{ t: "person", id: "d1", name: "Chris Columbus", role: "Director" }]);
  assert.equal(buildQuery(chips).PersonIds, "d1");
  assert.equal(buildQuery(chips).PersonTypes, "Director");
  assert.equal(buildQuery(chipsFromData([{ t: "person", id: "d1", name: "C", role: "Director" }, { t: "person", id: "a1", name: "R", role: "Actor" }])).PersonTypes, "Director,Actor");
  assert.equal(buildQuery(parseAsk("funny", NOW).chips, { refine: true }).Fields, "PrimaryImageAspectRatio,Genres");
  assert.equal(buildQuery(parseAsk("funny", NOW).chips).Fields, "PrimaryImageAspectRatio");
  assert.deepEqual(refineOptions([], [], chips), []);
});

test("chip contract v1 golden file (shared with the TV and the server): chipFor, the mood table, the C3 labels", () => {
  const g = JSON.parse(readFileSync(new URL("./fixtures/chip-contract-v1.json", import.meta.url), "utf8"));
  assert.equal(g.v, 1);
  assert.deepEqual(g.moods, MOOD_TABLE);
  for (const v of g.variants) assert.deepEqual([chipFor(v.data).id, chipFor(v.data).label], [v.id, v.label], JSON.stringify(v.data));
  for (const c of g.c3) {
    assert.equal(moodLabel(c.genres), c.label, JSON.stringify(c.genres));
    assert.equal(chipFor({ t: "notGenre", genres: c.genres }).label, c.notGenreLabel);
  }
  // A merged chip and a later single-genre chip no longer share an id
  assert.notEqual(chipFor({ t: "genre", genres: ["Adventure", "Action & Adventure", "Comedy"] }).id, chipFor({ t: "genre", genres: ["Adventure"] }).id);
});
