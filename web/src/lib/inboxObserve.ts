// What a refresh looks at, with the fetchers injected (pure; tested in web/test/inboxObserve.test.ts).
import { pickIptv, reqKey, type ObservedEpisode, type ObservedTitle, type RequestRec } from "./inbox.ts";
import type { VodItem } from "./iptvVod.ts";

export const MAX_SERIES_PER_LOOK = 40;
export const MAX_IPTV_PER_LOOK = 10;
export const PARALLEL = 4;
export const REQUEST_IPTV_AGE_MS = 60 * 24 * 3600_000;

/** Run fn over items with at most `n` in flight; results in input order (a throw is the item's `undefined`). */
export async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>): Promise<(R | undefined)[]> {
  const out: (R | undefined)[] = new Array(items.length);
  let i = 0;
  const worker = async () => {
    while (i < items.length) {
      const k = i++;
      try {
        out[k] = await fn(items[k]);
      } catch {
        out[k] = undefined;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, worker));
  return out;
}

export interface RawEpisode {
  id: string;
  name: string;
  seriesName?: string;
  season?: number;
  episode?: number;
  virtual?: boolean;
  created?: string;
}

export interface EpisodeDeps {
  favouriteSeries(): Promise<{ id: string; name: string }[]>;
  nextUpSeries(): Promise<{ id: string; name: string }[]>;
  /** The newest episodes of one series; throws when it can't be asked. */
  episodesOf(seriesId: string): Promise<RawEpisode[]>;
}

const oldestFirst = (keys: string[], checked: Record<string, number>) => keys.slice().sort((a, b) => (checked[a] ?? 0) - (checked[b] ?? 0));

/**
 * New-episode sources: each followed series (favourites + Next Up) is asked for its own episodes, at most `cap` per
 * look (least recently looked-at first), `PARALLEL` at a time. A series that fails or is past the cap is simply
 * absent from the answer, so the snapshot carries it through. undefined = could not even list the followed series.
 */
export async function observeEpisodes(deps: EpisodeDeps, checked: Record<string, number>, cap = MAX_SERIES_PER_LOOK): Promise<{ eps: Record<string, ObservedEpisode[]>; checked: string[] } | undefined> {
  const names = new Map<string, string>();
  const [fav, up] = await Promise.allSettled([deps.favouriteSeries(), deps.nextUpSeries()]);
  if (fav.status === "rejected" && up.status === "rejected") return undefined;
  for (const r of [fav, up]) if (r.status === "fulfilled") for (const x of r.value) if (x.id) names.set(x.id, x.name);
  const todo = oldestFirst([...names.keys()], checked).slice(0, cap);
  const got = await pool(todo, PARALLEL, (sid) => deps.episodesOf(sid));
  const eps: Record<string, ObservedEpisode[]> = {};
  const done: string[] = [];
  todo.forEach((sid, i) => {
    const list = got[i];
    if (!list) return;
    done.push(sid);
    eps[sid] = list
      .filter((e) => !e.virtual) // unaired / missing placeholders are not arrivals
      .map((e) => ({ id: e.id, show: e.seriesName || names.get(sid) || e.name, season: e.season, episode: e.episode, at: Date.parse(e.created ?? "") || 0 }));
  });
  return { eps, checked: done };
}

export interface TitleDeps {
  /** Library items matching these TMDB ids; throws when the library can't be asked. */
  libraryByTmdb(tmdbIds: number[]): Promise<{ media: "movie" | "tv"; tmdbId: number; id: string }[]>;
  providerIds(): Promise<(string | number)[] | null>;
  /** The person's IPTV titles for a search; null when the call failed. */
  vodSearch(media: "movie" | "tv", title: string): Promise<VodItem[] | null>;
}

/**
 * Requested titles: library first (all of them, one batched ask), then IPTV for the ones not there, only for requests
 * under 60 days old, at most `maxIptv` per look (least recently checked first), `PARALLEL` at a time. Requests not
 * checked this time come back `partial` so an IPTV rank already known is kept.
 */
export async function observeTitles(deps: TitleDeps, reqs: RequestRec[], checked: Record<string, number>, now: number, maxIptv = MAX_IPTV_PER_LOOK): Promise<{ titles: Record<string, ObservedTitle>; checked: string[] } | undefined> {
  if (!reqs.length) return { titles: {}, checked: [] };
  const inLib = new Map<string, string>();
  try {
    for (let i = 0; i < reqs.length; i += 40) for (const x of await deps.libraryByTmdb(reqs.slice(i, i + 40).map((r) => r.tmdbId))) inLib.set(reqKey(x.media, x.tmdbId), x.id);
  } catch {
    return undefined; // library unreachable: leave the titles snapshot as it was
  }
  const open = reqs.filter((r) => !inLib.has(r.key) && now - r.at <= REQUEST_IPTV_AGE_MS);
  const pickKeys = oldestFirst(open.map((r) => r.key), checked).slice(0, maxIptv);
  const picked = new Set(pickKeys);
  let allowed: (string | number)[] | null = null;
  if (pickKeys.length) allowed = await deps.providerIds().catch(() => null);
  const byKey = new Map(reqs.map((r) => [r.key, r]));
  const hits = new Map<string, VodItem | null | undefined>();
  const looked: string[] = [];
  if (allowed && allowed.length) {
    const res = await pool(pickKeys, PARALLEL, async (k) => {
      const r = byKey.get(k)!;
      const items = await deps.vodSearch(r.media, r.title);
      return items ? pickIptv(items, r.media, r.tmdbId, allowed!) : undefined;
    });
    pickKeys.forEach((k, i) => {
      hits.set(k, res[i]);
      if (res[i] !== undefined) looked.push(k);
    });
  }
  const titles: Record<string, ObservedTitle> = {};
  for (const r of reqs) {
    const libId = inLib.get(r.key);
    if (libId) {
      titles[r.key] = { r: 2, title: r.title, requested: true, libId };
      continue;
    }
    const hit = hits.get(r.key);
    const known = picked.has(r.key) && allowed != null && hit !== undefined;
    titles[r.key] = { r: hit ? 1 : 0, title: r.title, requested: true, iptvId: hit?.id, partial: known ? undefined : true };
  }
  return { titles, checked: looked };
}
