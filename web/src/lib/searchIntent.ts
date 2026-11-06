// Deterministic search-intent parsing (no LLM). Pure; tested in web/test/searchIntent.test.ts.
// The companion already reads plain-English filters; this layer decides how the screen presents the answer
// (Play first, season/episode jump, person first, live first) and what to ask Jellyfin for server-side on the fallback path.

export interface SearchIntent {
  raw: string;
  /** What is left after the intent words are removed: the title / free text. */
  text: string;
  /** "watch X" / "play X": put the playable best match first with a Play action. */
  play: boolean;
  season: number | null;
  episode: number | null;
  /** A person named by the query. `strong` = "movies with X"; weak = "X films" (only used when a People hit matches). */
  person: { name: string; strong: boolean } | null;
  /** "live X": live channels and sport lead. */
  live: boolean;
  /** Inclusive year range ("90s" = 1990-1999, "2019" = 2019-2019). */
  years: { from: number; to: number } | null;
  genre: string | null;
  kind: "movie" | "series" | null;
}

const GENRES: Record<string, string> = {
  thriller: "Thriller", thrillers: "Thriller", comedy: "Comedy", comedies: "Comedy", horror: "Horror", drama: "Drama", dramas: "Drama",
  action: "Action", "sci-fi": "Science Fiction", scifi: "Science Fiction", romance: "Romance", romcom: "Romance", romcoms: "Romance",
  documentary: "Documentary", documentaries: "Documentary", animation: "Animation", animated: "Animation", cartoon: "Animation",
  crime: "Crime", western: "Western", westerns: "Western", fantasy: "Fantasy", mystery: "Mystery", mysteries: "Mystery",
  war: "War", family: "Family", musical: "Music", musicals: "Music", history: "History", adventure: "Adventure",
};
const MOVIE_WORDS = new Set(["movie", "movies", "film", "films"]);
const SHOW_WORDS = new Set(["show", "shows", "series", "tv"]);

const clean = (s: string) => s.replace(/\s+/g, " ").trim();

export function parseIntent(raw: string): SearchIntent {
  const intent: SearchIntent = { raw, text: "", play: false, season: null, episode: null, person: null, live: false, years: null, genre: null, kind: null };
  let t = clean(raw);

  const play = /^(?:watch|play|stream|put on)\s+(.+)$/i.exec(t);
  if (play) {
    intent.play = true;
    t = play[1];
  }
  const live = /^(?:live|channel)\s+(.+)$/i.exec(t) ?? /^(.+?)\s+live$/i.exec(t);
  if (live) {
    intent.live = true;
    t = live[1];
  }

  // Season / episode. Order matters: the most specific form first.
  const se: [RegExp, number, number | null][] = [
    [/\bs(\d{1,2})\s*e(\d{1,3})\b/i, 1, 2],
    [/\bseason\s*(\d{1,2})\s*,?\s*(?:episode|ep)\s*(\d{1,3})\b/i, 1, 2],
    [/\b(\d{1,2})x(\d{1,3})\b/i, 1, 2],
    [/\bseason\s*(\d{1,2})\b/i, 1, null],
    [/\bs(\d{1,2})\b/i, 1, null],
  ];
  for (const [re, si, ei] of se) {
    const m = re.exec(t);
    if (!m) continue;
    intent.season = Number(m[si]);
    intent.episode = ei ? Number(m[ei]) : null;
    t = clean(t.replace(re, " "));
    break;
  }

  // Person: "movies with X" (strong) or "X films" (weak, needs a matching People hit)
  const strongP = /^(?:(?:movies|films|shows|series)\s+)?(?:with|starring|featuring)\s+(.+)$/i.exec(t) ?? /^(?:directed by|(?:movies|films) by)\s+(.+)$/i.exec(t);
  const filmsP = /^(.+?)\s+(?:films|movies|filmography)$/i.exec(t);
  if (strongP) {
    intent.person = { name: clean(strongP[1]), strong: true };
    t = intent.person.name;
  } else if (filmsP && !GENRES[filmsP[1].toLowerCase()] && !/\b(?:19|20)?\d0?s?\b/.test(filmsP[1])) {
    intent.person = { name: clean(filmsP[1]), strong: false };
    t = intent.person.name;
  }

  if (!intent.person) {
    // Year / decade, then genre and kind words around them
    let years: SearchIntent["years"] = null;
    const dec = /(?:^|\s)'?((?:19|20)?\d0)s\b/i.exec(t);
    const yr = /(?:^|\s)((?:19|20)\d{2})(?=\s|$)/.exec(t);
    if (dec) {
      const d = Number(dec[1]);
      const full = d < 100 ? (d >= 30 ? 1900 + d : 2000 + d) : d;
      years = { from: full, to: full + 9 };
      t = clean(t.replace(dec[0], " "));
    } else if (yr && Number(yr[1]) <= new Date().getFullYear() + 1) {
      years = { from: Number(yr[1]), to: Number(yr[1]) };
      t = clean(t.replace(yr[0], " "));
    }
    const words = t.split(" ").filter(Boolean);
    const hasKindWord = words.some((w) => MOVIE_WORDS.has(w.toLowerCase()) || SHOW_WORDS.has(w.toLowerCase()));
    const genreWord = words.find((w) => GENRES[w.toLowerCase()]);
    // A genre word is only a filter alongside a year or a kind word ("thriller" alone may be a title)
    if (genreWord && (years || hasKindWord)) {
      intent.genre = GENRES[genreWord.toLowerCase()];
      t = clean(words.filter((w) => w !== genreWord).join(" "));
    }
    if (years || intent.genre || hasKindWord) {
      const rest = t.split(" ").filter(Boolean);
      const kw = rest.find((w) => MOVIE_WORDS.has(w.toLowerCase()) || SHOW_WORDS.has(w.toLowerCase()));
      if (kw && (years || intent.genre || rest.length === 1)) {
        intent.kind = MOVIE_WORDS.has(kw.toLowerCase()) ? "movie" : "series";
        t = clean(rest.filter((w) => w !== kw).join(" "));
      }
    }
    intent.years = years;
  }
  intent.text = t;
  return intent;
}

/** True when the query carries a filter Jellyfin can apply server-side (Years / Genres). */
export const hasFilters = (i: SearchIntent) => !!(i.years || i.genre);

/** Jellyfin /Items params for the filtered part of an intent: year range and genre are applied by the server, never over a partial list. */
export function jellyfinParams(i: SearchIntent): Record<string, string | number | boolean> {
  const p: Record<string, string | number | boolean> = {
    IncludeItemTypes: i.kind === "movie" ? "Movie" : i.kind === "series" ? "Series" : "Movie,Series",
    Recursive: true,
    Limit: 40,
  };
  if (i.text) p.SearchTerm = i.text;
  if (i.years) {
    const ys: number[] = [];
    for (let y = i.years.from; y <= i.years.to && ys.length < 20; y++) ys.push(y);
    p.Years = ys.join(",");
  }
  if (i.genre) p.Genres = i.genre;
  if (!i.text) {
    p.SortBy = "CommunityRating,SortName";
    p.SortOrder = "Descending";
  }
  return p;
}

/** The text to send the server: the query as typed, minus the leading "watch/play" so title matching isn't polluted. */
export function serverQuery(i: SearchIntent): string {
  return i.play ? clean(i.raw).replace(/^(?:watch|play|stream|put on)\s+/i, "") : clean(i.raw);
}
