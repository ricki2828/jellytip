import { test } from "node:test";
import assert from "node:assert/strict";
import { pickPullQuote, trimAtWord } from "../src/lib/pullQuote.ts";

const r = (o: Partial<{ author: string; own: boolean; rating: number | null; excerpt: string; text: string; spoiler: boolean; source: string }>) => ({
  author: "a", own: false, rating: null, excerpt: "", text: "", spoiler: false, source: "letterboxd", ...o,
});

test("own review text first, then the server quote, then the first non-spoiler excerpt; none when empty", () => {
  const quote = { text: "Server pick", author: "bob", source: "letterboxd" };
  assert.equal(pickPullQuote({ quote, reviews: [r({ author: "me", own: true, excerpt: "Mine", rating: 4.5 })] })?.text, "Mine");
  assert.deepEqual(pickPullQuote({ quote, reviews: [r({ author: "me", own: true }), r({ author: "bob", rating: 4 })] }), {
    text: "Server pick", author: "bob", rating: 4, site: "Letterboxd", by: "bob on Letterboxd", mark: null, own: false,
  });
  assert.equal(pickPullQuote({ quote: null, reviews: [r({ excerpt: "spoily", spoiler: true }), r({ author: "c", excerpt: "Clean", source: "tmdb" })] })?.site, "TMDB");
  assert.equal(pickPullQuote({ quote: null, reviews: [r({ spoiler: true, excerpt: "x" }), r({ own: true })] }), null);
  assert.equal(pickPullQuote(null), null);
});

test("trimmed at a word with an ellipsis", () => {
  const t = trimAtWord("one two three four five six", 15);
  assert.equal(t, "one two three…");
  assert.equal(trimAtWord("short", 15), "short");
});

import { bylineText, siteName } from "../src/lib/attribution.ts";

test("Rotten Tomatoes consensus names itself with its mark; other sources never say Letterboxd", () => {
  const rt = { text: "Consensus text", author: "Critics", source: "rottentomatoes", mark: "rt_rotten" };
  const q = pickPullQuote({ quote: rt, reviews: [] });
  assert.equal(q?.site, "Rotten Tomatoes Critics Consensus");
  assert.equal(q?.by, "Rotten Tomatoes Critics Consensus");
  assert.equal(q?.mark, "rt_rotten");
  assert.equal(bylineText("tmdb", "ann"), "ann on TMDB");
  assert.equal(bylineText("somewhere", "ann"), "ann");
  assert.equal(siteName("somewhere"), "");
  assert.equal(bylineText(undefined, "ann"), "ann on Letterboxd");
  assert.equal(pickPullQuote({ quote: { ...rt, mark: "weird" }, reviews: [] })?.mark, null);
});
