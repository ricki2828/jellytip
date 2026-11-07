// Pure helpers for the clip + picks specials (closet_picks, four_favourites): parsing, per-pick availability, seek labels.
import { parseVodItems, type VodItem } from "./iptvVod.ts";

export interface ClipPickRaw {
  title: string;
  year?: number;
  itemId?: string | null;
  tmdbId?: number | null;
  posterUrl?: string | null;
  atSec?: number | null;
  iptv?: VodItem | null;
}

/** Drops malformed picks and normalises the IPTV copy the server may attach (four_favourites). */
export function parseClipPicks<T extends { title?: unknown; iptv?: unknown }>(raw: unknown): (T & { iptv?: VodItem | null })[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((p): p is T => !!p && typeof p === "object" && typeof (p as { title?: unknown }).title === "string")
    .map((p) => {
      const iptv = p.iptv ? (parseVodItems([p.iptv])[0] ?? null) : null;
      return { ...p, iptv };
    });
}

export type PickState = "library" | "iptv" | "get" | "none";

/** What tapping a poster does: library item > IPTV copy > admin "Get it" (needs a TMDB id) > nothing. */
export function pickState(p: { itemId?: string | null; iptv?: VodItem | null; tmdbId?: number | null }, inLibrary: boolean, admin: boolean): PickState {
  if (p.itemId && inLibrary) return "library";
  if (p.iptv) return "iptv";
  if (admin && p.tmdbId) return "get";
  return "none";
}

/** "0:42" / "1:05" for a seek chip. */
export function atLabel(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
