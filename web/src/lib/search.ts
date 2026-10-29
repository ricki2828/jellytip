// Unified search (pure; tested in web/test/search.test.ts). Contract "Search API" in ~/ai/plans/jellytip-10x.md:
// GET /v1/search → grouped results + the plain-English filters the server read out of the query (shown as chips).

export interface SearchFilters {
  decade?: number | null;
  year?: number | null;
  country?: string | null;
  /** ISO 639-1 original language ("fr"); comes with `country` for words like "french". */
  language?: string | null;
  genre?: string | null;
  runtimeMax?: number | null;
  runtimeMin?: number | null;
  unwatched?: boolean | null;
  /** "movie" | "series". */
  kind?: string | null;
  person?: string | null;
  /** Formats, all required ("4k atmos" → ["4K", "ATMOS"]); older servers sent one string. */
  tech?: string[] | null;
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
  /** Groups the server is still fetching (Discover waits at most 40 ms for TMDB): ask again for just those. */
  pending: string[];
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
    year: num(f.year),
    country: str(f.country),
    language: str(f.language),
    genre: str(f.genre),
    runtimeMax: num(f.runtimeMax),
    runtimeMin: num(f.runtimeMin),
    unwatched: f.unwatched === true ? true : null,
    kind: str(f.kind),
    person: str(f.person),
    tech: Array.isArray(f.tech) ? strs(f.tech) : str(f.tech) ? [str(f.tech)!] : null,
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
      // Live server (29-Oct) sends `inLibrary` as the parts list instead of a count: count the ones with an item
      const parts = Array.isArray(x.inLibrary) ? (x.inLibrary as unknown[]).map(obj) : null;
      // (every itemId null there, e.g. for the shared token: no count rather than a misleading "0 of 26")
      const owned = parts ? parts.filter((pt) => str(pt?.itemId)).length : null;
      const inLibrary = parts ? owned || undefined : num(x.inLibrary) ?? undefined;
      const total = num(x.total) ?? (parts ? parts.length : undefined);
      return id && name ? { tmdbCollectionId: id, name, inLibrary, total } : null;
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
    pending: strs(r.pending),
  };
}

export const isEmpty = (s: SearchResult) => !(s.films.length + s.shows.length + s.people.length + s.collections.length + s.lists.length + s.iptv.length + s.discover.length);

// ---------- filter chips ----------

const COUNTRY: Record<string, string> = {
  FR: "French", KR: "Korean", JP: "Japanese", ES: "Spanish", IT: "Italian", DE: "German", CN: "Chinese", HK: "Hong Kong", IN: "Indian",
  SE: "Swedish", DK: "Danish", NO: "Norwegian", MX: "Mexican", BR: "Brazilian", GB: "British", US: "American", NZ: "New Zealand",
  AU: "Australian", IE: "Irish", RU: "Russian", IR: "Iranian", TH: "Thai", TW: "Taiwanese", AR: "Argentinian", BE: "Belgian", NL: "Dutch",
};
const LANGUAGE: Record<string, string> = {
  fr: "French", ko: "Korean", ja: "Japanese", es: "Spanish", it: "Italian", de: "German", zh: "Chinese", cn: "Cantonese", hi: "Hindi",
  sv: "Swedish", da: "Danish", no: "Norwegian", pt: "Portuguese", ru: "Russian", fa: "Persian", th: "Thai", nl: "Dutch", mi: "Māori", en: "English",
};
const TECH: Record<string, string> = { "4K": "4K", DV: "Dolby Vision", "HDR10+": "HDR10+", HDR: "HDR", ATMOS: "Atmos", DTSX: "DTS:X" };

export interface Chip {
  /** Unique per chip ("tech:ATMOS" for each format). */
  id: string;
  key: FilterKey;
  /** Every filter the chip stands for ("French" = country FR + language fr). */
  keys: FilterKey[];
  label: string;
  /** Words that bring this filter back when the query is rebuilt (the server re-reads them). */
  phrase: string | null;
}

const runtime = (m: number) => (m % 60 === 0 ? `${m / 60}h` : `${m} min`);

/** The filters the server read, as removable chips (label for the chip, phrase for re-asking without the others). */
export function filterChips(f: SearchFilters): Chip[] {
  const out: Chip[] = [];
  const add = (key: FilterKey, label: string, phrase: string | null, keys: FilterKey[] = [key], id: string = key) => out.push({ id, key, keys, label, phrase });
  if (f.decade) add("decade", f.decade >= 2000 ? `${f.decade}s` : `${String(f.decade).slice(2)}s`, `${f.decade}s`);
  if (f.year) add("year", String(f.year), String(f.year));
  const lang = f.language ? LANGUAGE[f.language.toLowerCase()] : undefined;
  if (f.country) {
    const c = COUNTRY[f.country.toUpperCase()];
    // "french" comes back as country FR + language fr: one chip
    const both = !!lang && !!c && c === lang;
    add("country", c ?? f.country, c ? c.toLowerCase() : null, both ? ["country", "language"] : ["country"]);
    if (lang && !both) add("language", `${lang} language`, null);
  } else if (f.language) add("language", lang ?? f.language, lang ? lang.toLowerCase() : null);
  if (f.genre) add("genre", f.genre, f.genre.toLowerCase());
  if (f.runtimeMax) add("runtimeMax", `under ${runtime(f.runtimeMax)}`, `under ${runtime(f.runtimeMax)}`);
  if (f.runtimeMin) add("runtimeMin", `over ${runtime(f.runtimeMin)}`, null);
  if (f.kind) add("kind", f.kind === "series" || f.kind === "tv" ? "Shows" : "Films", f.kind === "series" || f.kind === "tv" ? "shows" : "films");
  if (f.unwatched) add("unwatched", "Unwatched", "unwatched");
  if (f.person) add("person", `with ${f.person}`, `with ${f.person}`);
  for (const v of f.tech ?? []) {
    const t = TECH[v.toUpperCase()] ?? v;
    add("tech", t, t.toLowerCase(), ["tech"], `tech:${v.toUpperCase()}`);
  }
  return out;
}

/**
 * The query again without one filter: the free text the server kept plus the phrases of the filters that stay.
 * (The contract echoes the parsed filters, not where they were in the query, so the query is rebuilt.)
 */
export function withoutFilter(text: string, f: SearchFilters, drop: string): string {
  const chips = filterChips(f);
  // `drop` is a chip id ("tech:ATMOS" drops just that format) or a filter key (drops everything it covers)
  const target = chips.find((c) => c.id === drop);
  const keep = chips.filter((c) => (target ? c !== target && !(target.key !== "tech" && c.keys.some((k) => target.keys.includes(k))) : !c.keys.includes(drop as FilterKey)) && c.phrase);
  // Genre reads best before the text ("crime heist"), the rest after
  const genre = keep.find((c) => c.key === "genre");
  const rest = keep.filter((c) => c.key !== "genre").map((c) => c.phrase);
  return [genre?.phrase, text, ...rest].filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
}
