// Rows v1 manifest parsing (pure; tested in web/test/rows.test.ts). Contract: ~/ai/plans/jellytip-10x.md.
import { parseVodItems, type VodItem } from "./iptvVod.ts";

export type RowLayout = "standard" | "poster" | "franchise";


export interface CollectionPart {
  tmdbId: number;
  title: string;
  year?: number | null;
  itemId?: string | null;
  /** "iptv" when the library lacks it but the person's IPTV has it (then `iptv` holds that copy; see lib/iptvVod). */
  source?: string;
  iptv?: unknown;
}

export interface Collection {
  tmdbId: number;
  name: string;
  overview?: string | null;
  backdrop?: string | null;
  poster?: string | null;
  partsOrder: CollectionPart[];
  watchedCount: number;
  total: number;
  inLibrary: number;
  artConsistent?: boolean;
  itemIds: string[];
}

export interface ServerRow {
  id: string;
  source: string;
  family: string;
  title: string;
  /** Unknown layouts from a newer server come through as "standard". */
  layout: RowLayout;
  mediaType: "movie" | "tv";
  itemIds: string[];
  /** Items whose 16:9 Thumb has no title in it: those tiles draw the title. */
  untitled: string[];
  person?: { id: string; name: string; role: string; imageTag?: string | null } | null;
  list?: { slug: string; owner?: string; liked?: boolean } | null;
  collection?: Collection | null;
  /** source "iptv" rows: the person's IPTV titles (itemIds is empty). */
  iptvItems: VodItem[];
}

const ROW_LAYOUTS: RowLayout[] = ["standard", "poster", "franchise"];
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && !!x) : []);

export function parseCollection(c: Partial<Collection> | null | undefined): Collection | null {
  if (!c || typeof c.tmdbId !== "number" || !c.name) return null;
  return {
    ...c,
    tmdbId: c.tmdbId,
    name: c.name,
    partsOrder: Array.isArray(c.partsOrder) ? c.partsOrder.filter((p) => p && typeof p.tmdbId === "number") : [],
    watchedCount: Number(c.watchedCount) || 0,
    total: Number(c.total) || 0,
    inLibrary: Number(c.inLibrary) || 0,
    itemIds: strings(c.itemIds),
  };
}


/** Rows in server order; rows without an id, title or items are dropped. null = no `rows` field (old server). */
export function parseRows(raw: unknown): ServerRow[] | null {
  if (!Array.isArray(raw)) return null;
  const out: ServerRow[] = [];
  const seen = new Set<string>();
  for (const r of raw as (Partial<ServerRow> | null)[]) {
    const itemIds = [...new Set(strings(r?.itemIds))];
    const iptvItems = parseVodItems((r as { iptvItems?: unknown } | null)?.iptvItems);
    if (!r || typeof r.id !== "string" || seen.has(r.id) || !r.title || itemIds.length + iptvItems.length === 0) continue;
    seen.add(r.id);
    const collection = parseCollection(r.collection);
    let layout: RowLayout = ROW_LAYOUTS.includes(r.layout as RowLayout) ? (r.layout as RowLayout) : "standard";
    if (layout === "franchise" && !collection) layout = "standard";
    out.push({
      id: r.id,
      source: r.source ?? "jellytip",
      family: r.family ?? "",
      title: r.title,
      layout,
      mediaType: r.mediaType === "tv" ? "tv" : "movie",
      itemIds,
      untitled: strings(r.untitled),
      person: r.person?.id && r.person.name ? r.person : null,
      list: r.list ?? null,
      collection,
      iptvItems,
    });
  }
  return out;
}
