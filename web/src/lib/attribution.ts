// Who a review quote is credited to (pure; tested in web/test/pullQuote.test.ts). Letterboxd reviews read "<author> on
// Letterboxd", TMDB ones "<author> on TMDB", the Rotten Tomatoes critics consensus "Rotten Tomatoes Critics Consensus"
// with a fresh / rotten mark. Any other source never says "on Letterboxd".

export type Mark = "rt_fresh" | "rt_rotten";

export const RT_NAME = "Rotten Tomatoes Critics Consensus";

export const isRt = (source?: string | null) => /^rotten ?tomatoes$|^rt$/i.test(source ?? "");

export const markOf = (m?: string | null): Mark | null => (m === "rt_fresh" || m === "rt_rotten" ? m : null);

/** Site name for a source; "" for a source we don't know. A missing source is Letterboxd (servers before sources existed). */
export function siteName(source?: string | null): string {
  if (isRt(source)) return RT_NAME;
  if (!source || source === "letterboxd") return "Letterboxd";
  if (source === "tmdb") return "TMDB";
  return "";
}

/** "— <this>" line: the consensus names itself; others name the author and (when known) the site. */
export function bylineText(source: string | null | undefined, author: string): string {
  if (isRt(source)) return RT_NAME;
  const site = siteName(source);
  return site ? `${author} on ${site}` : author;
}
