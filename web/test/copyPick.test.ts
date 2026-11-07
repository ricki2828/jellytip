import test from "node:test";
import assert from "node:assert/strict";
import { pickKey, recallPick, rememberedTarget, rememberPick } from "../src/lib/copyPick.ts";
import { parseVodDetail } from "../src/lib/iptvVod.ts";
import { availabilityOf, OUTSIDE_GROUPS } from "../src/lib/availability.ts";

const mem = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
};
const NOW = 1_800_000_000;

test("pick key: TMDB id first, else normalised title + year", () => {
  assert.equal(pickKey({ kind: "series", tmdbId: 93740, title: "Foundation" }), "series:t93740");
  assert.equal(pickKey({ kind: "movie", title: "The  Gentlemen!", year: 2024 }), "movie:nthe gentlemen:2024");
});

test("remember + recall; broken storage never throws", () => {
  const s = mem();
  rememberPick("series:t1", "p:s:2", s);
  assert.equal(recallPick("series:t1", s), "p:s:2");
  assert.equal(recallPick("series:t2", s), null);
  const bad = { getItem: () => { throw new Error("x"); }, setItem: () => { throw new Error("x"); } };
  assert.doesNotThrow(() => rememberPick("k", "v", bad));
  assert.equal(recallPick("k", bad), null);
});

test("remembered target: only a copy still on offer and not failed lately", () => {
  const copies = [{ id: "a:s:1" }, { id: "b:s:2" }, { id: "c:s:3", lastFail: { at: NOW - 60, reason: "open_failed", count7d: 1 } }];
  assert.equal(rememberedTarget("b:s:2", "a:s:1", copies, NOW), "b:s:2");
  assert.equal(rememberedTarget("a:s:1", "a:s:1", copies, NOW), null, "already on it");
  assert.equal(rememberedTarget("z:s:9", "a:s:1", copies, NOW), null, "no longer offered");
  assert.equal(rememberedTarget("c:s:3", "a:s:1", copies, NOW), null, "failed in the last 24 h");
  assert.equal(rememberedTarget(null, "a:s:1", copies, NOW), null);
});

test("detail parse keeps the new copy fields; older servers still parse", () => {
  const d = parseVodDetail({
    id: "p:s:1", title: "Foundation", kind: "series",
    copies: [
      { id: "p:s:1", providerTitle: "Foundation_eng", providerName: "OrigineTV", quality: [], res: null, lang: "en", langLabel: "English", opted: true, current: true },
      { id: "q:s:5", providerTitle: "4K-A+ - Foundation", providerName: "IPTV", quality: ["4k"], res: "4k", lang: "en", langLabel: "English", opted: false },
      { id: "q:s:6", providerTitle: "old" },
    ],
  })!;
  assert.equal(d.copies![0].current, true);
  assert.equal(d.copies![1].opted, false);
  assert.equal(d.copies![1].res, "4k");
  assert.equal(d.copies![2].lang, null);
  assert.equal(d.copies![2].opted, undefined);
});

test("ways: ranked copies keep the server order; outside-groups note; res word when tokens are empty", () => {
  const copies = [
    { id: "p:s:1", providerName: "A", quality: [], res: null, language: "English", opted: true },
    { id: "q:s:5", providerName: "B", quality: [], res: "4k", language: "English", opted: false },
  ];
  const kept = availabilityOf(null, { iptv: copies, iptvRanked: true });
  assert.deepEqual(kept.map((w) => w.iptvId), ["p:s:1", "q:s:5"]);
  assert.equal(kept[1].detail, OUTSIDE_GROUPS);
  assert.deepEqual(kept[1].quality, ["4K"]);
  assert.equal(kept[1].language, "English");
  const sorted = availabilityOf(null, { iptv: copies });
  assert.deepEqual(sorted.map((w) => w.iptvId), ["q:s:5", "p:s:1"], "unranked lists still sort by quality");
});
