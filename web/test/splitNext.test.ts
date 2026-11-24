// Next up over a split series (TV JellytipSplitNextTest / SeriesNextClaimTest port; The Celebrity Traitors, 22-Nov:
// library S2E2 + E6-10, IPTV all of S2). Provider ids scrubbed to pp000001.
import { test } from "node:test";
import assert from "node:assert/strict";
import { claimedNext, keepLibraryNext, nextAfter, parseSeriesNext, seriesPrimary, splitEpisodes, splitNextUp, type SplitEp, type SplitLibEp } from "../src/lib/splitNext.ts";
import type { VodEpisode } from "../src/lib/iptvVod.ts";

const lib = (e: number, o: Partial<SplitLibEp> = {}): SplitLibEp => ({ id: `lib${(o.season ?? 2) * 100 + e}`, season: 2, episode: e, name: `Episode ${e}`, played: false, positionTicks: 0, ...o });
const tv = (e: number, o: { played?: boolean; pos?: number; title?: string; id?: string } = {}): VodEpisode => ({
  id: o.id ?? `pp000001:e:${e}`,
  episodeId: String(e),
  episode: e,
  title: o.title ?? `Episode ${e}`,
  played: !!o.played,
  progress: o.played || o.pos ? { position: o.played ? 3500 : o.pos!, duration: 3600, played: !!o.played } : null,
});
const code = (e: SplitEp | null | undefined) => (e ? `S${e.season}E${e.episode}${e.lib ? "L" : "I"}` : null);

// Jellyfin lists the missing ones as placeholders (Virtual), as the series page sees them
const library = [lib(1, { virtual: true }), lib(2, { played: true }), lib(3, { virtual: true }), lib(4, { virtual: true }), lib(5, { virtual: true }), ...[6, 7, 8, 9, 10].map((n) => lib(n))];
const iptvS2 = [{ season: 2, episodes: Array.from({ length: 10 }, (_, i) => tv(i + 1, { played: i === 0 })) }];

test("after library E2 the next is the IPTV-only E3", () => {
  const eps = splitEpisodes(library, iptvS2, [2]);
  assert.equal(eps.length, 10);
  assert.deepEqual([0, 1, 2, 4, 5].map((i) => code(eps[i])), ["S2E1I", "S2E2L", "S2E3I", "S2E5I", "S2E6L"]);
  assert.equal(code(splitNextUp(eps)), "S2E3I");
  // the player's Up next after library E2: IPTV E3 (not the library's E6); after IPTV E5: library E6
  assert.equal(code(nextAfter(eps, 2, 2)), "S2E3I");
  assert.equal(code(nextAfter(eps, 2, 5)), "S2E6L");
  assert.equal(nextAfter(eps, 2, 10), null);
});

test("watched on either source counts; a started IPTV episode resumes", () => {
  const eps = splitEpisodes(library, [{ season: 2, episodes: Array.from({ length: 10 }, (_, i) => tv(i + 1, { played: i < 3, pos: i === 3 ? 600 : 0 })) }], [2]);
  const n = splitNextUp(eps)!;
  assert.equal(code(n), "S2E4I");
  assert.ok(n.inProgress);
  // library E7 watched later: the next is after it (E8), earlier gaps stay behind
  const later = splitEpisodes(library.map((l) => (l.episode === 7 ? { ...l, played: true } : l)), iptvS2, [2]);
  assert.equal(code(splitNextUp(later)), "S2E8L");
});

test("web rule: an IPTV episode only takes a slot the page draws", () => {
  // no placeholder for E3 in a library season: no tile, no slot
  const eps = splitEpisodes([lib(2, { played: true }), lib(6)], [{ season: 2, episodes: [tv(3)] }], [2]);
  assert.deepEqual(eps.map(code), ["S2E2L", "S2E6L"]);
  // a placeholder whose title disagrees: no slot either
  const bad = splitEpisodes([lib(3, { virtual: true, name: "The Reunion" })], [{ season: 2, episodes: [tv(3, { title: "Banished" })] }], [2]);
  assert.deepEqual(bad, []);
  // a season the library lacks is all IPTV
  const only = splitEpisodes([lib(1, { season: 1, played: true })], [{ season: 2, episodes: [tv(1), tv(2)] }], [1]);
  assert.deepEqual(only.map(code), ["S1E1L", "S2E1I", "S2E2I"]);
  assert.equal(code(splitNextUp(only)), "S2E1I");
});

test("edges: specials, all watched, duplicate blocks, unknown position", () => {
  // nothing watched: the first real episode (specials left out)
  assert.equal(code(splitNextUp(splitEpisodes([lib(1, { season: 0 })], [{ season: 1, episodes: [tv(1), tv(2)] }], [0]))), "S1E1I");
  assert.equal(splitNextUp(splitEpisodes([lib(1, { season: 1, played: true })], [], [1])), null);
  const dup = splitEpisodes([], [{ season: 2, episodes: [tv(3)] }, { season: 2, episodes: [tv(3, { id: "pp000002:e:3" })] }], []);
  assert.equal(dup[0]!.iptv?.id, "pp000001:e:3");
  assert.equal(nextAfter(dup, null, 3), null);
  assert.deepEqual(splitEpisodes([], null, []), []);
});

const short = [lib(2, { id: "ff7b098eb4811105eadbd2f25199f299", played: true }), lib(3, { virtual: true }), lib(4, { virtual: true }), lib(5, { virtual: true })];
const shortEps = splitEpisodes(short, [{ season: 2, episodes: [1, 2, 3, 4, 5].map((e) => tv(e)) }], [2]);

test("the live Traitors answer places on the IPTV E3 tile", () => {
  const claims = parseSeriesNext({ seriesNext: { "48fcd920aa5ca0985a1670879ae1a105": { source: "iptv", id: "pp000001:e:3", season: 2, episode: 3, inProgress: true } } });
  const c = claims.get("48fcd920aa5ca0985a1670879ae1a105")!;
  assert.ok(c.inProgress);
  const got = claimedNext(c, shortEps);
  assert.equal(got?.kind, "episode");
  assert.equal(got?.kind === "episode" && got.ep.iptv?.id, "pp000001:e:3");
  const p = seriesPrimary(shortEps, c)!;
  assert.equal(code(p.ep), "S2E3I");
  assert.ok(p.resume);
});

test("claims: library id, finished, no claim, junk, fall back by season/episode", () => {
  const one = (o: unknown) => parseSeriesNext({ seriesNext: { S1: o } }).get("s1");
  const c = one({ source: "library", id: "ff7b098e-b481-1105-eadb-d2f25199f299", season: 2, episode: 2 })!;
  const got = claimedNext(c, shortEps);
  assert.equal(got?.kind === "episode" && got.ep.lib?.id, "ff7b098eb4811105eadbd2f25199f299");
  assert.deepEqual(claimedNext(one({ source: null })!, shortEps), { kind: "finished" });
  assert.equal(parseSeriesNext({ seriesNext: {} }).size, 0);
  assert.equal(one({ source: "plex" }), undefined);
  assert.equal(one({ id: "x" }), undefined); // no "source" key: malformed, not finished
  assert.equal(parseSeriesNext("nope").size, 0);
  assert.equal(parseSeriesNext(null).size, 0);
  const bySe = one({ source: "iptv", id: "x:e:99", season: 2, episode: 4 })!;
  const g2 = claimedNext(bySe, shortEps);
  assert.equal(g2?.kind === "episode" && g2.ep.episode, 4);
  assert.equal(claimedNext(one({ source: "iptv", id: "x", season: 9, episode: 9 })!, shortEps), null);
});

test("seriesPrimary: claim, order rule, finished, empty", () => {
  // no claim: the order rule (first not watched after library E2)
  assert.equal(code(seriesPrimary(shortEps, null)!.ep), "S2E3I");
  // finished claim: the first episode the page has again (E1 has no placeholder here, so no tile), no Next marker
  const fin = seriesPrimary(shortEps, { source: null, id: null, season: null, episode: null, inProgress: false })!;
  assert.equal(code(fin.ep), "S2E2L");
  assert.equal(fin.next, false);
  // an unplaceable claim: the order rule
  assert.equal(code(seriesPrimary(shortEps, { source: "iptv", id: "zz", season: 9, episode: 9, inProgress: false })!.ep), "S2E3I");
  // all watched, no claim: episode 1 again
  const done = splitEpisodes([lib(1, { season: 1, played: true }), lib(2, { season: 1, played: true })], [], [1]);
  assert.deepEqual([code(seriesPrimary(done, null)!.ep), seriesPrimary(done, null)!.next], ["S1E1L", false]);
  assert.equal(seriesPrimary([], null), null);
});

test("Home Next Up hides Jellyfin's episode when the merged next says otherwise", () => {
  const claims = parseSeriesNext({
    items: [],
    seriesNext: {
      "48fcd920aa5ca0985a1670879ae1a105": { source: "iptv", id: "pp000001:e:2113330", season: 2, episode: 3, inProgress: true },
      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa: { source: "library", id: "00000000000000000000000000000006", season: 1, episode: 6 },
      bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb: { source: null },
    },
  });
  assert.equal(claims.size, 3);
  const e6 = "00000000-0000-0000-0000-000000000006";
  assert.equal(keepLibraryNext("48fcd920-aa5c-a098-5a16-70879ae1a105", e6, 0, claims), false);
  assert.equal(keepLibraryNext("48fcd920-aa5c-a098-5a16-70879ae1a105", e6, 10, claims), true); // a resume: Continue entry
  assert.equal(keepLibraryNext("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", e6, 0, claims), true);
  assert.equal(keepLibraryNext("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", e6, 0, claims), false);
  assert.equal(keepLibraryNext("cccccccccccccccccccccccccccccccc", e6, 0, claims), true);
  assert.equal(keepLibraryNext(null, e6, 0, claims), true);
  assert.equal(parseSeriesNext({ items: [] }).size, 0);
});
