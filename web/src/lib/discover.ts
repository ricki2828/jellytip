// Discover (admin): TMDB titles from the companion, parsed tolerantly (pure; tested in web/test/discover.test.ts).

export type MediaType = "movie" | "tv";
export type DiscoverSection = "trending" | "popular" | "upcoming" | "new" | "genre";

export interface DiscoverTitle {
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  year: number | null;
  overview: string;
  rating: number | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  /** none | requested | downloading | in_library (inline from the server, or merged from /v1/request/status). */
  state: string | null;
  progress: number | null;
  /** Jellyfin id when it's in the library. */
  itemId: string | null;
}

export interface Genre {
  id: number;
  name: string;
}

export const titleKey = (t: { mediaType: MediaType; tmdbId: number }) => `${t.mediaType}:${t.tmdbId}`;

const str = (v: unknown) => (typeof v === "string" && v.trim() && v !== "null" ? v : null);
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : typeof v === "string" && v.trim() && Number.isFinite(+v) ? +v : null);

export function parseTitle(raw: unknown, fallbackType: MediaType): DiscoverTitle | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const tmdbId = num(o.tmdbId ?? o.id);
  const title = str(o.title) ?? str(o.name);
  if (!tmdbId || tmdbId <= 0 || !title) return null;
  const t = str(o.mediaType) ?? str(o.media_type);
  const date = str(o.releaseDate) ?? str(o.release_date) ?? str(o.firstAirDate) ?? str(o.first_air_date);
  const reqd = o.requested ?? o.status;
  const status = reqd && typeof reqd === "object" ? (reqd as Record<string, unknown>) : null;
  return {
    tmdbId,
    mediaType: t === "tv" || t === "movie" ? t : fallbackType,
    title,
    year: num(o.year) ?? (date ? num(date.slice(0, 4)) : null),
    overview: str(o.overview) ?? "",
    rating: num(o.rating ?? o.vote_average),
    posterUrl: str(o.posterUrl),
    backdropUrl: str(o.backdropUrl),
    state: o.inLibrary === true ? "in_library" : str(o.state) ?? str(status?.state),
    progress: num(o.progress ?? status?.progress),
    itemId: str(o.jellyfinId) ?? str(o.itemId),
  };
}

/** A list answer: `{items|results: [...]}` or a bare array. Duplicates dropped, order kept. */
export function parseTitles(raw: unknown, fallbackType: MediaType): DiscoverTitle[] {
  const arr = Array.isArray(raw) ? raw : raw && typeof raw === "object" ? ((raw as Record<string, unknown>).items ?? (raw as Record<string, unknown>).results) : null;
  if (!Array.isArray(arr)) return [];
  const seen = new Set<string>();
  const out: DiscoverTitle[] = [];
  for (const r of arr) {
    const t = parseTitle(r, fallbackType);
    if (!t || seen.has(titleKey(t))) continue;
    seen.add(titleKey(t));
    out.push(t);
  }
  return out;
}

export function parseGenres(raw: unknown): Genre[] {
  const arr = Array.isArray(raw) ? raw : raw && typeof raw === "object" ? (raw as Record<string, unknown>).genres : null;
  if (!Array.isArray(arr)) return [];
  return arr
    .map((g) => (g && typeof g === "object" ? { id: num((g as Record<string, unknown>).id), name: str((g as Record<string, unknown>).name) } : null))
    .filter((g): g is Genre => !!g && g.id != null && !!g.name);
}

/** /v1/request/status answer `{status: {"movie:123": {state, progress}}}` merged into the titles. */
export function withStatuses(titles: DiscoverTitle[], raw: unknown): DiscoverTitle[] {
  const map = raw && typeof raw === "object" ? (raw as Record<string, unknown>).status : null;
  if (!map || typeof map !== "object") return titles;
  return titles.map((t) => {
    const s = (map as Record<string, unknown>)[titleKey(t)];
    if (!s || typeof s !== "object") return t;
    const o = s as Record<string, unknown>;
    return { ...t, state: str(o.state) ?? t.state, progress: num(o.progress) ?? t.progress, itemId: str(o.itemId) ?? t.itemId };
  });
}
