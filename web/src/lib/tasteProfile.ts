// The taste profile (GET /v1/me/taste, contract: ~/ai/state/jellytip-taste-contract.md) and what it says about one title.
// Pure; tested in web/test/tasteProfile.test.ts. Weights are 0..1 (negative = disliked); a missing key is "no opinion".

export interface TasteProfile {
  genres: Record<string, number>;
  people: Record<string, number>;
  decades: Record<string, number>;
  keywords: Record<string, number>;
  media: { movie: number; series: number };
  updatedAt: string | null;
}

/** TMDB/Jellyfin/IPTV spell genres differently ("Sci-Fi & Fantasy", "Action & Adventure"): one set of lowercase names to compare. */
const GENRE_ALIASES: Record<string, string[]> = {
  "sci-fi": ["science fiction"],
  scifi: ["science fiction"],
  "sci-fi & fantasy": ["science fiction", "fantasy"],
  "science fiction & fantasy": ["science fiction", "fantasy"],
  "action & adventure": ["action", "adventure"],
  "war & politics": ["war"],
  "kids": ["family"],
  "talk": ["talk show"],
  "musical": ["music"],
};
export const canonGenres = (name: string): string[] => {
  const k = name.trim().toLowerCase();
  return GENRE_ALIASES[k] ?? [k];
};

const num = (v: unknown): number | null => (typeof v === "number" && isFinite(v) ? Math.max(-1, Math.min(1, v)) : null);

function weights(v: unknown, kind = ""): Record<string, number> {
  const out: Record<string, number> = {};
  if (!v || typeof v !== "object" || Array.isArray(v)) return out;
  for (const [k, w] of Object.entries(v as Record<string, unknown>)) {
    const n = num(w);
    if (k && n != null) for (const c of kind === "genres" ? canonGenres(k) : [k.toLowerCase()]) out[c] = Math.max(out[c] ?? -1, n);
  }
  return out;
}

/** The profile as the server sends it; null when it is not an object or says nothing at all. */
export function parseProfile(raw: unknown): TasteProfile | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  const m = r.media && typeof r.media === "object" ? (r.media as Record<string, unknown>) : {};
  const p: TasteProfile = {
    genres: weights(r.genres, "genres"),
    people: weights(r.people),
    decades: weights(r.decades),
    keywords: weights(r.keywords),
    media: { movie: num(m.movie) ?? 0, series: num(m.series) ?? 0 },
    updatedAt: typeof r.updatedAt === "string" ? r.updatedAt : null,
  };
  return Object.keys(p.genres).length || Object.keys(p.decades).length || p.media.movie || p.media.series ? p : null;
}

/** Genres, strongest first, with a positive weight. Names keep the profile's lowercase; callers match case-insensitively. */
export const topGenres = (p: TasteProfile, n = 5): string[] =>
  Object.entries(p.genres)
    .filter(([, w]) => w > 0)
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
    .slice(0, n)
    .map(([g]) => g);

export interface Scorable {
  genres?: string[];
  year?: number;
  kind: "movie" | "series";
}

/** 0..1, 0.5 = no opinion. Genres lead (best match and the average), then the decade and film-vs-show lean. */
export function affinity(p: TasteProfile, it: Scorable): number {
  const gs = (it.genres ?? []).flatMap(canonGenres).map((g) => p.genres[g]).filter((w): w is number => w != null);
  const known = gs.length ? 0.6 * Math.max(...gs) + 0.4 * (gs.reduce((a, b) => a + b, 0) / gs.length) : 0;
  const dec = it.year ? p.decades[String(Math.floor(it.year / 10) * 10)] ?? 0 : 0;
  const lean = it.kind === "movie" ? p.media.movie : p.media.series;
  const blend = 0.65 * known + 0.2 * dec + 0.15 * lean;
  return Math.max(0, Math.min(1, 0.5 + 0.5 * blend));
}
