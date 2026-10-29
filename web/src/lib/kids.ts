// Kids profile on the client (defence in depth; the companion filters rows / specials / search itself, and real
// enforcement is a Jellyfin account with a parental-rating policy). Rating → age matches the server's map
// (contract "Personalisation contract additions" 2, ~/ai/plans/jellytip-10x.md); unknown counts as 18.

// Same table as the server (me.py age_of(), jellytip-10x.md "Security fixes and contract changes")
const TABLE: [number, string[]][] = [
  [0, ["G", "U", "E", "TV-Y", "TV-G", "AL", "0", "ALL"]],
  [7, ["PG", "TV-Y7", "TV-Y7-FV", "TV-PG", "6", "7", "8", "PG-8"]],
  [10, ["10", "PG-10"]],
  [13, ["PG-13", "12", "12A", "M", "R13", "TV-14", "13", "14", "RP13", "12+", "M12"]],
  [16, ["R16", "RP16", "15", "R15", "MA15+", "AV15+", "MA", "16", "15+", "M15"]],
  [18, ["R", "R18", "18", "NC-17", "TV-MA", "X", "X18+", "R18+", "18+", "NR17"]],
];
const AGE: Record<string, number> = Object.fromEntries(TABLE.flatMap(([age, rs]) => rs.map((r) => [r, age])));

/** Age for an OfficialRating ("NZ-R16", "GB-15", "PG-13"); 18 when unknown or missing. */
export function ratingAge(rating: string | null | undefined): number {
  if (!rating) return 18;
  const r = rating.trim().toUpperCase();
  // A 2-letter country prefix ("NZ-M", "GB:15", "AU MA15+") goes; ratings that only look like one (TV-, MA, NC, PG, RP) stay
  const bare = /^(TV-|MA|NC|PG|RP)/.test(r) ? r : r.replace(/^[A-Z]{2}[-:\s]+(?=\S)/, "");
  return AGE[bare] ?? 18;
}

/** May a kids profile with this ceiling show something rated `rating`? */
export const kidsAllowed = (rating: string | null | undefined, maxAge: number) => ratingAge(rating) <= maxAge;

/** Screens a kids profile never opens (they read Jellyfin directly, outside the server's kids filter). */
export const KIDS_BLOCKED_ROUTES = new Set(["person", "collection", "discover", "livetv", "iptv", "iptvplay", "iptvitem", "list"]);
