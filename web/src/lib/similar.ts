// Pure side of "More like this" (tested in web/test/similar.test.ts): the route's answer as a mixed ServerRow.
import { parseVodItem } from "./iptvVod.ts";
import type { VodItem } from "./iptvVod.ts";
import type { ServerRow } from "./rows.ts";

export interface SimilarQuery {
  kind: "movie" | "series";
  tmdb?: number | null;
  iptvId?: string | null;
  jfId?: string | null;
}

const MAX_ITEMS = 20;

/** Library id of one answer entry: a bare id string, or an object carrying one of the usual id keys. */
function libraryId(x: unknown): string | null {
  if (typeof x === "string") return x || null;
  const r = x as Record<string, unknown> | null;
  if (!r || typeof r !== "object") return null;
  if (r.source === "library") return typeof r.id === "string" && r.id ? r.id : null;
  for (const k of ["jfId", "itemId", "Id", "libraryId"]) if (typeof r[k] === "string" && r[k]) return r[k] as string;
  return null;
}

/** The answer as a mixed row (library ids + IPTV titles in the server's order); null when it holds nothing usable. */
export function parseSimilar(raw: unknown, kind: "movie" | "series"): ServerRow | null {
  const items = (raw as { items?: unknown } | null)?.items;
  if (!Array.isArray(items)) return null;
  const itemIds: string[] = [];
  const iptvItems: VodItem[] = [];
  const order: string[] = [];
  const seen = new Set<string>();
  for (const x of items) {
    if (order.length >= MAX_ITEMS) break;
    const lib = libraryId(x);
    if (lib) {
      if (!seen.has(lib)) {
        seen.add(lib);
        itemIds.push(lib);
        order.push(lib);
      }
      continue;
    }
    const v = parseVodItem(x);
    if (v && !seen.has(v.id)) {
      seen.add(v.id);
      iptvItems.push(v);
      order.push(v.id);
    }
  }
  if (!order.length) return null;
  return {
    id: "similar",
    source: "jellytip",
    family: "similar",
    title: "More like this",
    layout: "standard",
    mediaType: kind === "series" ? "tv" : "movie",
    itemIds,
    untitled: [],
    person: null,
    list: null,
    collection: null,
    iptvItems,
    order,
    ranks: {},
    missing: [],
  };
}

export function similarPath(q: SimilarQuery): string | null {
  const p = new URLSearchParams({ kind: q.kind });
  if (q.tmdb && q.tmdb > 0) p.set("tmdb", String(q.tmdb));
  if (q.iptvId) p.set("iptvId", q.iptvId);
  if (q.jfId) p.set("jfId", q.jfId);
  return p.has("tmdb") || p.has("iptvId") || p.has("jfId") ? `v1/similar?${p}` : null;
}
