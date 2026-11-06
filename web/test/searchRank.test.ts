import { test } from "node:test";
import assert from "node:assert/strict";
import { availability, groupEntries, iptvLanguageNote, latestGuard, matchTier, norm, rankEntries } from "../src/lib/searchRank.ts";
import { parseIntent } from "../src/lib/searchIntent.ts";
import type { SearchEntry } from "../src/lib/search.ts";

const e = (key: string, title: string, o: Partial<SearchEntry> = {}): SearchEntry => ({ key, kind: "film", source: "library", title, score: 1, quality: [], tech: [], ...o });

test("a leading article does not break an exact match", () => {
  assert.equal(matchTier("The Office", "office"), 0);
});

test("norm and tiers", () => {
  assert.equal(norm("The Office (US)"), "office us");
  assert.equal(matchTier("Léon", "leon"), 0);
  assert.equal(matchTier("Heat 2", "heat"), 1);
  assert.equal(matchTier("Mean Streets", "heat"), 2);
});

test("exact title ranks first even with a lower server score", () => {
  const list = [e("a", "Heatwave", { score: 9 }), e("b", "Heat", { score: 1 }), e("c", "Heat Seekers", { score: 5 })];
  assert.deepEqual(rankEntries(list, "heat").map((x) => x.key), ["b", "a", "c"]);
});

test("within a tier library beats IPTV beats not-available, then score", () => {
  const list = [e("d", "Heat", { source: "discover", tmdbId: 1, score: 9 }), e("i", "Heat", { source: "iptv", iptvId: "1:m:1", score: 9 }), e("l", "Heat", { score: 1 })];
  assert.deepEqual(rankEntries(list, "heat").map((x) => x.key), ["l", "i", "d"]);
});

test("availability labels", () => {
  assert.deepEqual(["library", "iptv", "discover"].map((s) => availability({ source: s as SearchEntry["source"] })), ["Library", "IPTV", "Not available"]);
});

test("groups: movies, shows, people, collections, missing; top is excluded from its group", () => {
  const list = [
    e("m1", "Heat"), e("m2", "Heat 2", { source: "iptv", iptvId: "1:m:2" }), e("s1", "Heat Show", { kind: "series" }),
    e("p1", "Heat Person", { kind: "person", personId: "a" }), e("c1", "Heat Collection", { kind: "collection" }),
    e("d1", "Heat Remake", { source: "discover", tmdbId: 5 }),
  ];
  const g = groupEntries(list, parseIntent("heat"), false);
  assert.equal(g.top?.key, "m1");
  assert.deepEqual(g.movies.map((x) => x.key), ["m2"]);
  assert.deepEqual(g.shows.map((x) => x.key), ["s1"]);
  assert.deepEqual(g.people.map((x) => x.key), ["p1"]);
  assert.deepEqual(g.collections.map((x) => x.key), ["c1"]);
  assert.deepEqual(g.missing.map((x) => x.key), ["d1"]);
  assert.equal(g.order[0], "movies");
});

test("play intent: the playable match leads, not a person or a not-available hit", () => {
  const list = [e("d", "Heat", { source: "discover", tmdbId: 1 }), e("p", "Heat", { kind: "person", personId: "a" }), e("l", "Heat", { score: 0 })];
  assert.equal(groupEntries(list, parseIntent("watch Heat"), false).top?.key, "l");
});

test("person intent: strong puts People first; weak only with a matching person", () => {
  const list = [e("m", "Forrest Gump"), e("p", "Tom Hanks", { kind: "person", personId: "a" })];
  const strong = groupEntries(list, parseIntent("movies with Tom Hanks"), false);
  assert.equal(strong.top?.key, "p");
  assert.equal(strong.order[0], "people");
  assert.equal(groupEntries(list, parseIntent("Tom Hanks films"), false).top?.key, "p");
  // "star wars movies" with an unrelated person: the title match stays on top
  const sw = groupEntries([e("m", "Star Wars"), e("p", "Mark Hamill", { kind: "person", personId: "b" })], parseIntent("star wars movies"), false);
  assert.equal(sw.top?.key, "m");
});

test("live intent puts the live section first only when there is live data", () => {
  const list = [e("m", "Arsenal Story")];
  assert.equal(groupEntries(list, parseIntent("live arsenal"), true).order[0], "live");
  assert.notEqual(groupEntries(list, parseIntent("live arsenal"), false).order[0], "live");
});

test("season intent prefers the library series", () => {
  const list = [e("f", "Fargo", { source: "discover", kind: "series", tmdbId: 3 }), e("s", "Fargo", { kind: "series" })];
  assert.equal(groupEntries(list, parseIntent("fargo s2e1"), false).top?.key, "s");
});

test("stale-response guard: only the newest ticket applies", () => {
  const g = latestGuard();
  const a = g.next();
  const b = g.next();
  const out: string[] = [];
  assert.equal(g.apply(a, () => out.push("old")), false);
  assert.equal(g.apply(b, () => out.push("new")), true);
  assert.deepEqual(out, ["new"]);
  g.next();
  assert.equal(g.isCurrent(b), false);
});

test("stale guard under out-of-order async answers", async () => {
  const g = latestGuard();
  let shown = "";
  const ask = (q: string, ms: number) => {
    const t = g.next();
    return new Promise<void>((ok) => setTimeout(() => (g.apply(t, () => (shown = q)), ok()), ms));
  };
  await Promise.all([ask("he", 30), ask("heat", 5)]);
  assert.equal(shown, "heat");
});

test("IPTV language is flagged unconfirmed only when a language was asked for", () => {
  const iptv = e("i", "Léon", { source: "iptv", iptvId: "1:m:1" });
  assert.equal(iptvLanguageNote(iptv, { filters: { language: "fr" } }), "Language not confirmed");
  assert.equal(iptvLanguageNote(iptv, { filters: {} }), null);
  assert.equal(iptvLanguageNote(e("l", "Léon"), { filters: { language: "fr" } }), null);
});

const rank = (q: string, list: SearchEntry[], live = false) => groupEntries(list, parseIntent(q), live);

test("literal titles beat intent words", () => {
  for (const [q, title] of [["Play Misty for Me", "Play Misty for Me"], ["Live and Let Die", "Live and Let Die"], ["Play Dirty", "Play Dirty"], ["Watch Dogs", "Watch Dogs"], ["The Watch", "The Watch"]] as const) {
    const list = [e("other", "Something Else", { score: 9 }), e("hit", title, { score: 1 }), e("p", title.split(" ").pop()!, { kind: "person", personId: "a" })];
    const g = rank(q, list, true);
    assert.equal(g.top?.key, "hit", q);
    assert.equal(g.intent.play, false, q);
    assert.equal(g.intent.live, false, q);
    assert.notEqual(g.order[0], "live", q);
  }
});

test("intent still applies when no title matches the whole query", () => {
  const g = rank("watch Heat", [e("l", "Heat"), e("x", "Heat Wave")]);
  assert.equal(g.intent.play, true);
});

test("strong person needs a matching name", () => {
  const list = [e("m", "Forrest Gump"), e("p", "Alex Hanks", { kind: "person", personId: "a" })];
  assert.equal(rank("movies with Tom Hanks", list).top?.key, "m");
  const w = rank("With Honors", [e("f", "With Honors"), e("p", "Honor Blackman", { kind: "person", personId: "b" })]);
  assert.equal(w.top?.key, "f");
  assert.notEqual(w.order[0], "people");
});

test("year range boosts matching years inside a tier", () => {
  const list = [e("a", "Wonder Woman", { year: 2017, score: 9 }), e("b", "Wonder Woman", { year: 1984, score: 1 })];
  assert.deepEqual(rankEntries(list, "wonder woman", { from: 1984, to: 1984 }).map((x) => x.key), ["b", "a"]);
  assert.deepEqual(rankEntries(list, "wonder woman").map((x) => x.key), ["a", "b"]);
});

test("NxM applies only when the rest matches a library show", () => {
  const withShow = rank("lost 3x04", [e("s", "Lost", { kind: "series" })]);
  assert.deepEqual([withShow.intent.season, withShow.intent.episode], [3, 4]);
  const none = rank("4x4", [e("m", "Some Film")]);
  assert.equal(none.intent.season, null);
});
