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
  // US ratings only on the route (NR / unrated never match): the kids rule's G / PG / TV-* list as it is
  if (kids) p.certifications = kids.data.ratings.filter((r) => r !== "U").join("|");
  const maxRt = Math.min(Infinity, ...of(chips, "maxRuntime").map((c) => c.data.min));
  const minRt = Math.max(0, ...of(chips, "minRuntime").map((c) => c.data.min));
  if (maxRt !== Infinity) p.maxRuntime = String(maxRt);
  if (minRt > 0) p.minRuntime = String(minRt);
  const minRating = Math.max(0, ...of(chips, "minRating").map((c) => c.data.value));
  if (minRating > 0) p.minRating = String(minRating);
  const years = of(chips, "years");
  if (years.length) p.year = `${Math.min(...years.map((c) => c.data.from))}-${Math.max(...years.map((c) => c.data.to))}`;
  return list.map((kind) => ({ kind, params: { kind, ...p } }));
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
  return parts.length ? `${parts.join("; ")}.` : null;
}

/** Re-exported for the parity vectors. */
export { KIDS_RATINGS };
