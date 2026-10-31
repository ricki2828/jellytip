// "Broadcast" look (plan: ~/ai/plans/jellytip-live-sport.md "In-match visual direction: DECIDED 31-Oct"): team colours with a clash rule,
// readable text on them, and the whole-panel wash. Pure; tested in web/test/iptv.test.ts.
import type { Fixture } from "./matchInfo.ts";

export const GOLD = "#FFC72C";
export const BASE = "#0D1015";
const NEUTRAL: [string, string] = ["#3b3f46", "#2a2d33"];

const rgb = (hex: string): [number, number, number] | null => {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};

const lin = (v: number) => {
  const c = v / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};

/** Relative luminance 0..1. */
export const luminance = (hex: string): number => {
  const c = rgb(hex);
  return c ? 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]) : 0;
};

/** CIE76 colour difference between two hex colours (0 = identical; under ~20 reads as "the same colour" on screen). */
export function deltaE(a: string, b: string): number {
  const lab = (hex: string): [number, number, number] => {
    const c = rgb(hex) ?? [0, 0, 0];
    const [r, g, bl] = c.map(lin) as [number, number, number];
    const x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047;
    const y = 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    const z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883;
    const f = (t: number) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
    return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
  };
  const [l1, a1, b1] = lab(a);
  const [l2, a2, b2] = lab(b);
  return Math.hypot(l1 - l2, a1 - a2, b1 - b2);
}

/** Black or white, whichever contrasts more with the colour (WCAG contrast against the actual colour, not a fixed choice). */
export function onColor(hex: string): "#000000" | "#ffffff" {
  const L = luminance(hex);
  return (L + 0.05) / 0.05 > 1.05 / (L + 0.05) ? "#000000" : "#ffffff";
}

/** Blend a colour over the base at `alpha` (the wash is drawn once as a static gradient from this). */
export function blend(hex: string, alpha: number, base = BASE): string {
  const c = rgb(hex);
  const b = rgb(base)!;
  if (!c) return base;
  const m = c.map((v, i) => Math.round(v * alpha + b[i] * (1 - alpha)));
  return `#${m.map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

export interface TeamColors {
  home: string;
  away: string;
  /** False when neither side had a colour: no wash, the panel stays the neutral base. */
  known: boolean;
}

/** The two team colours: the away side uses its alt colour when the primaries clash (ΔE < 20); neutral greys when unknown. */
export function teamColors(f: Pick<Fixture, "home" | "away"> | null | undefined): TeamColors {
  if (!f) return { home: NEUTRAL[0], away: NEUTRAL[1], known: false };
  const h = f.home.color;
  let a = f.away.color;
  if (h && a && deltaE(h, a) < 20 && f.away.altColor) a = f.away.altColor;
  return { home: h ?? NEUTRAL[0], away: a ?? NEUTRAL[1], known: !!(h || a) };
}

/** Whole-panel two-tone wash: home colour left half, away right half, each at 22% over the base; a flat base when colours are unknown. */
export function washBackground(c: TeamColors, alpha = 0.22): string {
  if (!c.known) return BASE;
  return `linear-gradient(90deg, ${blend(c.home, alpha)} 0 50%, ${blend(c.away, alpha)} 50% 100%)`;
}
