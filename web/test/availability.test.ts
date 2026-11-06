import { test } from "node:test";
import assert from "node:assert/strict";
import { availabilityOf, EP_NOT_IN_LIBRARY, EP_VIA_IPTV, isWatchable, LANGUAGE_UNCONFIRMED, primaryBadge, sourceLabel } from "../src/lib/availability.ts";
import { availability } from "../src/lib/searchRank.ts";
import { reasonLabel, type EpRow } from "../src/lib/seasonEpisodes.ts";
import type { Item } from "../src/api/jellyfin.ts";

const src = (id: string, width: number, extra: Record<string, unknown> = {}) =>
  ({ Id: id, MediaStreams: [{ Type: "Video", Index: 0, Width: width }], ...extra }) as unknown as Item["MediaSources"] extends (infer T)[] | undefined ? T : never;
const movie = (sources: ReturnType<typeof src>[]): Item => ({ Id: "m1", Name: "Heat", Type: "Movie", MediaSources: sources });

test("multi-version library: one library way, best version first, versions listed", () => {
  const [w] = availabilityOf(movie([src("a", 1920, { Name: "1080p" }), src("b", 3840, { Name: "4K" })]), { tech: [] });
  assert.equal(w.kind, "library");
  assert.deepEqual(w.quality, ["4K"]);
  assert.equal(w.versions?.length, 2);
  assert.equal(w.versions?.[0].label, "4K");
  assert.equal(w.detail, "2 versions: 4K, 1080p");
});

test("server tech augments the primary file (HDR, Atmos)", () => {
  const [w] = availabilityOf(movie([src("a", 3840)]), { tech: ["4K", "DV", "ATMOS"] });
  assert.deepEqual(w.quality, ["4K", "Dolby Vision", "Dolby Atmos"]);
});

test("IPTV only: unconfirmed language, owned providers only", () => {
  const ways = availabilityOf(null, {
    iptv: [
      { id: "3:m:1", providerName: "Mine", quality: ["4k"] },
      { id: "9:m:2", providerName: "Stranger", quality: ["4k"] },
    ],
    allowedProviderIds: [3],
  });
  assert.equal(ways.length, 1);
  assert.equal(ways[0].kind, "iptv");
  assert.equal(ways[0].language, LANGUAGE_UNCONFIRMED);
  assert.equal(ways[0].title, "IPTV · Mine");
  assert.deepEqual(ways[0].quality, ["4K"]);
});

test("a stated language is kept", () => {
  const [w] = availabilityOf(null, { iptv: [{ id: "3:m:1", language: "Spanish" }] });
  assert.equal(w.language, "Spanish");
});

test("both: library before IPTV before downloaded (online); IPTV sorted by quality", () => {
  const ways = availabilityOf(movie([src("a", 1920)]), {
    downloaded: true,
    tech: [],
    iptv: [
      { id: "3:m:1", providerName: "A", quality: [] },
      { id: "4:m:1", providerName: "B", quality: ["4k"] },
    ],
  });
  assert.deepEqual(ways.map((w) => w.kind), ["library", "iptv", "iptv", "downloaded"]);
  assert.equal(ways[1].iptvId, "4:m:1");
});

test("offline: downloaded copy first", () => {
  const ways = availabilityOf(movie([src("a", 3840)]), { online: false, downloaded: true, tech: [], iptv: [{ id: "3:m:1" }] });
  assert.deepEqual(ways.map((w) => w.kind), ["downloaded", "library", "iptv"]);
  assert.equal(ways[0].detail, "Plays without a connection");
});

test("not in library: requestable is the only way; state is honest", () => {
  assert.deepEqual(availabilityOf(null, { requestable: true }).map((w) => [w.kind, w.title]), [["request", "Request this title"]]);
  assert.equal(availabilityOf(null, { requestState: "requested" })[0].title, "Requested");
  assert.equal(availabilityOf(null, { requestState: "in_library" })[0].kind, "unavailable");
});

test("nothing: one unavailable way with a reason that depends on what was checked", () => {
  const a = availabilityOf(null);
  assert.deepEqual(a.map((w) => w.kind), ["unavailable"]);
  assert.equal(a[0].detail, "Not in your library");
  assert.equal(availabilityOf(null, { iptvChecked: true })[0].detail, "Not in your library or on your IPTV providers");
  assert.equal(isWatchable(a), false);
});

test("a Virtual (missing) item is not a library way", () => {
  const v: Item = { Id: "e", Name: "x", Type: "Episode", LocationType: "Virtual" };
  assert.equal(availabilityOf(v)[0].kind, "unavailable");
});

test("badges: first way decides the tile word", () => {
  assert.equal(primaryBadge(availabilityOf(movie([src("a", 1920)]), { tech: [] })), "Library");
  assert.equal(primaryBadge(availabilityOf(null, { iptv: [{ id: "3:m:1" }] })), "IPTV");
  assert.equal(primaryBadge(availabilityOf(null, { requestable: true })), "Request");
  assert.equal(primaryBadge(availabilityOf(null)), null);
});

test("Search tile words are unchanged", () => {
  assert.equal(availability({ source: "library" }), "Library");
  assert.equal(availability({ source: "iptv" }), "IPTV");
  assert.equal(availability({ source: "discover" }), "Not available");
  assert.equal(sourceLabel("discover"), "Not available");
});

test("season list words are unchanged", () => {
  const now = new Date("2026-11-06T00:00:00Z");
  const row = (o: Partial<EpRow>): EpRow => ({ key: "k", status: "missing", index: 1, name: "n", ...o });
  assert.equal(reasonLabel(row({ iptv: { id: "3:e:1" } }), now), "Available via IPTV");
  assert.equal(reasonLabel(row({}), now), "Not in your library");
  assert.equal(EP_VIA_IPTV, "Available via IPTV");
  assert.equal(EP_NOT_IN_LIBRARY, "Not in your library");
});
