// Unified search (pure; tested in web/test/search.test.ts). Contract "Search API" in ~/ai/plans/jellytip-10x.md:
// GET /v1/search → grouped results + the plain-English filters the server read out of the query (shown as chips).

export interface SearchFilters {
  decade?: number | null;
  country?: string | null;
  genre?: string | null;
  runtimeMax?: number | null;
  unwatched?: boolean | null;
  person?: string | null;
  tech?: string | null;
}

export type FilterKey = keyof SearchFilters;

export interface LibHit {
  id: string;
  name: string;
  year?: number | null;
  kind: string;
}
export interface PersonHit {
  personId: string;
  name: string;
  role?: string;
  imageTag?: string | null;
  filmCount?: number | null;
}
export interface CollectionHit {
  tmdbCollectionId: number;
  name: string;
  inLibrary?: number;
  total?: number;
}
export interface ListHit {
  slug: string;
  name: string;
  count?: number;
  source?: string;
}
export interface IptvHit {
  iptvId: string;
  name: string;
  year?: number | null;
  kind: "movie" | "series";
  tech: string[];
}
export interface DiscoverHit {
  tmdbId: number;
  mediaType: "movie" | "tv";
  title: string;
  year?: number | null;
  posterUrl?: string | null;
  requested?: string | null;
}

export interface SearchResult {
  q: string;
  text: string;
  filters: SearchFilters;
  films: LibHit[];
  shows: LibHit[];
  people: PersonHit[];
  collections: CollectionHit[];
  lists: ListHit[];
  iptv: IptvHit[];
  discover: DiscoverHit[];
  techs: unknown;
  logoStyles: unknown;
  untitled: string[];
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const strs = (v: unknown) => arr(v).filter((x): x is string => typeof x === "string" && !!x);

function items<T>(groups: unknown[], type: string, pick: (r: Record<string, unknown>) => T | null): T[] {
  const g = groups.map(obj).find((x) => x?.type === type);
  return arr(g?.items)
    .map(obj)
    .map((r) => (r ? pick(r) : null))
    .filter((x): x is T => x != null);
}

const lib = (r: Record<string, unknown>): LibHit | null => {
  const id = str(r.id);
  const name = str(r.name);
  return id && /^[0-9a-f]{32}$/i.test(id) && name ? { id: id.toLowerCase(), name, year: num(r.year), kind: str(r.kind) ?? "" } : null;
};

/** Server answer → typed groups; anything malformed drops out. null when it isn't a search answer at all. */
export function parseSearch(raw: unknown): SearchResult | null {
  const r = obj(raw);
  if (!r || !Array.isArray(r.groups)) return null;
  const g = r.groups as unknown[];
  const parsed = obj(r.parsed);
  const f = obj(parsed?.filters) ?? {};
  const filters: SearchFilters = {
    decade: num(f.decade),
    country: str(f.country),
    genre: str(f.genre),
    runtimeMax: num(f.runtimeMax),
    unwatched: f.unwatched === true ? true : null,
    person: str(f.person),
    tech: str(f.tech),
  };
  return {
    q: str(r.q) ?? "",
    text: str(parsed?.text) ?? "",
    filters,
    films: items(g, "films", lib),
    shows: items(g, "shows", lib),
    people: items(g, "people", (x) => {
      const id = str(x.personId);
      const name = str(x.name);
      return id && /^[0-9a-f]{32}$/i.test(id) && name ? { personId: id, name, role: str(x.role) ?? undefined, imageTag: str(x.imageTag), filmCount: num(x.filmCount) } : null;
    }),
    collections: items(g, "collections", (x) => {
      const id = num(x.tmdbCollectionId);
      const name = str(x.name);
      return id && name ? { tmdbCollectionId: id, name, inLibrary: num(x.inLibrary) ?? undefined, total: num(x.total) ?? undefined } : null;
    }),
    lists: items(g, "lists", (x) => {
      const slug = str(x.slug);
      const name = str(x.name);
      return slug && name ? { slug, name, count: num(x.count) ?? undefined, source: str(x.source) ?? undefined } : null;
    }),
    iptv: items(g, "iptv", (x) => {
      const id = str(x.iptvId);
      const name = str(x.name);
      return id && /^[A-Za-z0-9_-]{1,40}:[ms]:[A-Za-z0-9_.-]{1,64}$/.test(id) && name
        ? { iptvId: id, name, year: num(x.year), kind: x.kind === "series" || id.includes(":s:") ? "series" : "movie", tech: strs(x.tech) }
        : null;
    }),
    discover: items(g, "discover", (x) => {
      const id = num(x.tmdbId);
      const title = str(x.title);
      return id && title ? { tmdbId: id, mediaType: x.mediaType === "tv" ? "tv" : "movie", title, year: num(x.year), posterUrl: str(x.posterUrl), requested: str(x.requested) } : null;
    }),
    techs: r.techs,
    logoStyles: r.logoStyles,
    untitled: strs(r.untitled),
  };
}

export const isEmpty = (s: SearchResult) => !(s.films.length + s.shows.length + s.people.length + s.collections.length + s.lists.length + s.iptv.length + s.discover.length);

// ---------- filter chips ----------

const COUNTRY: Record<string, string> = {
  FR: "French", KR: "Korean", JP: "Japanese", ES: "Spanish", IT: "Italian", DE: "German", CN: "Chinese", HK: "Hong Kong", IN: "Indian",
  SE: "Swedish", DK: "Danish", NO: "Norwegian", MX: "Mexican", BR: "Brazilian", GB: "British", US: "American", NZ: "New Zealand",
  AU: "Australian", IE: "Irish", RU: "Russian", IR: "Iranian", TH: "Thai", TW: "Taiwanese", AR: "Argentinian", BE: "Belgian", NL: "Dutch",
};
const TECH: Record<string, string> = { "4K": "4K", DV: "Dolby Vision", "HDR10+": "HDR10+", HDR: "HDR", ATMOS: "Atmos", DTSX: "DTS:X" };

export interface Chip {
  key: FilterKey;
  label: string;
  /** Words that bring this filter back when the query is rebuilt (the server re-reads them). */
  phrase: string | null;
}

const runtime = (m: number) => (m % 60 === 0 ? `${m / 60}h` : `${m} min`);

/** The filters the server read, as removable chips (label for the chip, phrase for re-asking without the others). */
export function filterChips(f: SearchFilters): Chip[] {
  const out: Chip[] = [];
  if (f.decade) out.push({ key: "decade", label: f.decade >= 2000 ? `${f.decade}s` : `${String(f.decade).slice(2)}s`, phrase: `${f.decade}s` });
  if (f.country) {
    const c = COUNTRY[f.country.toUpperCase()];
    out.push({ key: "country", label: c ?? f.country, phrase: c ? c.toLowerCase() : null });
  }
  if (f.genre) out.push({ key: "genre", label: f.genre, phrase: f.genre.toLowerCase() });
  if (f.runtimeMax) out.push({ key: "runtimeMax", label: `under ${runtime(f.runtimeMax)}`, phrase: `under ${runtime(f.runtimeMax)}` });
  if (f.unwatched) out.push({ key: "unwatched", label: "Unwatched", phrase: "unwatched" });
  if (f.person) out.push({ key: "person", label: `with ${f.person}`, phrase: `with ${f.person}` });
  if (f.tech) {
    const t = TECH[f.tech.toUpperCase()] ?? f.tech;
    out.push({ key: "tech", label: t, phrase: t.toLowerCase() });
  }
  return out;
}

/**
 * The query again without one filter: the free text the server kept plus the phrases of the filters that stay.
 * (The contract echoes the parsed filters, not where they were in the query, so the query is rebuilt.)
 */
export function withoutFilter(text: string, f: SearchFilters, drop: FilterKey): string {
  const keep = filterChips(f).filter((c) => c.key !== drop && c.phrase);
  // Genre reads best before the text ("crime heist"), the rest after
  const genre = keep.find((c) => c.key === "genre");
  const rest = keep.filter((c) => c.key !== "genre").map((c) => c.phrase);
  return [genre?.phrase, text, ...rest].filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
}
