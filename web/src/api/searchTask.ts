// Jellyfin-side helpers for task-aware search: filtered fallback search (Years/Genres sent to the server) and season/episode jumps.
import { getItems, seasons, type Item } from "./jellyfin";
import { hasFilters, jellyfinParams, type SearchIntent } from "../lib/searchIntent";
import { matchTier } from "../lib/searchRank";

/**
 * Plain Jellyfin search for a server without the companion. A year or genre in the query goes to Jellyfin as Years / Genres,
 * so the server filters the whole library; episodes come from a separate title search.
 */
export async function searchJellyfin(i: SearchIntent, term: string): Promise<Item[]> {
  const jobs: Promise<Item[]>[] = [];
  if (hasFilters(i)) jobs.push(getItems(jellyfinParams(i)).then((r) => r.Items));
  // A bare year ("2012") may be a title: search it as one too
  if (i.text || !i.genre) {
    const text = i.text || term;
    jobs.push(getItems({ SearchTerm: text, IncludeItemTypes: i.kind === "series" ? "Series,Episode" : i.kind === "movie" ? "Movie" : "Movie,Series,Episode", Recursive: true, Limit: 60 }).then((r) => r.Items));
  }
  const lists = await Promise.all(jobs);
  const seen = new Set<string>();
  const all = lists.flat().filter((it) => !seen.has(it.Id) && !!seen.add(it.Id));
  // Exact titles first, then prefix, then Jellyfin's own order
  return all.map((it, n) => ({ it, n, t: matchTier(it.Name, i.text || term) })).sort((a, b) => a.t - b.t || a.n - b.n).map((x) => x.it);
}

/** "X s2e5": the episode item, or "X season 2": the season item. null when the library doesn't have it. */
export async function resolveSeasonEpisode(seriesId: string, season: number, episode: number | null): Promise<Item | null> {
  if (episode != null) {
    const r = await getItems({ ParentId: seriesId, IncludeItemTypes: "Episode", Recursive: true, ParentIndexNumber: season, IndexNumber: episode, Limit: 5 });
    return r.Items.find((e) => e.ParentIndexNumber === season && e.IndexNumber === episode) ?? null;
  }
  const list = await seasons(seriesId);
  return list.find((s) => s.IndexNumber === season) ?? null;
}
