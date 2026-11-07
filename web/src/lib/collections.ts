// Collections hub (GET /v1/collections; contract: ~/ai/plans/jellytip-search-10x.md "C. Collections hub"). Pure; tested in
// web/test/iptv.test.ts.

export interface CollectionCard {
  id: string;
  title: string;
  poster: string | null;
  backdrop: string | null;
  /** The first library title (Jellyfin art) and, for director / actor rows, the person's portrait. */
  itemId: string | null;
  person: { id: string; imageTag: string | null } | null;
  mediaType: "movie" | "tv" | null;
  total: number;
  libCount: number;
  iptvCount: number;
  ranked: boolean;
}

export interface CollectionSection {
  key: string;
  title: string;
  count: number;
  items: CollectionCard[];
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : 0);

/** Sections in the server's order; empty ones and unusable cards are dropped. */
export function parseHub(raw: unknown): CollectionSection[] {
  const secs = obj(raw)?.sections;
  if (!Array.isArray(secs)) return [];
  const out: CollectionSection[] = [];
  for (const x of secs) {
    const o = obj(x);
    const key = str(o?.key);
    if (!o || !key) continue;
    const seen = new Set<string>();
    const items: CollectionCard[] = [];
    for (const y of Array.isArray(o.items) ? o.items : []) {
      const c = obj(y);
      const id = str(c?.id);
      const title = str(c?.title);
      if (!c || !id || !title || seen.has(id)) continue;
      seen.add(id);
      const art = obj(c.art);
      const per = obj(art?.person);
      const pid = str(per?.id);
      items.push({
        id,
        title,
        poster: str(art?.poster),
        backdrop: str(art?.backdrop),
        itemId: str(art?.itemId),
        person: pid ? { id: pid, imageTag: str(per?.imageTag) } : null,
        mediaType: c.mediaType === "tv" ? "tv" : c.mediaType === "movie" ? "movie" : null,
        total: num(c.total),
        libCount: num(c.libCount),
        iptvCount: num(c.iptvCount),
        ranked: c.ranked === true,
      });
    }
    if (items.length) out.push({ key, title: str(o.title) ?? key, count: num(o.count) || items.length, items });
  }
  return out;
}

/** "12 films · 5 on IPTV" (the IPTV part only when there is some). */
export function cardLine(c: Pick<CollectionCard, "total" | "libCount" | "iptvCount">): string {
  const n = c.total || c.libCount + c.iptvCount;
  const base = `${n} title${n === 1 ? "" : "s"}`;
  return c.iptvCount > 0 ? `${base} · ${c.iptvCount} on IPTV` : c.libCount > 0 && c.libCount < n ? `${base} · ${c.libCount} in the library` : base;
}

export type CollTab = "all" | "movie" | "tv";
export const COLL_TABS: readonly CollTab[] = ["movie", "tv", "all"];
const TAB_KEY = "jellytip-coll-tab";

/** Does a card belong on a tab? The hub card carries one mediaType (server default "movie"); unknown (null) shows on both. */
export function inTab(c: Pick<CollectionCard, "mediaType">, tab: CollTab): boolean {
  return tab === "all" || c.mediaType === null || c.mediaType === tab;
}

/** Sections narrowed to a tab: cards filtered, counts recomputed, empty sections dropped. "all" returns the input. */
export function filterHub(sections: CollectionSection[], tab: CollTab): CollectionSection[] {
  if (tab === "all") return sections;
  const out: CollectionSection[] = [];
  for (const s of sections) {
    const items = s.items.filter((c) => inTab(c, tab));
    if (items.length) out.push({ ...s, items, count: items.length });
  }
  return out;
}

export function loadTab(): CollTab {
  try {
    const v = localStorage.getItem(TAB_KEY);
    return v === "movie" || v === "tv" || v === "all" ? v : "movie";
  } catch {
    return "movie";
  }
}
export function saveTab(t: CollTab) {
  try {
    localStorage.setItem(TAB_KEY, t);
  } catch {
    /* private mode */
  }
}
