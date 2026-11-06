// Plain-language discovery (#/ask). Deterministic, no LLM: phrases map to Jellyfin Items params, and what cannot be
// mapped is reported back as "ignored" rather than guessed. Pure; tested in web/test/askParse.test.ts.
import type { Item } from "../api/jellyfin";

export type ChipData =
  | { t: "genre"; genres: string[] }
  | { t: "kind"; kind: "Movie" | "Series" }
  | { t: "maxRuntime"; min: number }
  | { t: "minRuntime"; min: number }
  | { t: "years"; from: number; to: number }
  | { t: "unplayed" }
  | { t: "played" }
  | { t: "kids"; ratings: string[]; genres: string[] }
  | { t: "minRating"; value: number }
  | { t: "maxSeasons"; n: number };

export interface Chip {
  /** Stable for a given reading, so a removed chip stays removed while the text is unchanged. */
  id: string;
  label: string;
  data: ChipData;
}

export interface Reading {
  chips: Chip[];
  /** Words that matched nothing (filler words are dropped silently). */
  ignored: string[];
}

export const KIDS_RATINGS = ["G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "U"];
export const KIDS_GENRES = ["Family", "Animation"];
/** How many matches are requested in one go: runtime and season limits are checked client-side over this page. */
export const FETCH_LIMIT = 300;
export const PICKS = 24;
export const EXAMPLES = [
  "something funny under 90 minutes",
  "a 70s thriller I haven't seen",
  "feel-good for the kids",
  "well rated sci-fi from the 90s",
  "short series, mind-bending",
  "scary movie I haven't seen",
];

const MOODS: [RegExp, string[], string][] = [
  [/\b(?:feel[- ]?good|heart-?warming|uplifting|wholesome)\b/, ["Comedy", "Family", "Animation"], "Feel-good"],
  [/\b(?:mind[- ]?bending|twisty|trippy|head[- ]?scratcher)\b/, ["Science Fiction", "Mystery"], "Mind-bending"],
  [/\b(?:funny|comedy|comedies|hilarious|laugh|comic)\b/, ["Comedy"], "Comedy"],
  [/\b(?:scary|horror|creepy|spooky|frightening|terrifying)\b/, ["Horror"], "Horror"],
  [/\b(?:tense|thriller|thrillers|suspenseful|suspense|gripping|edge of (?:my|your) seat)\b/, ["Thriller"], "Thriller"],
  [/\b(?:romantic|romance|rom-?coms?|date night)\b/, ["Romance"], "Romance"],
  [/\b(?:action|action-packed|explosive)\b/, ["Action"], "Action"],
  [/\b(?:sci-?fi|science fiction|futuristic|space)\b/, ["Science Fiction"], "Sci-fi"],
  [/\b(?:mystery|mysteries|whodunn?it|detective)\b/, ["Mystery"], "Mystery"],
  [/\b(?:crime|heist|gangster|mob)\b/, ["Crime"], "Crime"],
  [/\b(?:animated|animation|cartoon|cartoons)\b/, ["Animation"], "Animation"],
  [/\b(?:documentary|documentaries)\b/, ["Documentary"], "Documentary"],
  [/\b(?:war)\b/, ["War"], "War"],
  [/\b(?:westerns?)\b/, ["Western"], "Western"],
  [/\b(?:fantasy|magical)\b/, ["Fantasy"], "Fantasy"],
  [/\b(?:drama|dramas|emotional|tearjerker|sad)\b/, ["Drama"], "Drama"],
  [/\b(?:adventure|adventures)\b/, ["Adventure"], "Adventure"],
  [/\b(?:musical|musicals)\b/, ["Music"], "Music"],
  [/\b(?:history|historical)\b/, ["History"], "History"],
  [/\b(?:family)\b/, ["Family"], "Family"],
];

const DECADE_WORDS: Record<string, number> = {
  twenties: 1920, thirties: 1930, forties: 1940, fifties: 1950, sixties: 1960, seventies: 1970, eighties: 1980, nineties: 1990,
};

/** Filler that carries no filter and is not worth reporting as ignored. */
const FILLER = new Set(
  "a an the some something anything one me my i we us you to for of with that which is are be it its in on and or but like want wanna need watch watching see find give recommend recommendation please tonight today good great nice really very maybe about got have has had just any kind sort type from at as by if so then than this these those there something's what whats who idea ideas pick picks suggest suggestion movie-night night evening weekend".split(" "),
);

const clean = (s: string) => s.replace(/\s+/g, " ").trim();
const fmtMin = (m: number) => (m % 60 === 0 && m >= 120 ? `${m / 60} h` : `${m} min`);

function toMinutes(n: number, unit: string): number {
  return /^h/.test(unit) ? Math.round(n * 60) : Math.round(n);
}

/** Parse plain language. `now` is injectable so "recent" is testable. */
export function parseAsk(raw: string, now = new Date()): Reading {
  let t = " " + raw.toLowerCase().replace(/[’`]/g, "'").replace(/[^a-z0-9'.+\- ]+/g, " ") + " ";
  const chips: Chip[] = [];
  const add = (c: Chip) => {
    if (!chips.some((x) => x.id === c.id)) chips.push(c);
  };
  // Take a match out of the text so later rules and the "ignored" pass do not see it
  const take = (re: RegExp, fn: (m: RegExpExecArray) => void) => {
    const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
    t = t.replace(g, (...args) => {
      const m = args.slice(0, -2) as unknown as RegExpExecArray;
      fn(m);
      return " ";
    });
  };

  // "show me" / "give me" are requests, not the word "show"
  take(/\b(?:show|give|find|get|pick|recommend)\s+(?:me|us)\b/, () => {});

  // ----- runtime -----
  const UNIT = "(min(?:ute)?s?|mins?|m|hours?|hrs?|hr|h)";
  take(new RegExp(`\\b(?:under|less than|below|shorter than|max(?:imum)?(?: of)?|at most|no more than|up to|within)\\s+(\\d+(?:\\.\\d+)?)\\s*${UNIT}\\b`), (m) => {
    const min = toMinutes(Number(m[1]), m[2]);
    if (min > 0) add({ id: `maxrt:${min}`, label: `under ${fmtMin(min)}`, data: { t: "maxRuntime", min } });
  });
  take(new RegExp(`\\b(\\d+(?:\\.\\d+)?)\\s*${UNIT}\\s+or\\s+(?:less|under|shorter)\\b`), (m) => {
    const min = toMinutes(Number(m[1]), m[2]);
    if (min > 0) add({ id: `maxrt:${min}`, label: `under ${fmtMin(min)}`, data: { t: "maxRuntime", min } });
  });
  take(/\b(?:under|less than|shorter than|within)\s+an?\s+hour\b/, () => add({ id: "maxrt:60", label: "under 60 min", data: { t: "maxRuntime", min: 60 } }));
  take(/\b(?:under|less than|shorter than|within)\s+(?:an?\s+)?hour and a half\b/, () => add({ id: "maxrt:90", label: "under 90 min", data: { t: "maxRuntime", min: 90 } }));
  take(new RegExp(`\\b(?:over|more than|longer than|at least|above)\\s+(\\d+(?:\\.\\d+)?)\\s*${UNIT}\\b`), (m) => {
    const min = toMinutes(Number(m[1]), m[2]);
    if (min > 0) add({ id: `minrt:${min}`, label: `over ${fmtMin(min)}`, data: { t: "minRuntime", min } });
  });

  // ----- seen / unseen -----
  take(/\b(?:i\s+)?(?:haven'?t|have not|havent|hasn'?t|never)\s+(?:seen|watched)(?:\s+(?:it|yet|before))?\b/, () => add({ id: "unplayed", label: "unwatched", data: { t: "unplayed" } }));
  take(/\b(?:not (?:seen|watched)(?: yet)?|unwatched|unseen|new to me|not played)\b/, () => add({ id: "unplayed", label: "unwatched", data: { t: "unplayed" } }));
  take(/\b(?:rewatch|re-watch|seen before|watched before|already seen|already watched)\b/, () => add({ id: "played", label: "already watched", data: { t: "played" } }));

  // ----- kids -----
  take(/\b(?:for (?:the |my |our )?(?:kids?|children|little ones)|kids'?|children'?s|family[- ]friendly|kid[- ]friendly)\b/, () =>
    add({ id: "kids", label: "for kids (rated G, PG)", data: { t: "kids", ratings: KIDS_RATINGS, genres: KIDS_GENRES } }),
  );

  // ----- rating -----
  take(/\b(?:rated|rating|score(?:d)?)\s*(?:over|above|at least|of|>=?)?\s*(\d(?:\.\d)?)(?!\d)\s*\+?(?:\s*(?:\/\s*10|or (?:higher|more|better)))?/, (m) => {
    const v = Number(m[1]);
    if (v > 0 && v <= 10) add({ id: `minrating:${v}`, label: `rated ${v}+`, data: { t: "minRating", value: v } });
  });
  take(/\b(?:(\d(?:\.\d)?)\s*\+?\s*(?:stars?|\/\s*10)|(?:best|top[- ]rated|critically acclaimed|acclaimed|award[- ]winning))\b/, (m) => {
    const v = m[1] ? Number(m[1]) : 7.5;
    if (v > 0 && v <= 10) add({ id: `minrating:${v}`, label: m[1] ? `rated ${v}+` : "rated 7.5+", data: { t: "minRating", value: v } });
  });
  take(/\b(?:well[- ]rated|highly rated|well[- ]reviewed|good reviews|great reviews|good ratings?)\b/, () => add({ id: "minrating:7", label: "rated 7+", data: { t: "minRating", value: 7 } }));

  // ----- kind + seasons -----
  take(/\b(?:miniseries|mini-series|limited series|one season|single season)\b/, () => {
    add({ id: "kind:Series", label: "shows", data: { t: "kind", kind: "Series" } });
    add({ id: "maxseasons:1", label: "1 season", data: { t: "maxSeasons", n: 1 } });
  });
  take(/\bshort\s+(?:tv\s+)?(?:series|shows?)\b/, () => {
    add({ id: "kind:Series", label: "shows", data: { t: "kind", kind: "Series" } });
    add({ id: "maxseasons:3", label: "up to 3 seasons", data: { t: "maxSeasons", n: 3 } });
  });
  take(/\b(?:movies?|films?|flicks?)\b/, () => add({ id: "kind:Movie", label: "movies", data: { t: "kind", kind: "Movie" } }));
  take(/\b(?:tv shows?|tv series|series|shows?|tv)\b/, () => add({ id: "kind:Series", label: "shows", data: { t: "kind", kind: "Series" } }));

  // ----- years -----
  const yearChip = (from: number, to: number, label: string) => add({ id: `years:${from}-${to}`, label, data: { t: "years", from, to } });
  const thisYear = now.getFullYear();
  take(/(?<![\w])(?:in the |from the |of the )?'?(1[89]|20)(\d)0'?s\b/, (m) => {
    const from = Number(`${m[1]}${m[2]}0`);
    yearChip(from, from + 9, `${from}s`);
  });
  take(/(?<![\w])(?:in the |from the |of the )?'?([2-9])0'?s\b/, (m) => {
    const from = 1900 + Number(m[1]) * 10;
    yearChip(from, from + 9, `${from}s`);
  });
  take(/\b(?:in the |from the |of the )?(twenties|thirties|forties|fifties|sixties|seventies|eighties|nineties)\b/, (m) => {
    const from = DECADE_WORDS[m[1]];
    yearChip(from, from + 9, `${from}s`);
  });
  take(/\b(?:after|since)\s+((?:19|20)\d\d)\b/, (m) => {
    const from = Number(m[1]) + 1;
    if (from <= thisYear) yearChip(from, thisYear, `after ${m[1]}`);
  });
  take(/\b(?:before)\s+((?:19|20)\d\d)\b/, (m) => {
    const to = Number(m[1]) - 1;
    if (to >= 1900) yearChip(1900, to, `before ${m[1]}`);
  });
  take(/\b((?:19|20)\d\d)\b/, (m) => yearChip(Number(m[1]), Number(m[1]), m[1]));
  take(/\b(?:recent|recently released|latest|new releases?|newer)\b/, () => yearChip(thisYear - 4, thisYear, `${thisYear - 4}-${thisYear}`));

  // ----- genres / moods (last, so "family friendly" and "funny" are not read twice) -----
  for (const [re, genres, label] of MOODS) {
    take(re, () => add({ id: `genre:${label}`, label, data: { t: "genre", genres } }));
  }

  const ignored: string[] = [];
  for (const w of clean(t).split(" ")) {
    const word = w.replace(/^['.\-+]+|['.\-+]+$/g, "");
    if (word && !FILLER.has(word) && !ignored.includes(word)) ignored.push(word);
  }
  return { chips, ignored };
}

export function removeChip(removed: string[], id: string): string[] {
  return removed.includes(id) ? removed : [...removed, id];
}

export const activeChips = (r: Reading, removed: string[]): Chip[] => r.chips.filter((c) => !removed.includes(c.id));

// ---------- query ----------

type Params = Record<string, string | number | boolean | undefined>;

const of = <T extends ChipData["t"]>(chips: Chip[], t: T) => chips.filter((c): c is Chip & { data: Extract<ChipData, { t: T }> } => c.data.t === t);

/** Jellyfin Items params for the chips. Runtime, season counts and "each mood matches" are checked client-side (see pickFrom). */
export function buildQuery(chips: Chip[], opts: { shuffle?: boolean; limit?: number } = {}): Params {
  const kinds = of(chips, "kind").map((c) => c.data.kind);
  const genres = new Set<string>();
  for (const c of of(chips, "genre")) c.data.genres.forEach((g) => genres.add(g));
  const kids = of(chips, "kids")[0];
  // "for kids" adds Family/Animation only when no mood already chose genres
  if (kids && genres.size === 0) kids.data.genres.forEach((g) => genres.add(g));
  const years = of(chips, "years")[0]?.data;
  const flags: string[] = [];
  if (of(chips, "unplayed").length) flags.push("IsUnplayed");
  if (of(chips, "played").length) flags.push("IsPlayed");
  const minRating = Math.max(0, ...of(chips, "minRating").map((c) => c.data.value));
  const wantSeasons = of(chips, "maxSeasons").length > 0;
  return {
    IncludeItemTypes: wantSeasons ? "Series" : kinds.length === 1 ? kinds[0] : "Movie,Series",
    Recursive: true,
    SortBy: opts.shuffle ? "Random" : "CommunityRating,SortName",
    SortOrder: opts.shuffle ? undefined : "Descending,Ascending",
    Limit: opts.limit ?? FETCH_LIMIT,
    StartIndex: 0,
    Fields: "Overview,Genres,ProviderIds,PrimaryImageAspectRatio,ChildCount",
    Genres: genres.size ? [...genres].join("|") : undefined,
    Years: years ? Array.from({ length: years.to - years.from + 1 }, (_, i) => years.from + i).join(",") : undefined,
    Filters: flags.length ? flags.join(",") : undefined,
    OfficialRatings: kids ? kids.data.ratings.join("|") : undefined,
    MinCommunityRating: minRating > 0 ? minRating : undefined,
  };
}

/** True when some of the chips can only be checked over the fetched page. */
export function needsClientFilter(chips: Chip[]): boolean {
  return of(chips, "maxRuntime").length + of(chips, "minRuntime").length + of(chips, "maxSeasons").length > 0 || of(chips, "genre").length > 1;
}

const runtimeMin = (it: Item): number | null => (it.RunTimeTicks ? it.RunTimeTicks / 600_000_000 : null);

/** Client-side filters over the fetched page: runtime (series use episode length), season count, every mood chip matched. */
export function filterItems(items: Item[], chips: Chip[]): Item[] {
  const maxRt = Math.min(Infinity, ...of(chips, "maxRuntime").map((c) => c.data.min));
  const minRt = Math.max(0, ...of(chips, "minRuntime").map((c) => c.data.min));
  const maxSeasons = Math.min(Infinity, ...of(chips, "maxSeasons").map((c) => c.data.n));
  const moods = of(chips, "genre");
  return items.filter((it) => {
    if (maxRt !== Infinity || minRt > 0) {
      const m = runtimeMin(it);
      if (m == null) return false; // unknown length cannot be confirmed
      if (m > maxRt || m < minRt) return false;
    }
    if (maxSeasons !== Infinity) {
      if (it.Type !== "Series" || it.ChildCount == null || it.ChildCount > maxSeasons) return false;
    }
    const have = new Set((it.Genres ?? []).map((g) => g.toLowerCase()));
    return moods.every((c) => c.data.genres.some((g) => have.has(g.toLowerCase())));
  });
}

export interface Picks {
  items: Item[];
  /** What the screen says about coverage. Never implies more than was checked. */
  note: string;
}

export function pickFrom(items: Item[], total: number | undefined, chips: Chip[], shuffled: boolean): Picks {
  const filtered = needsClientFilter(chips);
  const kept = filterItems(items, chips);
  const out = kept.slice(0, PICKS);
  const n = items.length;
  const partial = total != null ? total > n : n >= FETCH_LIMIT;
  let note: string;
  if (!partial) note = filtered ? `Checked all ${n} matches in your library.` : `All ${n} matches in your library.`;
  else {
    const set = shuffled ? `a random ${n} of ${total ?? "many"}` : `the first ${n}${total != null ? ` of ${total}` : ""}`;
    note = filtered ? `From ${set} matches: length and season limits are only checked within those, so some fits may be missing.` : `From ${set} matches.`;
  }
  if (kept.length > out.length) note += ` Showing ${out.length} of ${kept.length} that fit.`;
  return { items: out, note };
}

/** One line saying what was applied beyond the chips, e.g. the kids rule. */
export function appliedNotes(chips: Chip[]): string[] {
  const notes: string[] = [];
  const kids = of(chips, "kids")[0];
  if (kids) {
    const moods = of(chips, "genre").length > 0;
    notes.push(`For kids: rated ${kids.data.ratings.join(", ")}${moods ? "" : `, genres ${kids.data.genres.join(" or ")}`}. Unrated titles are left out.`);
  }
  if (of(chips, "maxRuntime").length || of(chips, "minRuntime").length) notes.push("Length: shows are judged by episode length; titles without a known length are left out.");
  return notes;
}

