// Ranking, grouping and the stale-answer guard for Search (pure; tested in web/test/searchRank.test.ts).
import type { SearchEntry, SearchResult } from "./search.ts";
import { neutralIntent, type SearchIntent } from "./searchIntent.ts";

import { sourceLabel, type Avail } from "./availability.ts";

export type { Avail };

export const availability = (e: Pick<SearchEntry, "source">): Avail => sourceLabel(e.source);

/** Lower-case, accents and punctuation out, leading article out: "The Office (US)" ~ "office us". */
export function norm(s: string): string {
  return s
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/^(?:the|a|an) /, "")
    .trim();
}

/** 0 = exact title, 1 = title starts with the query, 2 = anything else. */
export function matchTier(title: string, text: string): 0 | 1 | 2 {
  const t = norm(title);
  const q = norm(text);
  if (!q) return 2;
  if (t === q) return 0;
  return t.startsWith(q) ? 1 : 2;
}

const SOURCE_ORDER = { library: 0, iptv: 1, discover: 2 } as const;

/** Best tier of a title against any of the texts (the raw query and the intent-stripped text). */
const bestTier = (title: string, texts: string[]) => Math.min(...texts.map((t) => matchTier(title, t))) as 0 | 1 | 2;

/**
 * Exact title matches first, then prefix matches, then the server's order. Inside a tier: entries whose year is in the asked range,
 * then library before IPTV before not-available.
 */
export function rankEntries(list: SearchEntry[], texts: string | string[], years: { from: number; to: number } | null = null): SearchEntry[] {
  const ts = (Array.isArray(texts) ? texts : [texts]).filter(Boolean);
  const yearMiss = (e: SearchEntry) => (years && !(e.year != null && e.year >= years.from && e.year <= years.to) ? 1 : 0);
  return list
    .map((e, i) => ({ e, i, tier: ts.length ? bestTier(e.title, ts) : 2 }))
    .sort((a, b) => a.tier - b.tier || yearMiss(a.e) - yearMiss(b.e) || SOURCE_ORDER[a.e.source] - SOURCE_ORDER[b.e.source] || b.e.score - a.e.score || a.i - b.i)
    .map((x) => x.e);
}

export interface Grouped {
  /** The entry that leads the page, with the reason it does. */
  top: SearchEntry | null;
  movies: SearchEntry[];
  shows: SearchEntry[];
  people: SearchEntry[];
  collections: SearchEntry[];
  /** Discover entries: not in the library or on IPTV. */
  missing: SearchEntry[];
  /** Section order for the page (Top match is always first unless live leads). */
  /** The intent actually applied: neutral when a title matches the whole query. */
  intent: SearchIntent;
  order: ("live" | "movies" | "shows" | "episodes" | "people" | "collections" | "missing")[];
}

const playable = (e: SearchEntry) => (e.source === "library" || e.source === "iptv") && (e.kind === "film" || e.kind === "series");

/** Pick the top match and split the rest into groups; the intent decides who leads, unless a title matches the whole query. */
export function groupEntries(list: SearchEntry[], given: SearchIntent, hasLive: boolean): Grouped {
  // A result titled like the whole query ("Play Misty for Me", "Live and Let Die", "Watch Dogs") means the words were the title
  const literal = list.some((e) => e.kind !== "person" && matchTier(e.title, given.raw) < 2);
  let intent = literal ? neutralIntent(given) : given;
  if (!literal && given.xForm && !given.season) {
    const { season, episode, rest } = given.xForm;
    if (list.some((e) => e.kind === "series" && e.source === "library" && matchTier(e.title, rest) < 2)) intent = { ...given, season, episode, text: rest, xForm: null };
  }
  const ranked = rankEntries(list, [given.raw, intent.text], given.years);
  const personHit = intent.person ? ranked.find((e) => e.kind === "person" && matchTier(e.title, intent.person!.name) < 2) : undefined;
  const wantPerson = !!personHit;
  let top: SearchEntry | null = null;
  const cands = ranked.filter((e) => !e.hubId); // a TV hub (collection row) is never the Top match
  if (wantPerson) top = personHit!;
  else if (intent.play) top = cands.find(playable) ?? cands[0] ?? null;
  else if (intent.season != null) top = ranked.find((e) => e.kind === "series" && e.source !== "discover") ?? cands[0] ?? null;
  else top = cands[0] ?? null;
  const rest = ranked.filter((e) => e !== top);
  const g: Grouped = {
    top,
    movies: rest.filter((e) => e.kind === "film" && e.source !== "discover"),
    shows: rest.filter((e) => e.kind === "series" && e.source !== "discover"),
    people: rest.filter((e) => e.kind === "person"),
    collections: rest.filter((e) => e.kind === "collection"),
    missing: rest.filter((e) => e.source === "discover" && e.kind !== "person" && e.kind !== "collection"),
    intent,
    order: [],
  };
  const base: Grouped["order"] = ["movies", "shows", "episodes", "people", "collections", "live", "missing"];
  const lead: Grouped["order"] = intent.live && hasLive ? ["live"] : wantPerson && intent.person!.strong ? ["people"] : [];
  g.order = [...lead, ...base.filter((x) => !lead.includes(x))];
  return g;
}

/** Answers for a search that is superseded must never land: each new search takes a ticket, only the latest ticket may apply. */
export function latestGuard() {
  let n = 0;
  return {
    next: () => ++n,
    isCurrent: (ticket: number) => ticket === n,
    /** Run `apply` only if `ticket` is still the latest; returns whether it ran. */
    apply(ticket: number, apply: () => void): boolean {
      if (ticket !== n) return false;
      apply();
      return true;
    },
  };
}

/** Honest label for an IPTV item when the query asked for a language: the catalogue carries no language, so say it is unconfirmed. */
export function iptvLanguageNote(e: Pick<SearchEntry, "source">, r: Pick<SearchResult, "filters">): string | null {
  return e.source === "iptv" && (r.filters.language || r.filters.country) ? "Language not confirmed" : null;
}
