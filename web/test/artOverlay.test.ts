import { test } from "node:test";
import assert from "node:assert/strict";
import { contrast, HERO, heroOverlay, heroPixel, textOver, TILE, tileOverlay, tilePixel } from "../src/lib/artOverlay.ts";
import { fxArt, parseFixtureList } from "../src/lib/gameCentre.ts";

type RGB = [number, number, number];
const WHITE: RGB = [255, 255, 255];
const TEXT: RGB = [245, 246, 248]; // #f5f6f8, the hero and tile text
const range = (a: number, b: number, n = 25) => Array.from({ length: n }, (_, i) => a + ((b - a) * i) / (n - 1));
// Worst cases: the brightest art pixel under the lightest team colours (and the unknown-colour look)
const PAIRS = [
  { home: "#ffffff", away: "#ffff00", known: true },
  { home: "#ffff00", away: "#ffffff", known: true },
  { home: "#e2264d", away: "#0033a0", known: true },
  { home: "#3b3f46", away: "#2a2d33", known: false },
];

test("hero: the title (large text, 3:1) is readable across the whole text column on any art and team colours", () => {
  // the centre column starts after the crest: ~15% in on desktop, ~20% on a phone; text sits 15-85% up
  for (const c of PAIRS)
    for (const x of range(0.15, 0.85))
      for (const y of range(0.15, 0.85)) assert.ok(contrast(TEXT, heroPixel(x, y, c, WHITE)) >= 3, `title ${c.home} x=${x} y=${y}`);
  const worst = Math.min(...PAIRS.flatMap((c) => range(0.15, 0.85).flatMap((x) => range(0.15, 0.85).map((y) => contrast(TEXT, heroPixel(x, y, c, WHITE))))));
  assert.ok(worst >= 4.5, `hero title worst ${worst.toFixed(2)}`); // AA even for small text, with margin over the 3:1 large-text bar
});

test("hero: the 70% line and the buttons' row (small text, 4.5:1) are readable in the centre", () => {
  for (const c of PAIRS)
    for (const x of range(0.3, 0.7))
      for (const y of range(0.1, 0.6)) {
        const bg = heroPixel(x, y, c, WHITE);
        assert.ok(contrast(textOver(TEXT, 0.7, bg), bg) >= 4.5, `line ${c.home} x=${x} y=${y}`);
      }
});

test("tile: text (4.5:1) and the dim league line at 0.85 are readable edge to edge on any art", () => {
  for (const c of PAIRS)
    for (const x of range(0.03, 0.97)) {
      const bg = tilePixel(x, c, WHITE);
      assert.ok(contrast(TEXT, bg) >= 4.5, `tile text ${c.home} x=${x}`);
      assert.ok(contrast(textOver(TEXT, 0.85, bg), bg) >= 4.5, `tile league ${c.home} x=${x}`);
    }
});

test("hero overlay: scrim over a home-left / away-right gradient at ~55%, meeting in a dark centre", () => {
  const css = heroOverlay({ home: "#e2264d", away: "#0033a0", known: true });
  assert.match(css, /^linear-gradient\(0deg, rgba\(0, 0, 0, 0\.7\) 0%, rgba\(0, 0, 0, 0\) 75%\), /);
  assert.match(css, /linear-gradient\(90deg, rgba\(226, 38, 77, 0\.55\) 0%, rgba\(13, 16, 21, 0\.82\) 30%, rgba\(13, 16, 21, 0\.82\) 70%, rgba\(0, 51, 160, 0\.55\) 100%\)$/);
  assert.equal(HERO.sideAlpha, 0.55);
  // unknown colours: the dark base each side, still over the art
  assert.match(heroOverlay({ home: "#3b3f46", away: "#2a2d33", known: false }), /rgba\(13, 16, 21, 0\.55\) 0%/);
});

test("tile overlay: a lighter wash fading out by the middle; none without colours; a one-team event uses home both sides", () => {
  assert.equal(tileOverlay({ home: "#e2264d", away: "#0033a0", known: true }, true), `linear-gradient(90deg, rgba(226, 38, 77, ${TILE.washAlpha}) 0%, rgba(226, 38, 77, 0) 50%, rgba(0, 51, 160, 0) 50%, rgba(0, 51, 160, ${TILE.washAlpha}) 100%)`);
  assert.ok(TILE.washAlpha < HERO.sideAlpha);
  assert.equal(tileOverlay({ home: "#3b3f46", away: "#2a2d33", known: false }, true), null);
  assert.match(tileOverlay({ home: "#e2264d", away: "#0033a0", known: true }, false)!, /rgba\(226, 38, 77, 0\.18\) 100%/);
});

test("fxArt: plain URLs or the listings' {league: {fanart, badge}}; only companion paths and https", () => {
  assert.deepEqual(fxArt("v1/iptv/img/art/12f.sig", "v1/iptv/img/art/12b.sig"), { art: "v1/iptv/img/art/12f.sig", badge: "v1/iptv/img/art/12b.sig" });
  assert.deepEqual(fxArt({ league: { fanart: "v1/a/f", badge: "v1/a/b" } }, undefined), { art: "v1/a/f", badge: "v1/a/b" });
  assert.deepEqual(fxArt("https://r2.thesportsdb.com/x.jpg", null), { art: "https://r2.thesportsdb.com/x.jpg", badge: null });
  assert.deepEqual(fxArt("http://evil/x.jpg", "javascript:alert(1)"), { art: null, badge: null });
  assert.deepEqual(fxArt("//evil/x.jpg", "data:image/png;base64,xx"), { art: null, badge: null });
  assert.deepEqual(fxArt(null, ""), { art: null, badge: null });
});

test("fixture list carries art and badge; absent = null (the plain look)", () => {
  const row = (o: Record<string, unknown>) => ({ fid: "f", league: "NRL", kickoff: 1000, status: "pre", home: "H", away: "A", channels: [], ...o });
  const items = parseFixtureList({ items: [row({ fid: "a", art: "v1/iptv/img/art/1f.s", badge: "v1/iptv/img/art/1b.s" }), row({ fid: "b" })] }).items;
  assert.deepEqual(items.map((f) => [f.art, f.badge]), [["v1/iptv/img/art/1f.s", "v1/iptv/img/art/1b.s"], [null, null]]);
});

test("the CSS draws the art at the brightness and blur the contrast model assumes", async () => {
  const { readFileSync } = await import("node:fs");
  const hero = readFileSync(new URL("../src/components/LiveMatchHero.css", import.meta.url), "utf8");
  const tile = readFileSync(new URL("../src/screens/iptv/gameCentre.css", import.meta.url), "utf8");
  const n = (v: number) => String(v).replace(".", "\\.");
  assert.match(hero, new RegExp(`\\.lh-art \\{[^}]*filter: brightness\\(${n(HERO.artBrightness)}\\)`));
  assert.match(tile, new RegExp(`\\.gc-artbg img \\{[^}]*filter: blur\\(${TILE.blurPx}px\\) brightness\\(${n(TILE.artBrightness)}\\)`));
  // the pan exists only for motion-on, and only on the hero
  assert.match(hero, /@media \(prefers-reduced-motion: no-preference\) \{\s*\.lh-art \{\s*animation: lh-pan/);
  assert.doesNotMatch(tile, /animation:[^;]*pan/);
});
