// The Home hero's featured mix (same idea as the TV's JellytipHeroMix): before anyone touches a tile the hero rotates
// through new releases, recently added titles and a few featured rows or specials, never Continue Watching, each
// slide labelled with a short kicker. Built only from what Home already loaded. Pure; tested in web/test/heroMix.test.ts.
import type { Item } from "../api/jellyfin";

export interface HeroSlide {
  item: Item;
  kicker: string;
}

export interface HeroSource {
  kicker: string;
  ids: string[];
}

export const SLIDE_MS = 8_000;
export const PER_SOURCE = 4;
export const MAX_SLIDES = 14;

interface RowLike {
  id: string;
  family: string;
  title: string;
  itemIds: string[];
  iptvItems: unknown[];
}

type SpecialLike =
  | { layout: "tonights_pick"; itemId: string }
  | { layout: "person_spotlight"; name: string; itemIds: string[] }
  | { layout: "because_loved"; anchorItemId: string }
  | { layout: "top10"; itemIds: string[] }
  | { layout: string };

/** Kicker for a server row that belongs in the hero mix; null = not featured. */
export function rowKicker(r: Pick<RowLike, "family" | "title">): string | null {
  const t = r.title.toLowerCase();
  if (t.includes("new release")) return "New release";
  if (t.includes("just added") || r.family === "fresh") return "Just added";
  if (t.startsWith("trending")) return "Trending";
  if (r.family === "ratings" || r.family === "awards") return "Highly rated";
  return null;
}

/** Where slides can come from, in the order the page has them; ids only (the page loads the items). */
export function heroSources(rows: RowLike[], specials: SpecialLike[]): HeroSource[] {
  const out: HeroSource[] = [];
  const add = (kicker: string, ids: string[]) => ids.length && out.push({ kicker, ids: ids.slice(0, PER_SOURCE * 2) });
  for (const r of rows) {
    const k = rowKicker(r);
    if (k && !r.iptvItems.length) add(k, r.itemIds);
  }
  for (const s of specials) {
    if (s.layout === "tonights_pick") add("Tonight's pick", [(s as { itemId: string | null }).itemId ?? ""].filter(Boolean));
    else if (s.layout === "person_spotlight") add(`${(s as { name: string }).name} spotlight`, (s as { itemIds: string[] }).itemIds);
    else if (s.layout === "because_loved") add("Because you loved it", [(s as { anchorItemId: string }).anchorItemId]);
    else if (s.layout === "top10") add("Top 10 today", (s as { itemIds: string[] }).itemIds);
  }
  return out;
}

const undashed = (id: string) => id.replace(/-/g, "");

/**
 * One slide per source in turn, a different source leading each day. Films and shows only, nothing the person is
 * already watching (`excluded` holds Continue Watching ids and their series), nothing watched, each title once.
 */
export function buildSlides(sources: { kicker: string; items: Item[] }[], excluded: Set<string>, dayOfYear: number): HeroSlide[] {
  const queues = sources
    .map((s) => ({
      kicker: s.kicker,
      items: s.items
        .filter((i) => (i.Type === "Movie" || i.Type === "Series") && !excluded.has(undashed(i.Id)) && i.UserData?.Played !== true)
        .filter((i, n, a) => a.findIndex((x) => x.Id === i.Id) === n)
        .slice(0, PER_SOURCE),
    }))
    .filter((q) => q.items.length);
  if (!queues.length) return [];
  const start = dayOfYear % queues.length;
  const order = [...queues.slice(start), ...queues.slice(0, start)];
  const seen = new Set<string>();
  const out: HeroSlide[] = [];
  while (out.length < MAX_SLIDES && order.some((q) => q.items.length)) {
    for (const q of order) {
      while (q.items.length) {
        const item = q.items.shift()!;
        if (!seen.has(item.Id)) {
          seen.add(item.Id);
          out.push({ item, kicker: q.kicker });
          break;
        }
      }
    }
  }
  return out.slice(0, MAX_SLIDES);
}

/** Ids to exclude: what Continue Watching / Next Up shows, and the series those episodes belong to. */
export function excludedIds(leading: Item[]): Set<string> {
  const s = new Set<string>();
  for (const i of leading) {
    s.add(undashed(i.Id));
    if (i.SeriesId) s.add(undashed(i.SeriesId));
  }
  return s;
}
