import { test } from "node:test";
import assert from "node:assert/strict";
import { iptvPart, parseVodDetail, parseVodItems, qualityLabel } from "../src/lib/iptvVod.ts";
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

test("quality label", () => {
  assert.equal(qualityLabel(["4k", "hdr"]), "4K HDR");
  assert.equal(qualityLabel(["1080p"]), "HD");
  assert.equal(qualityLabel(["720p"]), null);
});
