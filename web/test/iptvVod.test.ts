import { test } from "node:test";
import assert from "node:assert/strict";
import { iptvPart, parseVodDetail, parseVodItems, vodTech } from "../src/lib/iptvVod.ts";
import { parseRows } from "../src/lib/rows.ts";

const vod = { id: "3:m:991", source: "iptv", kind: "movie", tmdbId: 27205, title: "Inception", year: 2010, quality: ["4k", "hdr"], poster: "https://image.tmdb.org/t/p/w342/x.jpg" };

test("vod items: bad ids, missing titles and duplicates drop out", () => {
  const items = parseVodItems([vod, { ...vod }, { id: "../etc", title: "x" }, { id: "3:m:1" }, { id: "3:s:7", title: "Show" }, null]);
  assert.deepEqual(items.map((i) => [i.id, i.kind]), [["3:m:991", "movie"], ["3:s:7", "series"]]);
  assert.equal(items[0].progress, null);
  assert.deepEqual(parseVodItems("nope"), []);
});

test("an iptv row has no itemIds but survives on its iptvItems", () => {
  const rows = parseRows([
    { id: "iptv:4k", source: "iptv", title: "4K new releases", layout: "poster", itemIds: [], iptvItems: [vod] },
    { id: "iptv:empty", source: "iptv", title: "Empty", itemIds: [], iptvItems: [] },
    { id: "lib", title: "Library", itemIds: ["a"] },
  ])!;
  assert.deepEqual(rows.map((r) => [r.id, r.source, r.iptvItems.length]), [["iptv:4k", "iptv", 1], ["lib", "jellytip", 0]]);
});

test("details: seasons sorted, episodes with bad ids dropped, empty seasons gone", () => {
  const d = parseVodDetail({
    ...vod,
    id: "3:s:7",
    kind: "series",
    runtime: 3000,
    seasons: [
      { season: 2, episodes: [{ id: "3:e:21", episode: 1, progress: { position: 40, duration: 2400 } }] },
      { season: 1, episodes: [{ id: "3:e:11", episodeId: "11", episode: 1 }, { id: "bad", episode: 2 }] },
      { season: 3, episodes: [] },
    ],
  })!;
  assert.deepEqual(d.seasons!.map((s) => [s.season, s.episodes.map((e) => e.id)]), [[1, ["3:e:11"]], [2, ["3:e:21"]]]);
  assert.equal(d.seasons![1].episodes[0].episodeId, "21");
  assert.deepEqual(d.seasons![1].episodes[0].progress, { position: 40, duration: 2400, played: false });
});

test("franchise part: only source iptv with a valid copy counts", () => {
  assert.equal(iptvPart({ source: "iptv", iptv: { id: "3:m:5", title: "Skyfall", year: 2012 } })?.id, "3:m:5");
  assert.equal(iptvPart({ source: "iptv", iptv: { id: "nope" } }), null);
  assert.equal(iptvPart({ iptv: { id: "3:m:5" } }), null);
  assert.equal(iptvPart(null), null);
});

test("badge formats: server tech first, else the quality tokens", () => {
  assert.deepEqual(parseVodItems([{ ...vod, tech: ["4k", "dv", "ATMOS", "junk"] }])[0].tech, ["4K", "DV", "ATMOS"]);
  assert.deepEqual(vodTech({ tech: ["DV"], quality: ["4k"] }), ["DV"]);
  assert.deepEqual(vodTech({ quality: ["4k", "hdr"] }), ["4K", "HDR"]);
  assert.deepEqual(vodTech({ quality: ["1080p"] }), []);
});

import { parseVodItem as pvi } from "../src/lib/iptvVod.ts";
test("favourite flag is read only when the server sends a boolean", () => {
  assert.equal(pvi({ id: "1:m:2", title: "A", favourite: true })?.favourite, true);
  assert.equal(pvi({ id: "1:m:2", title: "A" })?.favourite, null);
  assert.equal(pvi({ id: "1:m:2", title: "A", favourite: "yes" })?.favourite, null);
});

test("vod detail: TMDB art, cast with portraits, genres, trailer", () => {
  const d = parseVodDetail({
    id: "3:m:9",
    title: "Léon",
    backdrop: "v1/iptv/img/3:m:9.abcd",
    logo: "https://image.tmdb.org/t/p/w500/l.png",
    genres: ["Crime", "Drama", "Thriller", "Extra"],
    trailer: "v1/iptv/trailer/3:m:9.mp4",
    cast: [{ name: "Jean Reno", character: "Léon", profile: "v1/iptv/img/p.1" }, { name: "Nat", profile: null }, { character: "no name" }],
  });
  assert.equal(d?.logo, "https://image.tmdb.org/t/p/w500/l.png");
  assert.equal(d?.genre, "Crime, Drama, Thriller");
  assert.equal(d?.trailer, "v1/iptv/trailer/3:m:9.mp4");
  assert.deepEqual(d?.castList?.map((c) => c.name), ["Jean Reno", "Nat"]);
  assert.equal(d?.cast, "Jean Reno, Nat");
  // The older shapes still parse
  const old = parseVodDetail({ id: "3:m:9", title: "Léon", genre: "Crime", cast: "A, B" });
  assert.equal(old?.genre, "Crime");
  assert.equal(old?.cast, "A, B");
  assert.equal(old?.castList, undefined);
});
