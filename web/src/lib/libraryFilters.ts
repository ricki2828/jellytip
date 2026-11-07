// Server-backed filters for the All movies / All shows grid (pure; tested in web/test/libraryFilters.test.ts).
// Every filter maps to a Jellyfin /Users/{id}/Items query param, so it only ever covers THIS library, not IPTV.

export type Kind = "movie" | "tv";
export type Watched = "all" | "unplayed" | "played";

export type Dir = "asc" | "desc";

/** `by` is the Jellyfin ItemSortBy field (Jellyfin 10.9+/12.x names); `dir` is the direction a fresh pick starts in. */
export const SORTS: { key: string; label: string; by: string; dir: Dir; words: [string, string] }[] = [
  // words = [descending, ascending] description for the direction toggle
  { key: "added", label: "Recently added", by: "DateCreated", dir: "desc", words: ["Newest added", "Oldest added"] },
  { key: "az", label: "A–Z", by: "SortName", dir: "asc", words: ["Z to A", "A to Z"] },
  { key: "release", label: "Release", by: "PremiereDate", dir: "desc", words: ["Newest first", "Oldest first"] },
  { key: "rating", label: "Rating", by: "CommunityRating", dir: "desc", words: ["Highest first", "Lowest first"] },
  { key: "critic", label: "Critic score", by: "CriticRating", dir: "desc", words: ["Highest first", "Lowest first"] },
  { key: "runtime", label: "Runtime", by: "Runtime", dir: "desc", words: ["Longest first", "Shortest first"] },
  { key: "plays", label: "Most watched", by: "PlayCount", dir: "desc", words: ["Most watched", "Least watched"] },
  { key: "watched", label: "Last watched", by: "DatePlayed", dir: "desc", words: ["Latest first", "Earliest first"] },
  { key: "random", label: "Random", by: "Random", dir: "asc", words: ["Random", "Random"] },
];

export const DECADES = [2020, 2010, 2000, 1990, 1980, 1970, 1960, 1950] as const;
export type RuntimeBand = "" | "short" | "mid" | "long";
export type Res = "" | "4k" | "hd";
export const RUNTIME_BANDS: [RuntimeBand, string][] = [
  ["short", "Under 90 min"],
  ["mid", "90–120 min"],
  ["long", "Over 120 min"],
];

export const MIN_RATINGS = [6, 7, 8] as const;

export interface Ref {
  id: string;
  name: string;
}

export interface LibraryFilters {
  sort: string;
  dir: Dir | null; // null = the sort's own default direction
  genres: string[];
  years: number[];
  ratings: string[]; // OfficialRatings, e.g. "PG-13"
  minRating: number | null;
  watched: Watched;
  favorite: boolean;
  decades: number[]; // decade start years, e.g. 1990
  runtime: RuntimeBand; // movies only; client-side (Jellyfin has no runtime filter)
  res: Res;
  subs: boolean;
  studios: Ref[];
  people: Ref[];
}

export const DEFAULT_FILTERS: LibraryFilters = { sort: "added", dir: null, genres: [], years: [], ratings: [], minRating: null, watched: "all", favorite: false, decades: [], runtime: "", res: "", subs: false, studios: [], people: [] };

export const PAGE = 60;
export const RANDOM_LIMIT = 2000;

export function sortFor(key: string) {
  return SORTS.find((s) => s.key === key) ?? SORTS[0];
}

export const effectiveDir = (f: LibraryFilters): Dir => f.dir ?? sortFor(f.sort).dir;
/** "Newest first", "Shortest first", ... for the direction toggle. */
export const dirWords = (f: LibraryFilters): string => sortFor(f.sort).words[effectiveDir(f) === "desc" ? 0 : 1];
/** Flip the direction (stored as null again when it matches the sort's default). */
export function flipDir(f: LibraryFilters): LibraryFilters {
  const next: Dir = effectiveDir(f) === "desc" ? "asc" : "desc";
  return { ...f, dir: next === sortFor(f.sort).dir ? null : next };
}
export const setSort = (f: LibraryFilters, key: string): LibraryFilters => ({ ...f, sort: key, dir: null });

/** Filters that narrow the result set (the sort is not one). */
export function isFiltered(f: LibraryFilters): boolean {
  return (
    f.genres.length > 0 || f.years.length > 0 || f.ratings.length > 0 || f.minRating !== null || f.watched !== "all" || f.favorite ||
    f.decades.length > 0 || f.runtime !== "" || f.res !== "" || f.subs || f.studios.length > 0 || f.people.length > 0
  );
}

/** Years param: explicit years plus every year of each chosen decade (Jellyfin takes a comma list). */
export function yearsParam(f: LibraryFilters): string | undefined {
  const all = new Set<number>(f.years);
  for (const d of f.decades) for (let y = d; y < d + 10; y++) all.add(y);
  return all.size ? [...all].sort((a, b) => a - b).join(",") : undefined;
}

const TICKS_PER_MIN = 600_000_000;
/** Runtime has no Jellyfin Items param, so bands are applied to each fetched page. */
export function runtimeOk(band: RuntimeBand, ticks: number | undefined): boolean {
  if (!band) return true;
  if (!ticks) return false;
  const min = ticks / TICKS_PER_MIN;
  return band === "short" ? min < 90 : band === "mid" ? min >= 90 && min <= 120 : min > 120;
}
export const needsClientFilter = (kind: Kind, f: LibraryFilters) => kind === "movie" && f.runtime !== "";

const uniq = <T>(xs: T[]) => [...new Set(xs)];

/** Jellyfin Items params. Genres / ratings are `|`-joined (any of), years `,`-joined, Filters `,`-joined (all of). */
export function toQuery(kind: Kind, f: LibraryFilters, start: number): Record<string, string | number | boolean | undefined> {
  const s = sortFor(f.sort);
  const dir = effectiveDir(f) === "desc" ? "Descending" : "Ascending";
  const random = s.by === "Random"; // re-shuffles on every request, so pages would overlap: take the library in one go
  const flags: string[] = [];
  if (f.watched === "unplayed") flags.push("IsUnplayed");
  if (f.watched === "played") flags.push("IsPlayed");
  if (f.favorite) flags.push("IsFavorite");
  return {
    IncludeItemTypes: kind === "movie" ? "Movie" : "Series",
    Recursive: true,
    SortBy: random || s.by === "SortName" ? s.by : `${s.by},SortName`,
    SortOrder: random ? "Ascending" : s.by === "SortName" ? dir : `${dir},Ascending`,
    StartIndex: random ? 0 : start,
    Limit: random ? RANDOM_LIMIT : PAGE,
    Genres: f.genres.length ? f.genres.join("|") : undefined,
    Years: yearsParam(f),
    Is4K: f.res === "4k" ? true : undefined,
    IsHD: f.res === "hd" ? true : undefined,
    HasSubtitles: f.subs ? true : undefined,
    StudioIds: f.studios.length ? f.studios.map((r) => r.id).join("|") : undefined,
    PersonIds: f.people.length ? f.people.map((r) => r.id).join("|") : undefined,
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
  for (const d of [...f.decades].sort((a, b) => b - a)) c.push({ id: `d:${d}`, label: `${d}s` });
  for (const y of f.years) c.push({ id: `y:${y}`, label: String(y) });
  for (const r of f.ratings) c.push({ id: `r:${r}`, label: r });
  if (f.minRating !== null) c.push({ id: "min", label: `${f.minRating}+ rating` });
  if (f.watched !== "all") c.push({ id: "w", label: f.watched === "unplayed" ? "Unwatched" : "Watched" });
  if (f.favorite) c.push({ id: "fav", label: "Favourites" });
  if (f.runtime) c.push({ id: "rt", label: RUNTIME_BANDS.find(([k]) => k === f.runtime)?.[1] ?? f.runtime });
  if (f.res) c.push({ id: "res", label: f.res === "4k" ? "4K" : "HD" });
  if (f.subs) c.push({ id: "subs", label: "Has subtitles" });
  for (const r of f.studios) c.push({ id: `st:${r.id}`, label: r.name });
  for (const r of f.people) c.push({ id: `p:${r.id}`, label: r.name });
  return c;
}

export function removeChip(f: LibraryFilters, id: string): LibraryFilters {
  if (id.startsWith("g:")) return { ...f, genres: f.genres.filter((g) => g !== id.slice(2)) };
  if (id.startsWith("y:")) return { ...f, years: f.years.filter((y) => String(y) !== id.slice(2)) };
  if (id.startsWith("r:")) return { ...f, ratings: f.ratings.filter((r) => r !== id.slice(2)) };
  if (id === "min") return { ...f, minRating: null };
  if (id === "w") return { ...f, watched: "all" };
  if (id === "fav") return { ...f, favorite: false };
  if (id.startsWith("d:")) return { ...f, decades: f.decades.filter((d) => String(d) !== id.slice(2)) };
  if (id === "rt") return { ...f, runtime: "" };
  if (id === "res") return { ...f, res: "" };
  if (id === "subs") return { ...f, subs: false };
  if (id.startsWith("st:")) return { ...f, studios: f.studios.filter((r) => r.id !== id.slice(3)) };
  if (id.startsWith("p:")) return { ...f, people: f.people.filter((r) => r.id !== id.slice(2)) };
  return f;
}

export const clearAll = (f: LibraryFilters): LibraryFilters => ({ ...DEFAULT_FILTERS, sort: f.sort, dir: f.dir });

export function toggle<T>(xs: T[], x: T): T[] {
  return xs.includes(x) ? xs.filter((v) => v !== x) : [...xs, x];
}

// ---------- state round-trip (sessionStorage, scoped to the signed-in user + media kind) ----------

export interface StorageLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

const ID_RE = /^[0-9a-f]{32}$/i;
/** {id,name} pairs, ids must be Jellyfin ids (they go straight into query strings). */
export function parseRefs(v: unknown): Ref[] {
  const out: Ref[] = [];
  for (const x of Array.isArray(v) ? v : []) {
    const o = x && typeof x === "object" ? (x as Record<string, unknown>) : {};
    const id = typeof o.id === "string" ? o.id.toLowerCase() : "";
    const name = typeof o.name === "string" ? o.name.slice(0, 80) : "";
    if (ID_RE.test(id) && name && !out.some((r) => r.id === id)) out.push({ id, name });
  }
  return out;
}

/** Jellyfin /Studios and /Persons search results → refs. */
export function parseSearchItems(raw: unknown): Ref[] {
  const items = raw && typeof raw === "object" ? (raw as { Items?: unknown }).Items : null;
  return parseRefs((Array.isArray(items) ? items : []).map((i) => ({ id: (i as { Id?: unknown })?.Id, name: (i as { Name?: unknown })?.Name })));
}

export function parseFilters(raw: unknown): LibraryFilters {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const strs = (v: unknown) => (Array.isArray(v) ? uniq(v.filter((x): x is string => typeof x === "string" && x !== "")) : []);
  const watched: Watched = o.watched === "unplayed" || o.watched === "played" ? o.watched : "all";
  const min = typeof o.minRating === "number" && Number.isFinite(o.minRating) ? o.minRating : null;
  const sort = typeof o.sort === "string" && SORTS.some((s) => s.key === o.sort) ? o.sort : DEFAULT_FILTERS.sort;
  const dir: Dir | null = (o.dir === "asc" || o.dir === "desc") && o.dir !== sortFor(sort).dir && sort !== "random" ? o.dir : null;
  const nums = (v: unknown) => uniq((Array.isArray(v) ? v : []).filter((y): y is number => typeof y === "number" && Number.isFinite(y)));
  return {
    sort,
    dir,
    decades: nums(o.decades).filter((d) => (DECADES as readonly number[]).includes(d)),
    runtime: o.runtime === "short" || o.runtime === "mid" || o.runtime === "long" ? o.runtime : "",
    res: o.res === "4k" || o.res === "hd" ? o.res : "",
    subs: o.subs === true,
    studios: parseRefs(o.studios),
    people: parseRefs(o.people),
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

// ---------- URL round-trip (#/movies/all?sort=…&g=Action|Drama) so a filtered view can be shared or re-opened ----------

const KEYS = ["sort", "dir", "g", "y", "d", "r", "min", "w", "fav", "rt", "res", "subs", "st", "pp"] as const;

export function filtersToParams(f: LibraryFilters): URLSearchParams {
  const q = new URLSearchParams();
  if (f.sort !== DEFAULT_FILTERS.sort) q.set("sort", f.sort);
  if (f.dir) q.set("dir", f.dir);
  if (f.genres.length) q.set("g", f.genres.join("|"));
  if (f.years.length) q.set("y", f.years.join(","));
  if (f.decades.length) q.set("d", f.decades.join(","));
  if (f.ratings.length) q.set("r", f.ratings.join("|"));
  if (f.minRating !== null) q.set("min", String(f.minRating));
  if (f.watched !== "all") q.set("w", f.watched);
  if (f.favorite) q.set("fav", "1");
  if (f.runtime) q.set("rt", f.runtime);
  if (f.res) q.set("res", f.res);
  if (f.subs) q.set("subs", "1");
  if (f.studios.length) q.set("st", f.studios.map((r) => `${r.id}~${r.name}`).join("|"));
  if (f.people.length) q.set("pp", f.people.map((r) => `${r.id}~${r.name}`).join("|"));
  return q;
}

/** null when the URL carries no filter keys at all (then the stored state applies). */
export function filtersFromParams(q: URLSearchParams): LibraryFilters | null {
  if (!KEYS.some((k) => q.has(k))) return null;
  const list = (k: string, sep: string) => (q.get(k) ?? "").split(sep).filter(Boolean);
  const nums = (k: string) => list(k, ",").map(Number).filter(Number.isFinite);
  const refs = (k: string) =>
    list(k, "|").map((p) => {
      const i = p.indexOf("~");
      return { id: p.slice(0, i), name: p.slice(i + 1) };
    });
  return parseFilters({
    sort: q.get("sort") ?? DEFAULT_FILTERS.sort,
    dir: q.get("dir"),
    genres: list("g", "|"),
    years: nums("y"),
    decades: nums("d"),
    ratings: list("r", "|"),
    minRating: q.has("min") ? Number(q.get("min")) : null,
    watched: q.get("w"),
    favorite: q.get("fav") === "1",
    runtime: q.get("rt"),
    res: q.get("res"),
    subs: q.get("subs") === "1",
    studios: refs("st"),
    people: refs("pp"),
  });
}
