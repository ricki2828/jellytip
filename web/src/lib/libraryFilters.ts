// Server-backed filters for the All movies / All shows grid (pure; tested in web/test/libraryFilters.test.ts).
// Every filter maps to a Jellyfin /Users/{id}/Items query param, so it only ever covers THIS library, not IPTV.

export type Kind = "movie" | "tv";
export type Watched = "all" | "unplayed" | "played";

export const SORTS: { key: string; label: string; by: string; order: string }[] = [
  { key: "added", label: "Recently added", by: "DateCreated,SortName", order: "Descending,Ascending" },
  { key: "az", label: "A–Z", by: "SortName", order: "Ascending" },
  { key: "release", label: "Release", by: "PremiereDate,SortName", order: "Descending,Ascending" },
  { key: "rating", label: "Rating", by: "CommunityRating,SortName", order: "Descending,Ascending" },
  { key: "runtime", label: "Runtime", by: "Runtime,SortName", order: "Descending,Ascending" },
  { key: "random", label: "Random", by: "Random", order: "Ascending" },
];

export const MIN_RATINGS = [6, 7, 8] as const;

export interface LibraryFilters {
  sort: string;
  genres: string[];
  years: number[];
  ratings: string[]; // OfficialRatings, e.g. "PG-13"
  minRating: number | null;
  watched: Watched;
  favorite: boolean;
}

export const DEFAULT_FILTERS: LibraryFilters = { sort: "added", genres: [], years: [], ratings: [], minRating: null, watched: "all", favorite: false };

export const PAGE = 60;
export const RANDOM_LIMIT = 2000;

export function sortFor(key: string) {
  return SORTS.find((s) => s.key === key) ?? SORTS[0];
}

/** Filters that narrow the result set (the sort is not one). */
export function isFiltered(f: LibraryFilters): boolean {
  return f.genres.length > 0 || f.years.length > 0 || f.ratings.length > 0 || f.minRating !== null || f.watched !== "all" || f.favorite;
}

const uniq = <T>(xs: T[]) => [...new Set(xs)];

/** Jellyfin Items params. Genres / ratings are `|`-joined (any of), years `,`-joined, Filters `,`-joined (all of). */
export function toQuery(kind: Kind, f: LibraryFilters, start: number): Record<string, string | number | boolean | undefined> {
  const s = sortFor(f.sort);
  const random = s.by === "Random"; // re-shuffles on every request, so pages would overlap: take the library in one go
  const flags: string[] = [];
  if (f.watched === "unplayed") flags.push("IsUnplayed");
  if (f.watched === "played") flags.push("IsPlayed");
  if (f.favorite) flags.push("IsFavorite");
  return {
    IncludeItemTypes: kind === "movie" ? "Movie" : "Series",
    Recursive: true,
    SortBy: s.by,
    SortOrder: s.order,
    StartIndex: random ? 0 : start,
    Limit: random ? RANDOM_LIMIT : PAGE,
    Genres: f.genres.length ? f.genres.join("|") : undefined,
    Years: f.years.length ? [...f.years].sort((a, b) => a - b).join(",") : undefined,
    OfficialRatings: f.ratings.length ? f.ratings.join("|") : undefined,
    MinCommunityRating: f.minRating ?? undefined,
    Filters: flags.length ? flags.join(",") : undefined,
    EnableTotalRecordCount: true,
  };
}

/** Facet query for the pickers (Jellyfin /Items/Filters). */
export function facetsQuery(kind: Kind) {
  return { IncludeItemTypes: kind === "movie" ? "Movie" : "Series", Recursive: true };
}

export interface Facets {
  genres: string[];
  years: number[];
  ratings: string[];
}

export function parseFacets(raw: unknown): Facets {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const strs = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x !== "") : []);
  const years = (Array.isArray(o.Years) ? o.Years : []).filter((y): y is number => typeof y === "number" && Number.isFinite(y)).sort((a, b) => b - a);
  return { genres: strs(o.Genres).sort((a, b) => a.localeCompare(b)), years: uniq(years), ratings: strs(o.OfficialRatings).sort((a, b) => a.localeCompare(b)) };
}

export interface Chip {
  id: string; // stable key, also what removeChip takes
  label: string;
}

export function activeChips(f: LibraryFilters): Chip[] {
  const c: Chip[] = [];
  for (const g of f.genres) c.push({ id: `g:${g}`, label: g });
  for (const y of f.years) c.push({ id: `y:${y}`, label: String(y) });
  for (const r of f.ratings) c.push({ id: `r:${r}`, label: r });
  if (f.minRating !== null) c.push({ id: "min", label: `${f.minRating}+ rating` });
  if (f.watched !== "all") c.push({ id: "w", label: f.watched === "unplayed" ? "Unwatched" : "Watched" });
  if (f.favorite) c.push({ id: "fav", label: "Favourites" });
  return c;
}

export function removeChip(f: LibraryFilters, id: string): LibraryFilters {
  if (id.startsWith("g:")) return { ...f, genres: f.genres.filter((g) => g !== id.slice(2)) };
  if (id.startsWith("y:")) return { ...f, years: f.years.filter((y) => String(y) !== id.slice(2)) };
  if (id.startsWith("r:")) return { ...f, ratings: f.ratings.filter((r) => r !== id.slice(2)) };
  if (id === "min") return { ...f, minRating: null };
  if (id === "w") return { ...f, watched: "all" };
  if (id === "fav") return { ...f, favorite: false };
  return f;
}

export const clearAll = (f: LibraryFilters): LibraryFilters => ({ ...DEFAULT_FILTERS, sort: f.sort });

export function toggle<T>(xs: T[], x: T): T[] {
  return xs.includes(x) ? xs.filter((v) => v !== x) : [...xs, x];
}

// ---------- state round-trip (sessionStorage, scoped to the signed-in user + media kind) ----------

export interface StorageLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

export function parseFilters(raw: unknown): LibraryFilters {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const strs = (v: unknown) => (Array.isArray(v) ? uniq(v.filter((x): x is string => typeof x === "string" && x !== "")) : []);
  const watched: Watched = o.watched === "unplayed" || o.watched === "played" ? o.watched : "all";
  const min = typeof o.minRating === "number" && Number.isFinite(o.minRating) ? o.minRating : null;
  return {
    sort: typeof o.sort === "string" && SORTS.some((s) => s.key === o.sort) ? o.sort : DEFAULT_FILTERS.sort,
    genres: strs(o.genres),
    years: uniq((Array.isArray(o.years) ? o.years : []).filter((y): y is number => typeof y === "number" && Number.isFinite(y))),
    ratings: strs(o.ratings),
    minRating: min,
    watched,
    favorite: o.favorite === true,
  };
}

/** Key is per user AND kind; no signed-in user means nothing is stored or restored. */
export function stateKey(userId: string, kind: Kind): string | null {
  return userId ? `jt.library.v1.${userId}.${kind}` : null;
}

export function loadFilters(store: StorageLike | null, userId: string, kind: Kind): LibraryFilters {
  const k = stateKey(userId, kind);
  if (!store || !k) return { ...DEFAULT_FILTERS };
  try {
    const raw = store.getItem(k);
    return raw ? parseFilters(JSON.parse(raw)) : { ...DEFAULT_FILTERS };
  } catch {
    return { ...DEFAULT_FILTERS };
  }
}

export function saveFilters(store: StorageLike | null, userId: string, kind: Kind, f: LibraryFilters): void {
  const k = stateKey(userId, kind);
  if (!store || !k) return;
  try {
    store.setItem(k, JSON.stringify(f));
  } catch {
    /* storage full or blocked: filters just won't survive navigation */
  }
}
