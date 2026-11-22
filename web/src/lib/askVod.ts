// Ask over IPTV films and series (GET v1/iptv/vod, live 22-Nov with t5q's certifications / minRuntime / maxRuntime).
// Pure: the same chips as the library query (askParse ChipData) become one IPTV query per kind plus a client-side check
// for what the route can't filter (genres: items carry TMDB genre ids; exact years; played). Tested in test/askVod.test.ts;
// the TV port (AskVod.kt) is held to vectors made from this file.
import { KIDS_RATINGS, kidsGenres, type Chip, type ChipData } from "./askParse.ts";

/** TMDB genre ids by the genre names Jellyfin uses (movie and TV lists). */
export const TMDB_GENRES: Record<string, number> = {
  action: 28, adventure: 12, animation: 16, comedy: 35, crime: 80, documentary: 99, drama: 18, family: 10751, fantasy: 14,
  history: 36, horror: 27, music: 10402, mystery: 9648, romance: 10749, "science fiction": 878, "tv movie": 10770,
  thriller: 53, war: 10752, western: 37, "action & adventure": 10759, kids: 10762, news: 10763, reality: 10764,
  "sci-fi & fantasy": 10765, soap: 10766, talk: 10767, "war & politics": 10768,
};

/** TMDB's movie genre ids and TV genre ids differ (TV has no Horror / Thriller; Kids, Sci-Fi & Fantasy are TV-only). */
export const MOVIE_GENRE_IDS = new Set([28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37]);
export const TV_GENRE_IDS = new Set([10759, 16, 35, 80, 99, 18, 10751, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37]);
/** The route takes at most this many ids per genre param (else 400). */
export const MAX_GENRE_IDS = 30;

/** The slice of an IPTV vod item the checks need. */
export interface VodView {
  kind: "movie" | "series";
  year: number | null;
  genreIds: number[];
  certification: string | null;
  runtime: number | null;
  played: boolean | null;
}

export interface VodQuery {
  kind: "movie" | "series";
  params: Record<string, string>;
}

const of = <T extends ChipData["t"]>(chips: Chip[], t: T) => chips.filter((c): c is Chip & { data: Extract<ChipData, { t: T }> } => c.data.t === t);
const ids = (genres: string[]) => [...new Set(genres.map((g) => TMDB_GENRES[g.toLowerCase()]).filter((x): x is number => x != null))];

/** How many IPTV titles one ask fetches per kind (the route's own maximum). */
export const VOD_LIMIT = 200;
/** The route's largest runtime filter (minutes). */
export const MAX_RUNTIME = 1000;

/**
 * The IPTV queries for the chips: none when a chip can't be applied to IPTV at all (a person: the catalogue has no
 * cast), else one per kind (the route takes one kind). Ratings / runtime / rating floor / a year span go to the route;
 * the rest is checked by [vodKeep].
 */
export function vodQueries(chips: Chip[]): VodQuery[] {
  if (of(chips, "person").length) return [];
  const kinds = of(chips, "kind").map((c) => c.data.kind);
  const wantSeasons = of(chips, "maxSeasons").length > 0;
  const list: ("movie" | "series")[] = wantSeasons ? ["series"] : kinds.length === 1 ? [kinds[0] === "Movie" ? "movie" : "series"] : ["movie", "series"];
  const p: Record<string, string> = { limit: String(VOD_LIMIT), sort: "rating" };
  const kids = of(chips, "kids")[0];
  // US ratings only on the route (NR / unrated never match): the kids rule's G / PG / TV-* list as it is. None left (e.g.
  // only "U") = no IPTV at all: an empty list would mean no rating filter on the route (fail open)
  if (kids) {
    const certs = kids.data.ratings.filter((r) => r !== "U");
    if (!certs.length) return [];
    p.certifications = certs.join("|");
  }
  const maxRt = Math.min(Infinity, ...of(chips, "maxRuntime").map((c) => c.data.min));
  const minRt = Math.max(0, ...of(chips, "minRuntime").map((c) => c.data.min));
  // The route takes 1..1000 minutes (else 400 for the whole query): a length beyond that is no filter there
  if (maxRt !== Infinity && maxRt <= MAX_RUNTIME) p.maxRuntime = String(maxRt);
  if (minRt > 0 && minRt <= MAX_RUNTIME) p.minRuntime = String(minRt);
  const minRating = Math.max(0, ...of(chips, "minRating").map((c) => c.data.value));
  if (minRating > 0) p.minRating = String(minRating);
  const years = of(chips, "years");
  if (years.length) p.year = `${Math.min(...years.map((c) => c.data.from))}-${Math.max(...years.map((c) => c.data.to))}`;
  // Genres on the route (vodgenre, live 22-Nov; any-of, ids of THAT kind): one mood chip = its genres; no mood but kids = the
  // kids genres; several moods = none on the route (each must match: the device checks). Left-out genres: excludeGenreIds.
  // A kind that has none of the one mood's genres can't match it: no query for that kind.
  const moods = of(chips, "genre");
  const want = moods.length === 1 ? moods[0].data.genres : moods.length === 0 && of(chips, "kids").length ? kidsGenres(chips) : [];
  const out = of(chips, "notGenre").flatMap((c) => c.data.genres);
  const forKind = (genres: string[], kind: "movie" | "series") => ids(genres).filter((g) => (kind === "movie" ? MOVIE_GENRE_IDS : TV_GENRE_IDS).has(g)).slice(0, MAX_GENRE_IDS);
  return list.flatMap((kind) => {
    const q: Record<string, string> = { kind, ...p };
    if (want.length) {
      const g = forKind(want, kind);
      if (!g.length) return [];
      q.genreIds = g.join(",");
    }
    const x = forKind(out, kind);
    if (x.length) q.excludeGenreIds = x.join(",");
    return [{ kind, params: q }];
  });
}

/**
 * The client-side check over the IPTV page: every mood chip matched (any of its genres), no left-out genre, the kids
 * genres when no mood chose any, the exact years (the route took their span), played state. Seasons can't be checked
 * on IPTV (no count in the list): a season limit keeps only series and says so in the note.
 */
export function vodKeep(it: VodView, chips: Chip[]): boolean {
  const have = new Set(it.genreIds);
  for (const c of of(chips, "notGenre")) if (ids(c.data.genres).some((g) => have.has(g))) return false;
  const moods = of(chips, "genre");
  if (!moods.every((c) => ids(c.data.genres).some((g) => have.has(g)))) return false;
  const kg = kidsGenres(chips);
  if (of(chips, "kids").length && moods.length === 0 && kg.length && !ids(kg).some((g) => have.has(g))) return false;
  const years = of(chips, "years");
  if (years.length && (it.year == null || !years.some((c) => it.year! >= c.data.from && it.year! <= c.data.to))) return false;
  if (of(chips, "unplayed").length && it.played === true) return false;
  if (of(chips, "played").length && it.played !== true) return false;
  return true;
}

/** One line about what the IPTV half could and couldn't apply (shown beside the coverage note). */
export function vodNote(chips: Chip[]): string | null {
  if (of(chips, "person").length) return "IPTV isn't searched by director or actor.";
  const parts: string[] = [];
  if (of(chips, "kids").length) parts.push("IPTV titles without a known age rating are left out");
  if (of(chips, "maxRuntime").length || of(chips, "minRuntime").length) parts.push("IPTV titles without a known length are left out");
  if (of(chips, "maxSeasons").length) parts.push("IPTV season counts aren't known");
  // A genre IPTV can't name (not in the TMDB table): nothing on IPTV can match it
  const unknown = of(chips, "genre").filter((c) => ids(c.data.genres).length === 0).map((c) => c.label);
  if (unknown.length) parts.push(`IPTV can't match ${unknown.map((l) => `“${l}”`).join(", ")}`);
  return parts.length ? `${parts.join("; ")}.` : null;
}

/** What the checks see of a list item (the route already applied ratings / lengths; it doesn't send them back). */
export function vodViewOf(v: { kind: "movie" | "series"; year?: number | null; genreIds?: number[]; played?: boolean }): VodView {
  return { kind: v.kind, year: v.year ?? null, genreIds: v.genreIds ?? [], certification: null, runtime: null, played: v.played ?? null };
}

/**
 * Ask's IPTV half: the route's answers (already rating / length / genre-filtered), checked again here, minus titles the
 * library picks already show (same TMDB id), at most `cap` (the library picks come first; IPTV fills after them).
 */
export function askIptvPicks<V extends { id: string; kind: "movie" | "series"; tmdbId?: number | null; year?: number | null; genreIds?: number[]; played?: boolean }>(
  fetched: V[],
  chips: Chip[],
  libraryTmdb: ReadonlySet<string>,
  cap: number,
): { shown: V[]; kept: number } {
  const seen = new Set<string>();
  const kept = fetched.filter((v) => {
    if (seen.has(v.id) || !vodKeep(vodViewOf(v), chips)) return false;
    seen.add(v.id);
    return true;
  });
  const shown = kept.filter((v) => v.tmdbId == null || !libraryTmdb.has(`${v.kind}:${v.tmdbId}`)).slice(0, Math.max(0, cap));
  return { shown, kept: kept.length };
}

/** Re-exported for the parity vectors. */
export { KIDS_RATINGS };
