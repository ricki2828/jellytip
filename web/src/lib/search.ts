// Unified search (pure; tested in web/test/search.test.ts). Contract "Search API" in ~/ai/plans/jellytip-10x.md:
// GET /v1/search → grouped results + the plain-English filters the server read out of the query (shown as chips).

import { parseFixture, type Fixture } from "./matchInfo.ts";
import { parsePhase, parseStartsAt, type Phase } from "./preGame.ts";
import { isPre, startsLine } from "./preGame.ts";
import { timeChip } from "./upcoming.ts";
import { parseQuality, type Quality } from "./sport.ts";

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
  /** Recognised sport / league words ("cricket", "nba"): the live results lead for these. */
  sport?: string | null;
  league?: string | null;
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
  poster: string | null;
  backdrop: string | null;
  quality: string[];
  rating: number | null;
}
export interface DiscoverHit {
  tmdbId: number;
  mediaType: "movie" | "tv";
  title: string;
  year?: number | null;
  posterUrl?: string | null;
  requested?: string | null;
}

/** A programme airing now or soon on one of the caller's channels (`live` group; plan "E. Search covers live channels and events"). */
export interface LiveHit {
  key: string;
  title: string;
  start: number;
  stop: number;
  phase: Phase | null;
  startsAt: number | null;
  fixture: Fixture | null;
  sportName: string | null;
  league: string | null;
  replay: boolean;
  channel: { id: string; name: string; num: number | null; logo: string | null; quality: Quality | null };
  alternates: { id: string; name: string }[];
}

/** A channel whose name matched (`channels` group). */
export interface ChannelHit {
  id: string;
  name: string;
  num: number | null;
  logo: string | null;
  group: string | null;
  quality: Quality | null;
  now: { title: string; phase: Phase | null } | null;
  /** The match / event on the channel now (RedZone channels carry it on the channel itself). */
  fixture: Fixture | null;
}

const CHAN_ID = /^[A-Za-z0-9_-]{1,40}:[A-Za-z0-9_.-]{1,64}$/;

export function parseLiveHits(list: unknown): LiveHit[] {
  const out: LiveHit[] = [];
  const seen = new Set<string>();
  for (const x of arr(list)) {
    const o = obj(x);
    const title = str(o?.title);
    const start = num(o?.start);
    const stop = num(o?.stop);
    const ch = obj(o?.channel);
    const cid = str(ch?.id);
    if (!o || !title || start == null || stop == null || !ch || !cid || !CHAN_ID.test(cid)) continue;
    const key = `${cid}|${start}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      key,
      title,
      start,
      stop,
      phase: parsePhase(o.phase),
      startsAt: parseStartsAt(o.startsAt),
      fixture: parseFixture(o.matchInfo),
      sportName: str(o.sportName),
      league: str(o.league),
      replay: o.replay === true,
      channel: { id: cid, name: str(ch.name) ?? "Channel", num: num(ch.num), logo: str(ch.logo), quality: parseQuality(ch.quality) },
      alternates: arr(o.alternateChannels).flatMap((a) => {
        const c = obj(a);
        const id = str(c?.id);
        return c && id && CHAN_ID.test(id) ? [{ id, name: str(c.name) ?? "Channel" }] : [];
      }),
    });
  }
  return out.slice(0, 30);
}

export function parseChannelHits(list: unknown): ChannelHit[] {
  const out: ChannelHit[] = [];
  const seen = new Set<string>();
  for (const x of arr(list)) {
    const o = obj(x);
    const id = str(o?.id);
    const name = str(o?.name);
    if (!o || !id || !name || !CHAN_ID.test(id) || seen.has(id)) continue;
    seen.add(id);
    const now = obj(o.now);
    const nowTitle = str(now?.title);
    out.push({ id, name, num: num(o.num), logo: str(o.logo), group: str(o.group), quality: parseQuality(o.quality), now: nowTitle ? { title: nowTitle, phase: parsePhase(now?.phase) } : null, fixture: parseFixture(o.matchInfo ?? now?.matchInfo) });
  }
  return out.slice(0, 20);
}

/** One entry of the server's ranked `results` list (contract: ~/ai/plans/jellytip-search-10x.md "Pinned response fields"). */
export interface SearchEntry {
  key: string;
  kind: "film" | "series" | "person" | "collection";
  source: "library" | "iptv" | "discover";
  title: string;
  score: number;
  /** Library: Jellyfin item id (lower-case hex). */
  id?: string;
  iptvId?: string;
  tmdbId?: number | null;
  year?: number | null;
  rating?: number | null;
  played?: boolean;
  /** Series: TMDB counts, and the season / episode the query named. */
  seasons?: number | null;
  episodes?: number | null;
  season?: number | null;
  episode?: number | null;
  poster?: string | null;
  backdrop?: string | null;
  posterUrl?: string | null;
  quality: string[];
  tech: string[];
  requested?: string | null;
  personId?: string;
  role?: string;
  imageTag?: string | null;
  filmCount?: number | null;
  tmdbCollectionId?: number;
  inLibrary?: number;
  total?: number;
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
  /** Programmes airing now or soon, and channel-name matches; null when the server doesn't send the group. */
  live: LiveHit[] | null;
  channels: ChannelHit[] | null;
  /** A pure sport or league query: the live row goes first. */
  sportQuery: boolean;
  /** The ranked mixed list and its best hit; null when the server doesn't send `results` (then the groups are shown). */
  results: SearchEntry[] | null;
  top: SearchEntry | null;
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

const HEX = /^[0-9a-f]{32}$/i;
const IPTV_ID = /^[A-Za-z0-9_-]{1,40}:[ms]:[A-Za-z0-9_.-]{1,64}$/;

/** One ranked entry; null when it lacks what its kind and source need to open. */
export function parseEntry(raw: unknown): SearchEntry | null {
  const x = obj(raw);
  if (!x) return null;
  const kind = x.kind === "film" || x.kind === "series" || x.kind === "person" || x.kind === "collection" ? x.kind : null;
  const source = x.source === "library" || x.source === "iptv" || x.source === "discover" ? x.source : null;
  const title = str(x.title) ?? str(x.name);
  if (!kind || !source || !title) return null;
  const base: SearchEntry = {
    key: "",
    kind,
    source,
    title,
    score: num(x.score) ?? 0,
    year: num(x.year),
    rating: num(x.rating),
    played: x.played === true,
    seasons: num(x.seasons),
    episodes: num(x.episodes),
    season: num(x.season),
    episode: num(x.episode),
    tmdbId: num(x.tmdbId),
    poster: str(x.poster),
    backdrop: str(x.backdrop),
    posterUrl: str(x.posterUrl),
    quality: strs(x.quality),
    tech: strs(x.tech),
    requested: str(x.requested),
  };
  if (kind === "person") {
    const id = str(x.personId);
    return id && HEX.test(id) ? { ...base, key: `p:${id}`, personId: id, role: str(x.role) ?? undefined, imageTag: str(x.imageTag), filmCount: num(x.filmCount) } : null;
  }
  if (kind === "collection") {
    const id = num(x.tmdbCollectionId);
    return id != null ? { ...base, key: `c:${id}`, tmdbCollectionId: id, inLibrary: num(x.inLibrary) ?? undefined, total: num(x.total) ?? undefined, poster: str(x.poster) } : null;
  }
  if (source === "library") {
    const id = str(x.id);
    return id && HEX.test(id) ? { ...base, key: `l:${id.toLowerCase()}`, id: id.toLowerCase() } : null;
  }
  if (source === "iptv") {
    const id = str(x.iptvId);
    return id && IPTV_ID.test(id) ? { ...base, key: `i:${id}`, iptvId: id } : null;
  }
  return base.tmdbId != null ? { ...base, key: `d:${kind}:${base.tmdbId}` } : null;
}

/** `results` (best first, deduplicated by key) and `top`; null results when the field is absent (older server). */
export function parseResults(results: unknown, top: unknown): { results: SearchEntry[] | null; top: SearchEntry | null } {
  if (!Array.isArray(results)) return { results: null, top: null };
  const seen = new Set<string>();
  const list = results
    .map(parseEntry)
    .filter((e): e is SearchEntry => !!e && !seen.has(e.key) && !!seen.add(e.key))
    .map((e, i) => ({ e, i }))
    .sort((a, b) => b.e.score - a.e.score || a.i - b.i)
    .map((x) => x.e);
  const t = parseEntry(top);
  return { results: list, top: (t && list.find((e) => e.key === t.key)) ?? list[0] ?? null };
}

/** "Season 2" / "S2 E3" hint when the query named one, else "N seasons" for a show. */
export function seasonHint(e: Pick<SearchEntry, "kind" | "season" | "episode" | "seasons" | "episodes">): string | null {
  if (e.kind !== "series") return null;
  if (e.season != null) return e.episode != null ? `S${e.season} E${e.episode}` : `Season ${e.season}`;
  if (e.seasons) return `${e.seasons} season${e.seasons === 1 ? "" : "s"}${e.episodes ? ` · ${e.episodes} episodes` : ""}`;
  return null;
}

/** `live` and `channels` as groups ({type, items}) or as top-level lists; null when absent. */
function parseLiveGroups(r: Record<string, unknown>, g: unknown[], f: SearchFilters): { live: LiveHit[] | null; channels: ChannelHit[] | null; sportQuery: boolean } {
  const grp = (type: string) => g.map(obj).find((x) => x?.type === type)?.items;
  const live = grp("live") ?? r.live;
  const channels = grp("channels") ?? r.channels;
  return {
    live: Array.isArray(live) ? parseLiveHits(live) : null,
    channels: Array.isArray(channels) ? parseChannelHits(channels) : null,
    sportQuery: !!(f.sport || f.league),
  };
}

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
    sport: str(f.sport),
    league: str(f.league),
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
        ? { iptvId: id, name, year: num(x.year), kind: x.kind === "series" || id.includes(":s:") ? "series" : "movie", tech: strs(x.tech), poster: str(x.poster), backdrop: str(x.backdrop), quality: strs(x.quality), rating: num(x.rating) }
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
    ...parseResults(r.results, r.top),
    ...parseLiveGroups(r, g, filters),
  };
}

export const isEmpty = (s: SearchResult) =>
  s.results
    ? !(s.results.length + s.lists.length + s.discover.length + (s.live?.length ?? 0) + (s.channels?.length ?? 0))
    : !((s.live?.length ?? 0) + (s.channels?.length ?? 0) + s.films.length + s.shows.length + s.people.length + s.collections.length + s.lists.length + s.iptv.length + s.discover.length);

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

/** "LIVE" / "REPLAY" / "PRE-GAME · Starts 18:00 · in 35 min" / "Today 19:30". */
export function livePill(h: LiveHit, now: number): { text: string; tone: "rec" | "pre" | "time" | "dim" } {
  if (h.replay) return { text: "REPLAY", tone: "dim" };
  if (isPre(h.startsAt, now, h.phase)) return { text: `PRE-GAME · ${startsLine(h.startsAt!, now)}`, tone: "pre" };
  if (h.start <= now && now < h.stop) return { text: "LIVE", tone: "rec" };
  return { text: timeChip({ start: h.start, stop: h.stop }, new Date(now * 1000)), tone: "time" };
}


/** An IPTV group hit as a ranked-list entry (for a late answer that only carries the `iptv` group). */
const iptvEntry = (h: IptvHit): SearchEntry => ({ key: `i:${h.iptvId}`, kind: h.kind === "series" ? "series" : "film", source: "iptv", title: h.name, score: 0, iptvId: h.iptvId, year: h.year, rating: h.rating, poster: h.poster, backdrop: h.backdrop, quality: h.quality, tech: h.tech });

/**
 * A late answer for part of a search (`pending` named "iptv" or "discover"; the client re-asks with `types=` that group):
 * merge it into the answer already on screen. The IPTV copies replace the IPTV entries of the ranked list (and the group);
 * nothing else on screen changes, so no row jumps.
 */
export function mergeLate(r: SearchResult, late: SearchResult, what: "iptv" | "discover"): SearchResult {
  const pending = r.pending.filter((p) => p !== what);
  if (what === "discover") return { ...r, discover: late.discover, pending };
  let results = r.results;
  if (results) {
    const fromLate = (late.results ?? late.iptv.map(iptvEntry)).filter((e) => e.source === "iptv");
    const have = new Set(results.filter((e) => e.source !== "iptv").map((e) => e.key));
    results = [...results.filter((e) => e.source !== "iptv"), ...fromLate.filter((e) => !have.has(e.key))];
  }
  return { ...r, iptv: late.iptv, results, pending };
}
