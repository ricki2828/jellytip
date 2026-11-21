// Sport artwork under Game Centre's hero and tiles (Ricki 21-Nov): the league/sport image, washed in the two team
// colours. The overlay is built here from one set of numbers, and the same numbers drive a worst-case contrast model
// (a pure-white image under a pure-white team colour), so the tests prove the text stays readable on any art.
import { BASE, type TeamColors } from "./broadcast.ts";

/** Hero: art full-bleed; home colour from the left and away from the right at ~55%, meeting in a dark centre; a bottom scrim. */
export const HERO = { sideAlpha: 0.55, midAlpha: 0.82, midFrom: 0.3, artBrightness: 0.45, scrimAlpha: 0.7, scrimTop: 0.75 } as const;
/** Tile: the same art dimmed and blurred, a lighter colour wash fading out by the middle (the colour edges stay on top). */
export const TILE = { washAlpha: 0.18, artBrightness: 0.2, blurPx: 6 } as const;

type RGB = [number, number, number];
const rgbOf = (hex: string): RGB => {
  const m = /^#([0-9a-f]{6})$/i.exec(hex) ?? /^#([0-9a-f]{6})$/i.exec(BASE)!; // anything but #rrggbb draws as the dark base, never black
  const v = parseInt(m[1], 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
};
const rgba = (hex: string, a: number) => `rgba(${rgbOf(hex).join(", ")}, ${a})`;

/** CSS background for the hero's overlay (above the art): scrim first (it paints on top), then the colour gradient. */
export function heroOverlay(c: TeamColors): string {
  const home = c.known ? c.home : BASE;
  const away = c.known ? c.away : BASE;
  const { sideAlpha: s, midAlpha: m, midFrom: f, scrimAlpha, scrimTop } = HERO;
  return (
    `linear-gradient(0deg, rgba(0, 0, 0, ${scrimAlpha}) 0%, rgba(0, 0, 0, 0) ${scrimTop * 100}%), ` +
    `linear-gradient(90deg, ${rgba(home, s)} 0%, ${rgba(BASE, m)} ${f * 100}%, ${rgba(BASE, m)} ${(1 - f) * 100}%, ${rgba(away, s)} 100%)`
  );
}

/** CSS background for a tile's colour wash (above the blurred art); null without known colours (the art alone, dimmed). */
export function tileOverlay(c: TeamColors, hasAway: boolean): string | null {
  if (!c.known) return null;
  const a = TILE.washAlpha;
  const away = hasAway ? c.away : c.home;
  return `linear-gradient(90deg, ${rgba(c.home, a)} 0%, ${rgba(c.home, 0)} 50%, ${rgba(away, 0)} 50%, ${rgba(away, a)} 100%)`;
}

// ---- contrast model (sRGB compositing, premultiplied gradient interpolation, as browsers draw it) ----
type Stop = { at: number; rgb: RGB; a: number };
function sample(stops: Stop[], t: number): { rgb: RGB; a: number } {
  let i = 0;
  while (i < stops.length - 2 && t > stops[i + 1].at) i++;
  const p = stops[i], q = stops[i + 1];
  const k = q.at === p.at ? 0 : Math.min(1, Math.max(0, (t - p.at) / (q.at - p.at)));
  const a = p.a + (q.a - p.a) * k;
  const pre = p.rgb.map((v, j) => v * p.a + (q.rgb[j] * q.a - v * p.a) * k);
  return { rgb: (a > 0 ? pre.map((v) => v / a) : [0, 0, 0]) as RGB, a };
}
const over = (top: { rgb: RGB; a: number }, under: RGB): RGB => top.rgb.map((v, j) => v * top.a + under[j] * (1 - top.a)) as RGB;
const lum = (c: RGB) => {
  const l = (v: number) => {
    const x = v / 255;
    return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * l(c[0]) + 0.7152 * l(c[1]) + 0.0722 * l(c[2]);
};
export const contrast = (a: RGB, b: RGB) => {
  const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m);
  return (x + 0.05) / (y + 0.05);
};
/** Text colour at an opacity over a background (how `.gc-league { opacity: .7 }` reaches the eye). */
export const textOver = (text: RGB, opacity: number, bg: RGB): RGB => over({ rgb: text, a: opacity }, bg);

/** What the hero shows at (x, y from the bottom), both 0..1, over an art pixel [art]. */
export function heroPixel(x: number, yUp: number, c: TeamColors, art: RGB): RGB {
  const home = rgbOf(c.known ? c.home : BASE), away = rgbOf(c.known ? c.away : BASE), base = rgbOf(BASE);
  const { sideAlpha: s, midAlpha: m, midFrom: f, scrimAlpha, scrimTop, artBrightness } = HERO;
  const dimmed = art.map((v) => v * artBrightness) as RGB;
  const wash = sample([{ at: 0, rgb: home, a: s }, { at: f, rgb: base, a: m }, { at: 1 - f, rgb: base, a: m }, { at: 1, rgb: away, a: s }], x);
  const scrim = sample([{ at: 0, rgb: [0, 0, 0], a: scrimAlpha }, { at: scrimTop, rgb: [0, 0, 0], a: 0 }, { at: 1, rgb: [0, 0, 0], a: 0 }], yUp);
  return over(scrim, over(wash, dimmed));
}

/** What a tile shows at x (0..1) over an art pixel [art] (the blur only averages art, so a white pixel is still the worst case). */
export function tilePixel(x: number, c: TeamColors, art: RGB): RGB {
  const dimmed = art.map((v) => v * TILE.artBrightness) as RGB;
  if (!c.known) return dimmed;
  const home = rgbOf(c.home), away = rgbOf(c.away), a = TILE.washAlpha;
  const wash = sample([{ at: 0, rgb: home, a }, { at: 0.5, rgb: home, a: 0 }, { at: 0.5, rgb: away, a: 0 }, { at: 1, rgb: away, a }], x);
  return over(wash, dimmed);
}
