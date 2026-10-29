// Kids profile on the client (defence in depth; the companion filters rows / specials / search itself, and real
// enforcement is a Jellyfin account with a parental-rating policy). Rating → age matches the server's map
// (contract "Personalisation contract additions" 2, ~/ai/plans/jellytip-10x.md); unknown counts as 18.

const AGE: Record<string, number> = {
  G: 0, U: 0, E: 0, "TV-Y": 0, "TV-G": 0, "ALL": 0,
  PG: 7, "TV-Y7": 7, "TV-Y7-FV": 7, "TV-PG": 7,
  "PG-13": 13, "12": 13, "12A": 13, M: 13, R13: 13, RP13: 13, "TV-14": 13,
  "15": 16, R15: 16, R16: 16, RP16: 16, "MA15+": 16, MA: 16,
  R: 18, R18: 18, "18": 18, "R18+": 18, "NC-17": 18, "X18+": 18, "TV-MA": 18, X: 18,
};

/** Age for an OfficialRating ("NZ-R16", "GB-15", "PG-13"); 18 when unknown or missing. */
export function ratingAge(rating: string | null | undefined): number {
  if (!rating) return 18;
  const r = rating.trim().toUpperCase();
  // "PG-13" is a rating, "NZ-M" is a country prefix: the rating as given first, then without a 2-letter prefix
  return AGE[r] ?? AGE[r.replace(/^[A-Z]{2}[-/:\s]+(?=\S)/, "")] ?? 18;
}

/** May a kids profile with this ceiling show something rated `rating`? */
export const kidsAllowed = (rating: string | null | undefined, maxAge: number) => ratingAge(rating) <= maxAge;

/** Screens a kids profile never opens (they read Jellyfin directly, outside the server's kids filter). */
export const KIDS_BLOCKED_ROUTES = new Set(["person", "collection", "discover", "livetv", "iptv", "iptvplay", "iptvitem", "list"]);
