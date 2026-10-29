// IPTV films / series in the normal pages (pure; tested in web/test/iptvVod.test.ts). Contract: ~/ai/plans/jellytip-iptv.md
// "IPTV movies / series" and "Rows + franchises". Anything with source "iptv" gets the corner dot + "via IPTV".

export interface VodProgress {
  position: number;
  duration: number;
  played?: boolean;
}

export interface VodItem {
  id: string;
  source: "iptv";
  kind: "movie" | "series";
  tmdbId?: number | null;
  title: string;
  year?: number | null;
  releaseDate?: string | null;
  quality: string[];
  poster?: string | null;
  backdrop?: string | null;
  overview?: string | null;
  rating?: number | null;
  providerName?: string;
  category?: string | null;
  progress?: VodProgress | null;
}

export interface VodEpisode {
  id: string;
  episodeId: string;
  episode: number;
  title?: string | null;
  runtime?: number | null;
  overview?: string | null;
  still?: string | null;
  progress?: VodProgress | null;
}

export interface VodDetail extends VodItem {
  runtime?: number | null;
  genre?: string | null;
  cast?: string | null;
  director?: string | null;
  copies?: { id: string; providerTitle?: string; quality?: string[] }[];
  seasons?: { season: number; episodes: VodEpisode[] }[];
}

/** Franchise gap-fill: a part the library lacks but the person's IPTV has. */
export interface IptvPart {
  id: string;
  title: string;
  year?: number | null;
  quality?: string[];
  poster?: string | null;
  backdrop?: string | null;
  providerName?: string;
  rating?: number | null;
}

export const VOD_ID_RE = /^[A-Za-z0-9_-]{1,40}:[ms]:[A-Za-z0-9_.-]{1,64}$/;
const EP_ID_RE = /^[A-Za-z0-9_-]{1,40}:e:[A-Za-z0-9_.-]{1,64}$/;

const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && isFinite(v) ? v : null);
const strs = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && !!x) : []);

function progress(v: unknown): VodProgress | null {
  const p = v as Partial<VodProgress> | null;
  if (!p || typeof p !== "object" || !num(p.duration)) return null;
  return { position: num(p.position) ?? 0, duration: p.duration!, played: p.played === true };
}

/** One VodItem; null when it has no usable id or title. */
export function parseVodItem(raw: unknown): VodItem | null {
  const r = raw as Record<string, unknown> | null;
  if (!r || typeof r !== "object") return null;
  const id = str(r.id);
  const title = str(r.title);
  if (!id || !VOD_ID_RE.test(id) || !title) return null;
  return {
    id,
    source: "iptv",
    kind: r.kind === "series" || id.includes(":s:") ? "series" : "movie",
    tmdbId: num(r.tmdbId),
    title,
    year: num(r.year),
    releaseDate: str(r.releaseDate),
    quality: strs(r.quality),
    poster: str(r.poster),
    backdrop: str(r.backdrop),
    overview: str(r.overview),
    rating: num(r.rating),
    providerName: str(r.providerName) ?? undefined,
    category: str(r.category),
    progress: progress(r.progress),
  };
}

export function parseVodItems(raw: unknown): VodItem[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: VodItem[] = [];
  for (const x of raw) {
    const v = parseVodItem(x);
    if (v && !seen.has(v.id)) {
      seen.add(v.id);
      out.push(v);
    }
  }
  return out;
}

/** /vod/{id}: the item plus details and (series) seasons with valid episode ids only. */
export function parseVodDetail(raw: unknown): VodDetail | null {
  const base = parseVodItem(raw);
  if (!base) return null;
  const r = raw as Record<string, unknown>;
  const seasons = Array.isArray(r.seasons)
    ? (r.seasons as Record<string, unknown>[])
        .filter((s) => s && num(s.season) != null)
        .map((s) => ({
          season: num(s.season)!,
          episodes: (Array.isArray(s.episodes) ? (s.episodes as Record<string, unknown>[]) : [])
            .filter((e) => e && typeof e.id === "string" && EP_ID_RE.test(e.id))
            .map((e) => ({
              id: e.id as string,
              episodeId: str(e.episodeId) ?? (e.id as string).split(":e:")[1],
              episode: num(e.episode) ?? 0,
              title: str(e.title),
              runtime: num(e.runtime),
              overview: str(e.overview),
              still: str(e.still),
              progress: progress(e.progress),
            })),
        }))
        .filter((s) => s.episodes.length > 0)
        .sort((a, b) => a.season - b.season)
    : undefined;
  return { ...base, runtime: num(r.runtime), genre: str(r.genre), cast: str(r.cast), director: str(r.director), seasons };
}

/** The franchise part's IPTV copy, when the server attached one. */
export function iptvPart(p: { source?: unknown; iptv?: unknown } | null | undefined): IptvPart | null {
  if (!p || p.source !== "iptv") return null;
  const i = p.iptv as Record<string, unknown> | null;
  const id = str(i?.id);
  if (!i || !id || !VOD_ID_RE.test(id)) return null;
  return { id, title: str(i.title) ?? "", year: num(i.year), quality: strs(i.quality), poster: str(i.poster), backdrop: str(i.backdrop), providerName: str(i.providerName) ?? undefined, rating: num(i.rating) };
}

/** Short quality label for a tile (best first). */
export function qualityLabel(q: string[] | undefined): string | null {
  if (!q?.length) return null;
  if (q.includes("4k")) return q.includes("hdr") ? "4K HDR" : "4K";
  if (q.includes("1080p")) return "HD";
  return null;
}

/** Route to an IPTV title's details. */
export const vodPath = (id: string) => `/iptv/item?id=${encodeURIComponent(id)}`;
