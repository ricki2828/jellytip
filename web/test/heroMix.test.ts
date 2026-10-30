import { test } from "node:test";
import assert from "node:assert/strict";
import { buildSlides, excludedIds, heroSources, rowKicker } from "../src/lib/heroMix.ts";

const it = (Id: string, extra: Record<string, unknown> = {}) => ({ Id, Name: Id, Type: "Movie", ...extra }) as never;

test("row kickers", () => {
  assert.equal(rowKicker({ family: "x", title: "New Releases" }), "New release");
  assert.equal(rowKicker({ family: "fresh", title: "Fresh stuff" }), "Just added");
  assert.equal(rowKicker({ family: "personal", title: "Unwatched gems" }), null);
});

test("sources: featured rows and specials only, IPTV rows skipped", () => {
  const s = heroSources(
    [
      { id: "a", family: "x", title: "New releases", itemIds: ["1", "2"], iptvItems: [] },
      { id: "b", family: "x", title: "Just added", itemIds: [], iptvItems: [{}] },
      { id: "c", family: "x", title: "Misc", itemIds: ["9"], iptvItems: [] },
    ],
    [{ layout: "tonights_pick", itemId: "5" }, { layout: "trailer_break" }, { layout: "top10", itemIds: ["7", "8"] }],
  );
  assert.deepEqual(s.map((x) => x.kicker), ["New release", "Tonight's pick", "Top 10 today"]);
});

test("slides interleave sources, skip Continue Watching, watched and repeats; the leading source rotates daily", () => {
  const src = [
    { kicker: "New release", items: [it("a"), it("b"), it("w", { UserData: { Played: true } })] },
    { kicker: "Just added", items: [it("c"), it("a"), it("show", { Type: "Series" }), it("e", { Type: "Episode" })] },
  ];
  const ex = excludedIds([it("ep1", { SeriesId: "show", Type: "Episode" })]);
  assert.deepEqual(buildSlides(src.map((s) => ({ ...s, items: [...s.items] })), ex, 0).map((x) => `${x.kicker}:${x.item.Id}`), ["New release:a", "Just added:c", "New release:b"]);
  assert.deepEqual(buildSlides(src.map((s) => ({ ...s, items: [...s.items] })), ex, 1).map((x) => x.kicker).slice(0, 2), ["Just added", "New release"]);
  assert.deepEqual(buildSlides([], ex, 0), []);
});
