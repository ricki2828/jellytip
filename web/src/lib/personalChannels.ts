// Personal channels built from the taste profile (pure; tested in web/test/personalChannels.test.ts). They have no query of
// their own: each is cut from the pools of the built-in channels (already loaded and cached), so they cost no requests and
// stay put between refreshes. Hidden (not defined) when the profile gives fewer than MIN_ITEMS playable items.
import { CHANNELS, MIN_ITEMS, byEpisodeOrder, hashString, isIptvId, meanAff, playable, type ChanItem, type ChannelDef } from "./channels.ts";
import { topGenres, type TasteProfile } from "./tasteProfile.ts";

/** Fits the person's taste at least this well to be picked for a personal channel. */
export const FIT_MIN = 0.55;
const FILMS_MAX = 40;
const SERIES_MAX = 8;
const PER_SERIES = 12;
const GENRE_MIN_ITEMS = 6;

export const FOR_YOU_ID = "for-you";
export const GENRE_ID = "for-you-genre";
export const SHOWS_ID = "your-shows";

/** Every distinct playable item across the built-in channels' pools (first copy wins). */
export function unionOf(base: Record<string, ChanItem[] | undefined>): ChanItem[] {
  const seen = new Set<string>();
  const out: ChanItem[] = [];
  for (const c of CHANNELS) {
    for (const it of playable(base[c.id] ?? [])) {
      if (seen.has(it.Id)) continue;
      seen.add(it.Id);
      out.push(it);
    }
  }
  return out;
}

const byAffDesc = (a: ChanItem, b: ChanItem): number => (b.Aff ?? 0.5) - (a.Aff ?? 0.5) || (a.Id < b.Id ? -1 : a.Id > b.Id ? 1 : 0);

/** Whole shows: the best `seriesMax` by mean affinity, each cut to `per` episodes (a stable hash sample, kept in episode order). */
function topSeries(eps: ChanItem[], seriesMax: number, per: number): ChanItem[] {
  const by = new Map<string, ChanItem[]>();
  for (const e of eps) if (e.SeriesId) by.set(e.SeriesId, [...(by.get(e.SeriesId) ?? []), e]);
  return [...by.entries()]
    .map(([sid, l]) => ({ sid, l, a: meanAff(l) ?? 0.5, lib: isIptvId(l[0].Id) ? 0 : 1 }))
    .sort((p, q) => q.a - p.a || q.lib - p.lib || (p.sid < q.sid ? -1 : 1))
    .slice(0, seriesMax)
    .flatMap(({ l }) =>
      l
        .map((e) => [hashString(`you|${e.Id}`), e] as const)
        .sort((p, q) => p[0] - q[0] || (p[1].Id < q[1].Id ? -1 : 1))
        .slice(0, per)
        .map((p) => p[1])
        .sort(byEpisodeOrder),
    );
}

const cap = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1);
const hasGenre = (it: ChanItem, g: string): boolean => (it.G ?? []).some((x) => x.toLowerCase() === g);

/** The items a personal channel plays, from the built-in channels' pools. */
export function personalItems(def: ChannelDef, base: Record<string, ChanItem[] | undefined>, genre?: string): ChanItem[] {
  const all = unionOf(base);
  if (def.personal === "foryou") {
    const fit = all.filter((i) => (i.Aff ?? 0) >= FIT_MIN);
    const films = fit.filter((i) => !i.SeriesId).sort(byAffDesc).slice(0, FILMS_MAX);
    return [...films, ...topSeries(fit.filter((i) => !!i.SeriesId), SERIES_MAX, PER_SERIES)];
  }
  if (def.personal === "genre") {
    const g = (genre ?? def.genres?.[0] ?? "").toLowerCase();
    const fit = all.filter((i) => hasGenre(i, g) && (i.Aff ?? 0.5) >= 0.5);
    return [...fit.filter((i) => !i.SeriesId).sort(byAffDesc).slice(0, FILMS_MAX), ...topSeries(fit.filter((i) => !!i.SeriesId), SERIES_MAX, PER_SERIES)];
  }
  if (def.personal === "shows") return topSeries(all.filter((i) => !!i.SeriesId && (i.Aff ?? 0) >= FIT_MIN), SERIES_MAX, PER_SERIES);
  return [];
}

const viable = (items: ChanItem[]): boolean => playable(items).length >= MIN_ITEMS;

/**
 * The personal channels this profile supports with these pools: "For You", "<Genre> for you" (the strongest genre that has
 * enough to play), "Your shows". Empty without a profile. Each is only defined when it would have at least MIN_ITEMS to play.
 */
export function personalDefs(profile: TasteProfile | null, base: Record<string, ChanItem[] | undefined>): ChannelDef[] {
  if (!profile) return [];
  const defs: ChannelDef[] = [];
  const foryou: ChannelDef = { id: FOR_YOU_ID, name: "For You", blurb: "Films and shows picked from what you watch and like", type: "Movie", personal: "foryou" };
  if (viable(personalItems(foryou, base))) defs.push(foryou);
  for (const g of topGenres(profile, 5)) {
    const probe: ChannelDef = { id: GENRE_ID, name: `${cap(g)} for you`, blurb: `Your favourite genre, picked for you`, type: "Movie", genres: [g], personal: "genre" };
    const items = personalItems(probe, base, g);
    if (items.length >= GENRE_MIN_ITEMS && viable(items)) {
      defs.push(probe);
      break;
    }
  }
  const shows: ChannelDef = { id: SHOWS_ID, name: "Your shows", blurb: "Shows you watch, and ones like them", type: "Episode", personal: "shows" };
  if (viable(personalItems(shows, base))) defs.push(shows);
  return defs;
}
