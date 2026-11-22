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
  | { t: "maxSeasons"; n: number }
  /** "not scary", "non-animated": titles in any of these genres are left out (checked client-side over the fetched page). */
  | { t: "notGenre"; genres: string[] };

export interface Chip {
  /** Stable for a given reading, so a removed chip stays removed while the text is unchanged. */
  id: string;
  label: string;
  data: ChipData;
  /** Where in the text it was read, and the words (used to resolve contradictions). */
  pos?: number;
  src?: string;
}

export interface Reading {
  chips: Chip[];
  /** Words that matched nothing (filler words are dropped silently). */
  ignored: string[];
  /** Things the person should know about how this was read (negatives, contradictions). */
  notes: string[];
}

export const KIDS_RATINGS = ["G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "U"];
export const KIDS_GENRES = ["Family", "Animation", "Kids"];
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
  [/\b(?:feel[- ]?good|heart-?warming|uplifting|wholesome)\b/, ["Comedy", "Family", "Animation", "Kids"], "Feel-good"],
  [/\b(?:mind[- ]?bending|twisty|trippy|head[- ]?scratcher)\b/, ["Science Fiction", "Sci-Fi & Fantasy", "Mystery"], "Mind-bending"],
  [/\b(?:funny|comedy|comedies|hilarious|laugh|comic)\b/, ["Comedy"], "Comedy"],
  [/\b(?:scary|horror|creepy|spooky|frightening|terrifying)\b/, ["Horror"], "Horror"],
  [/\b(?:tense|thriller|thrillers|suspenseful|suspense|gripping|edge of (?:my|your) seat)\b/, ["Thriller"], "Thriller"],
  [/\b(?:romantic|romance|rom-?coms?|date night)\b/, ["Romance"], "Romance"],
  [/\b(?:action|action-packed|explosive)\b/, ["Action", "Action & Adventure"], "Action"],
  [/\b(?:sci-?fi|science fiction|futuristic|space)\b/, ["Science Fiction", "Sci-Fi & Fantasy"], "Sci-fi"],
  [/\b(?:mystery|mysteries|whodunn?it|detective)\b/, ["Mystery"], "Mystery"],
  [/\b(?:crime|heist|gangster|mob)\b/, ["Crime"], "Crime"],
  [/\b(?:animated|animation|cartoon|cartoons)\b/, ["Animation"], "Animation"],
  [/\b(?:documentary|documentaries)\b/, ["Documentary"], "Documentary"],
  [/\b(?:war)\b/, ["War", "War & Politics"], "War"],
  [/\b(?:westerns?)\b/, ["Western"], "Western"],
  [/\b(?:fantasy|magical)\b/, ["Fantasy", "Sci-Fi & Fantasy"], "Fantasy"],
  [/\b(?:drama|dramas|emotional|tearjerker|sad)\b/, ["Drama"], "Drama"],
  [/\b(?:adventure|adventures)\b/, ["Adventure", "Action & Adventure"], "Adventure"],
  [/\b(?:musical|musicals)\b/, ["Music"], "Music"],
  [/\b(?:history|historical)\b/, ["History"], "History"],
  [/\b(?:family)\b/, ["Family", "Kids"], "Family"],
];

/** Moods that can be negated ("not scary" = no Horror). Others stay unsupported: "not sad" would drop every drama. */
const INVERTIBLE = new Set(["Horror", "Animation", "Comedy", "Thriller", "Romance", "Action", "Sci-fi", "Mystery", "Crime", "Documentary", "War", "Western", "Fantasy", "Adventure", "Music", "History"]);

const lc = (xs: string[]) => new Set(xs.map((x) => x.toLowerCase()));

/** The mood label for a genre list (same genres as a mood = that mood's label, so ids match the parser's), else the first genre. */
function moodLabel(genres: string[]): string {
  const want = [...lc(genres)].sort().join("|");
  const m = MOODS.find(([, g]) => [...lc(g)].sort().join("|") === want);
  return m ? m[2] : genres[0];
}

/**
 * The one way to turn filter data into a chip (id + label): used for chips that don't come from the parsed text (refine
 * options, and a query produced elsewhere, e.g. an LLM, as a list of ChipData). The parser keeps its own labels.
 */
export function chipFor(d: ChipData): Chip {
  switch (d.t) {
    case "genre":
      return { id: `genre:${moodLabel(d.genres)}`, label: moodLabel(d.genres), data: d };
    case "notGenre":
      return { id: `notgenre:${moodLabel(d.genres)}`, label: `no ${moodLabel(d.genres).toLowerCase()}`, data: d };
    case "kind":
      return { id: `kind:${d.kind}`, label: d.kind === "Movie" ? "movies" : "shows", data: d };
    case "maxRuntime":
      return { id: `maxrt:${d.min}`, label: `under ${fmtMin(d.min)}`, data: d };
    case "minRuntime":
      return { id: `minrt:${d.min}`, label: `over ${fmtMin(d.min)}`, data: d };
    case "years":
      return { id: `years:${d.from}-${d.to}`, label: d.from === d.to ? `${d.from}` : d.to - d.from === 9 && d.from % 10 === 0 ? `${d.from}s` : `${d.from}-${d.to}`, data: d };
    case "unplayed":
      return { id: "unplayed", label: "unwatched", data: d };
    case "played":
      return { id: "played", label: "already watched", data: d };
    case "kids":
      return { id: "kids", label: "for kids (rated G, PG)", data: d };
    case "minRating":
      return { id: `minrating:${d.value}`, label: `rated ${d.value}+`, data: d };
    case "maxSeasons":
      return { id: `maxseasons:${d.n}`, label: d.n === 1 ? "1 season" : `up to ${d.n} seasons`, data: d };
  }
}

/** Chips for a list of filter data (de-duplicated by id): the entry point for a query produced outside the parser. */
export const chipsFromData = (list: ChipData[]): Chip[] => list.map(chipFor).filter((c, i, all) => all.findIndex((x) => x.id === c.id) === i);

const DECADE_WORDS: Record<string, number> = {
  twenties: 1920, thirties: 1930, forties: 1940, fifties: 1950, sixties: 1960, seventies: 1970, eighties: 1980, nineties: 1990,
};

/** Filler that carries no filter and is not worth reporting as ignored. */
const FILLER = new Set(
  "a an the some something one me my i we us you to for of with that which is are be it its in on and or like want wanna need watch watching see find give recommend recommendation please tonight today good great nice really very maybe about got have has had just any kind sort type from at as by if so then than this these those there something's what whats who idea ideas pick picks suggest suggestion movie-night night evening weekend".split(" "),
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
  const notes: string[] = [];
  const ignored: string[] = [];
  let cur = { pos: 0, src: "" };
  const add = (c: Chip) => {
    if (!chips.some((x) => x.id === c.id)) chips.push({ ...c, pos: cur.pos, src: cur.src });
  };
  // Take a match out of the text so later rules and the "ignored" pass do not see it
  const take = (re: RegExp, fn: (m: RegExpExecArray) => void) => {
    const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
    t = t.replace(g, (...args) => {
      const m = args.slice(0, -2) as unknown as RegExpExecArray;
      cur = { pos: args[args.length - 2] as number, src: clean(String(args[0])) };
      fn(m);
      return " ".repeat(String(args[0]).length);
    });
  };

  // Negatives: a negated mood that names genres ("not scary", "no horror", "non-animated") leaves those genres out;
  // anything else ("not too long", "not for kids", "not sad") is left out of the reading (never guessed) and reported
  // The negated word may follow an article ("not a scary movie", "nothing that's scary"); a negated mood carries through an
  // "or" / "nor" list of moods ("nothing scary or animated"), never through "and" ("not scary and funny" wants funny)
  const NEG = /\b((?:(?:not|no|without|except|excluding|nothing|anything but|but not)\s+|non[- ])(?:(?:too|very|so|really|at all)\s+)?(?:for\s+)?(?:(?:a|an|any|the|my|that'?s|that is)\s+)?[a-z][a-z0-9'-]*)((?:\s*,?\s+(?:or|nor)\s+(?:no\s+|not\s+)?[a-z][a-z0-9'-]*)*)/g;
  const negMood = (w: string) => MOODS.find(([re, , label]) => INVERTIBLE.has(label) && new RegExp(re.source).test(` ${w} `));
  t = t.replace(NEG, (m: string, head: string, chain: string, off: number, whole: string) => {
    const rest = whole.slice(off + head.length);
    // Phrases that are supported and are not negations of a filter
    if (/^(?:not|no)\s+(?:more than|seen|watched|played)\b/.test(whole.slice(off)) || /^no more than\b/.test(whole.slice(off)) || /^(?:not|no)\s+(?:seen|watched|played)\b/.test(head + rest)) return m;
    const word = (head.match(/[a-z][a-z0-9'-]*$/) ?? [""])[0];
    const mood = /\bfor\s/.test(head) ? undefined : negMood(word);
    if (!mood) {
      ignored.push(clean(head));
      if (!notes.some((n) => n.startsWith("Negatives"))) notes.push("Negatives only work for genres ('not scary', 'no animation'); the rest was left out.");
      // The words after it are read as usual
      return " ".repeat(head.length) + chain;
    }
    cur = { pos: off, src: clean(head) };
    add({ id: `notgenre:${mood[2]}`, label: `no ${mood[2].toLowerCase()}`, data: { t: "notGenre", genres: mood[1] } });
    // The list carries the negation while each word is a mood that can be negated
    const link = /^\s*,?\s+(?:or|nor)\s+(?:no\s+|not\s+)?([a-z][a-z0-9'-]*)/;
    let used = 0;
    for (;;) {
      const cm = link.exec(chain.slice(used));
      const md = cm ? negMood(cm[1]) : undefined;
      if (!cm || !md) break;
      cur = { pos: off + head.length + used, src: clean(cm[0]) };
      add({ id: `notgenre:${md[2]}`, label: `no ${md[2].toLowerCase()}`, data: { t: "notGenre", genres: md[1] } });
      used += cm[0].length;
    }
    return " ".repeat(head.length + used) + chain.slice(used);
  });

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
  take(/\b(?:for (?:the |my |our )?(?:kids?|children|little ones)|kids'?|children'?s|family[- ]friendly|kid[- ]friendly|(?:that )?(?:would |will |can )?works? for (?:all|everyone|everybody|all of us)|all ages|whole family)\b/, () =>
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
  take(/\b((?:19|20)\d\d)\s*(?:to|-|until|through|till)\s*((?:19|20)\d\d)\b/, (m) => {
    const a = Number(m[1]);
    const b = Number(m[2]);
    if (a <= b) yearChip(a, b, `${a}-${b}`);
  });
  take(/\b((?:19|20)\d\d)\b/, (m) => yearChip(Number(m[1]), Number(m[1]), m[1]));
  take(/\b(?:recent|recently released|latest|new releases?|newer)\b/, () => yearChip(thisYear - 4, thisYear, `${thisYear - 4}-${thisYear}`));

  // ----- genres / moods (last, so "family friendly" and "funny" are not read twice) -----
  for (const [re, genres, label] of MOODS) {
    take(re, () => add({ id: `genre:${label}`, label, data: { t: "genre", genres } }));
  }

  // Contradictions: keep the later one, report the earlier
  const clash = (a: string, b: string, what: string) => {
    const ca = chips.find((c) => c.id === a);
    const cb = chips.find((c) => c.id === b);
    if (!ca || !cb) return;
    const [drop, keep] = (ca.pos ?? 0) < (cb.pos ?? 0) ? [ca, cb] : [cb, ca];
    chips.splice(chips.indexOf(drop), 1);
    ignored.push(drop.src ?? drop.label);
    notes.push(`${what} contradict each other: kept “${keep.label}”, ignored “${drop.src ?? drop.label}”.`);
  };
  clash("kind:Movie", "kind:Series", "Movies and shows");
  for (const [, , label] of MOODS) clash(`genre:${label}`, `notgenre:${label}`, `“${label}” and “no ${label.toLowerCase()}”`);
  clash("unplayed", "played", "Unwatched and rewatch");

  for (const w of clean(t).split(" ")) {
    const word = w.replace(/^['.\-+]+|['.\-+]+$/g, "");
    if (word && !FILLER.has(word) && !ignored.includes(word)) ignored.push(word);
  }
  return { chips, ignored, notes };
}

export function removeChip(removed: string[], id: string): string[] {
  return removed.includes(id) ? removed : [...removed, id];
}

export const activeChips = (r: Reading, removed: string[]): Chip[] => r.chips.filter((c) => !removed.includes(c.id));

// ---------- query ----------

type Params = Record<string, string | number | boolean | undefined>;

const of = <T extends ChipData["t"]>(chips: Chip[], t: T) => chips.filter((c): c is Chip & { data: Extract<ChipData, { t: T }> } => c.data.t === t);

/** Genres left out by "not ..." chips (lower case). */
export const excludedGenres = (chips: Chip[]): Set<string> => lc(of(chips, "notGenre").flatMap((c) => c.data.genres));

/** The kids rule's genres, minus any left out ("for the kids, nothing animated" = Family or Kids). */
export function kidsGenres(chips: Chip[]): string[] {
  const kids = of(chips, "kids")[0];
  if (!kids) return [];
  const out = excludedGenres(chips);
  return kids.data.genres.filter((g) => !out.has(g.toLowerCase()));
}

/** Jellyfin Items params for the chips. Runtime, season counts and "each mood matches" are checked client-side (see pickFrom). */
export function buildQuery(chips: Chip[], opts: { shuffle?: boolean; limit?: number } = {}): Params {
  const kinds = of(chips, "kind").map((c) => c.data.kind);
  const genres = new Set<string>();
  for (const c of of(chips, "genre")) c.data.genres.forEach((g) => genres.add(g));
  const kids = of(chips, "kids")[0];
  // "for kids" adds Family/Animation only when no mood already chose genres; genres left out by "not ..." never go in
  if (kids && genres.size === 0) kidsGenres(chips).forEach((g) => genres.add(g));
  const out = excludedGenres(chips);
  for (const g of [...genres]) if (out.has(g.toLowerCase())) genres.delete(g);
  const yearSet = new Set<number>();
  for (const c of of(chips, "years")) for (let y = c.data.from; y <= c.data.to; y++) yearSet.add(y);
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
    Limit: opts.limit ?? fetchLimit(chips),
    StartIndex: 0,
    // Only what is checked client-side. RunTimeTicks is not a Fields value: the server always returns it.
    Fields: ["PrimaryImageAspectRatio", of(chips, "genre").length > 1 || out.size ? "Genres" : "", wantSeasons ? "ChildCount" : ""].filter(Boolean).join(","),
    Genres: genres.size ? [...genres].join("|") : undefined,
    Years: yearSet.size ? [...yearSet].sort((a, b) => a - b).join(",") : undefined,
    Filters: flags.length ? flags.join(",") : undefined,
    OfficialRatings: kids ? kids.data.ratings.join("|") : undefined,
    MinCommunityRating: minRating > 0 ? minRating : undefined,
  };
}

/** Ask for the full page only when something has to be checked over it; otherwise the picks themselves. */
export function fetchLimit(chips: Chip[]): number {
  return needsClientFilter(chips) ? FETCH_LIMIT : PICKS;
}

/** True when some of the chips can only be checked over the fetched page. */
export function needsClientFilter(chips: Chip[]): boolean {
  return of(chips, "maxRuntime").length + of(chips, "minRuntime").length + of(chips, "maxSeasons").length + of(chips, "notGenre").length > 0 || of(chips, "genre").length > 1;
}

const runtimeMin = (it: Item): number | null => (it.RunTimeTicks ? it.RunTimeTicks / 600_000_000 : null);

/** Client-side filters over the fetched page: runtime (series use episode length), season count, every mood chip matched. */
export function filterItems(items: Item[], chips: Chip[]): Item[] {
  const maxRt = Math.min(Infinity, ...of(chips, "maxRuntime").map((c) => c.data.min));
  const minRt = Math.max(0, ...of(chips, "minRuntime").map((c) => c.data.min));
  const maxSeasons = Math.min(Infinity, ...of(chips, "maxSeasons").map((c) => c.data.n));
  const moods = of(chips, "genre");
  const out = excludedGenres(chips);
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
    // A title in a left-out genre is out (no genres known = kept: nothing to say it is one)
    for (const g of have) if (out.has(g)) return false;
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
  const partial = total != null ? total > n : n >= fetchLimit(chips);
  const checks = [
    of(chips, "maxRuntime").length + of(chips, "minRuntime").length > 0 ? "length" : "",
    of(chips, "maxSeasons").length > 0 ? "season" : "",
    of(chips, "genre").length > 1 ? "all-moods" : "",
    of(chips, "notGenre").length > 0 ? "left-out-genre" : "",
  ].filter(Boolean);
  let note: string;
  if (!partial) note = filtered ? `Checked all ${n} matches in your library.` : `All ${n} matches in your library.`;
  else {
    const set = shuffled ? `a random ${n} of ${total ?? "many"}` : `the first ${n}${total != null ? ` of ${total}` : ""}`;
    note = filtered ? `From ${set} matches: the ${checks.join(", ")} check${checks.length > 1 ? "s are" : " is"} only checked within those, so some fits may be missing.` : `From ${set} matches.`;
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
    const kg = kidsGenres(chips);
    notes.push(`For kids: rated ${kids.data.ratings.join(", ")}${moods || kg.length === 0 ? "" : `, genres ${kg.join(" or ")}`}. Unrated titles are left out.`);
  }
  const left = of(chips, "notGenre").flatMap((c) => c.data.genres);
  if (left.length) notes.push(`Left out: titles in ${[...new Set(left)].join(", ")} (titles with no genres listed can't be checked and are kept).`);
  if (of(chips, "maxRuntime").length || of(chips, "minRuntime").length) notes.push("Length: shows are judged by episode length; titles without a known length are left out.");
  if (of(chips, "genre").length > 1) notes.push("Several moods: a title must match each one (any of that mood's genres).");
  if (of(chips, "minRating").length) notes.push("Rating: titles without a community rating are left out.");
  return notes;
}
