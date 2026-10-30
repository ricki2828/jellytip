// IPTV titles inside specials (pure; tested in web/test/iptv.test.ts).
import { parseVodItems, type VodItem } from "./iptvVod.ts";

export type Pairs = { a: string | null; b: string | null; aIptv?: VodItem | null; bIptv?: VodItem | null; reason?: string; totalRuntimeMin?: number }[];

/** double_feature pairs; each half is a library id or an IPTV title (aIptv / bIptv); pairs missing a half are dropped. */
export function parseFeaturePairs(raw: unknown): Pairs {
  if (!Array.isArray(raw)) return [];
  const out: Pairs = [];
  for (const p of raw as Record<string, unknown>[]) {
    const a = typeof p?.a === "string" && p.a ? p.a : null;
    const b = typeof p?.b === "string" && p.b ? p.b : null;
    const aIptv = a ? null : parseVodItems([p?.aIptv])[0] ?? null;
    const bIptv = b ? null : parseVodItems([p?.bIptv])[0] ?? null;
    if (!(a || aIptv) || !(b || bIptv)) continue;
    out.push({ a, b, aIptv, bIptv, reason: typeof p.reason === "string" ? p.reason : undefined, totalRuntimeMin: typeof p.totalRuntimeMin === "number" ? p.totalRuntimeMin : undefined });
  }
  return out;
}
