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
  /** Mixed rows (My list): every id (library undashed, vod ids) in display order, newest first. */
  order: string[];
  /** Trending Top 10 rows: rank (1-10) per id, ids as in `order` with dashes stripped. */
  ranks: Record<string, number>;
  /** Trending Top 10 rows: titles that are nowhere (library, IPTV), shown dimmed "Not available". */
  missing: MissingTitle[];
}

export interface MissingTitle {
  /** null: the server couldn't resolve it, so there is nothing for Get it to open. */
  tmdbId: number | null;
  title: string;
  year?: number | null;
  poster?: string | null;
  rank: number | null;
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


const undash = (id: string) => id.replace(/-/g, "").toLowerCase();

/** rank per id from `rank` / `ranks` ({id: n}) and from a `rank` on each IPTV entry. */
export function parseRanks(r: unknown, iptvRaw: unknown): Record<string, number> {
  const out: Record<string, number> = {};
  const o = r as { rank?: unknown; ranks?: unknown } | null;
  for (const m of [o?.rank, o?.ranks]) {
    if (m && typeof m === "object" && !Array.isArray(m)) for (const [k, v] of Object.entries(m)) if (typeof v === "number" && isFinite(v)) out[undash(k)] = v;
  }
  if (Array.isArray(iptvRaw))
    for (const x of iptvRaw as { id?: unknown; rank?: unknown }[]) if (typeof x?.id === "string" && typeof x.rank === "number" && isFinite(x.rank)) out[undash(x.id)] = x.rank;
  return out;
}

export function parseMissing(raw: unknown): MissingTitle[] {
  if (!Array.isArray(raw)) return [];
  const out: MissingTitle[] = [];
  for (const m of raw as Record<string, unknown>[]) {
    if (!m || typeof m.title !== "string" || !m.title) continue;
    out.push({ tmdbId: typeof m.tmdbId === "number" ? m.tmdbId : null, title: m.title, year: typeof m.year === "number" ? m.year : null, poster: typeof m.poster === "string" ? m.poster : typeof m.posterUrl === "string" ? m.posterUrl : null, rank: typeof m.rank === "number" ? m.rank : null });
  }
  return out;
}

/** One tile of a trending row, in rank order. */
export type TrendEntry = { rank: number } & ({ kind: "lib"; id: string } | { kind: "iptv"; item: VodItem } | { kind: "missing"; title: MissingTitle });

/** Library ids (given in `libIds`, already loaded and visible), IPTV titles and missing titles merged and sorted by rank.
 * Entries without a rank take their place in `order` (1-based); the numeral shown is the entry's rank. */
export function trendEntries(row: Pick<ServerRow, "order" | "ranks" | "iptvItems" | "missing">, libIds: string[], cap = 10): TrendEntry[] {
  const pos = new Map(row.order.map((id, i) => [undash(id), i + 1]));
  const rankOf = (id: string, fallback: number) => row.ranks[undash(id)] ?? pos.get(undash(id)) ?? fallback;
  const all: TrendEntry[] = [
    ...libIds.map((id, i) => ({ kind: "lib" as const, id, rank: rankOf(id, 100 + i) })),
    ...row.iptvItems.map((item, i) => ({ kind: "iptv" as const, item, rank: rankOf(item.id, 200 + i) })),
    ...row.missing.map((title, i) => ({ kind: "missing" as const, title, rank: title.rank ?? 300 + i })),
  ];
  return all.sort((a, b) => a.rank - b.rank).slice(0, cap);
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
      order: strings((r as { order?: unknown }).order),
      ranks: parseRanks(r, (r as { iptvItems?: unknown }).iptvItems),
      missing: parseMissing((r as { missing?: unknown }).missing),
    });
  }
  return out;
}

/** Tiles in a mixed row's display order: by `order` (ids as the server wrote them), the rest after in their given order. */
export function inOrder<T extends { id: string }>(tiles: T[], order: string[]): T[] {
  if (!order.length) return tiles;
  const rank = new Map(order.map((id, i) => [id, i]));
  return tiles
    .map((t, i) => ({ t, i }))
    .sort((x, y) => (rank.get(x.t.id) ?? Infinity) - (rank.get(y.t.id) ?? Infinity) || x.i - y.i)
    .map((x) => x.t);
}
