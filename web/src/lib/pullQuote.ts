// The one review quoted on a movie/series details page (pure; tested in web/test/pullQuote.test.ts):
// the caller's own review text, else the server's pull-quote, else the first non-spoiler excerpt, trimmed at a word.

import { bylineText, markOf, siteName, type Mark } from "./attribution.ts";

export interface QuoteSource {
  quote: { text: string; author: string; source: string; mark?: string | null } | null;
  reviews: { author: string; own: boolean; rating: number | null; excerpt: string; text: string; spoiler: boolean; source: string; mark?: string | null }[];
}

export interface PullQuote {
  text: string;
  author: string;
  rating: number | null;
  /** "Letterboxd" | "TMDB" | "Rotten Tomatoes Critics Consensus" ("" for a source we don't know) */
  site: string;
  /** "— …" line: author on site, or the consensus name. */
  by: string;
  /** Rotten Tomatoes fresh / rotten mark. */
  mark: Mark | null;
  /** The caller's own review. */
  own: boolean;
}

export const PULL_QUOTE_MAX = 220;

const credit = (source: string, author: string, mark?: string | null) => ({ site: siteName(source), by: bylineText(source, author), mark: markOf(mark) });

/** Cut at the last word boundary before `max` and add an ellipsis (no cut mid-word). */
export function trimAtWord(text: string, max = PULL_QUOTE_MAX): string {
  const t = text.replace(/\s+/g, " ").trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  const at = cut.lastIndexOf(" ");
  return `${(at > max * 0.6 ? cut.slice(0, at) : cut).replace(/[\s,;:.–—-]+$/, "")}…`;
}

export function pickPullQuote(data: QuoteSource | null): PullQuote | null {
  if (!data) return null;
  const own = data.reviews.find((r) => r.own && !r.spoiler && (r.excerpt || r.text).trim());
  if (own) return { text: trimAtWord(own.excerpt || own.text), author: own.author, rating: own.rating, ...credit(own.source, own.author, own.mark), own: true };
  if (data.quote?.text.trim()) {
    const by = data.reviews.find((r) => r.author === data.quote!.author);
    return { text: trimAtWord(data.quote.text), author: data.quote.author, rating: by?.rating ?? null, ...credit(data.quote.source, data.quote.author, data.quote.mark), own: false };
  }
  const first = data.reviews.find((r) => !r.spoiler && r.excerpt.trim());
  if (first) return { text: trimAtWord(first.excerpt), author: first.author, rating: first.rating, ...credit(first.source, first.author, first.mark), own: false };
  return null;
}
