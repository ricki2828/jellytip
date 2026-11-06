// The person's IPTV copies of a Jellyfin movie, for the Details "Ways to watch" section. Existing endpoints only:
// GET v1/iptv/providers (own + shared providers) and GET v1/iptv/vod (their titles). One lookup per details page,
// cached per person; any failure = no copies (null), never an error on the page.
import { iptvGet, providers } from "./iptv";
import { parseVodItems } from "../lib/iptvVod";
import { getSession } from "./jellyfin";
import { createLookupCache } from "../lib/lookupCache";
import { ownedVod } from "../lib/seasonEpisodes";
import type { IptvCopy } from "../lib/availability";

export interface MovieIptv {
  copies: IptvCopy[];
  providerIds: string[];
}

const cache = createLookupCache<MovieIptv>();
if (typeof window !== "undefined") window.addEventListener("jellytip-session", () => cache.clear());

export function iptvMovieFor(title: string, tmdbId: string | undefined): Promise<MovieIptv | null> {
  const user = getSession()?.userId;
  if (!user || !tmdbId || !title) return Promise.resolve(null);
  return cache.get(user, `m:${tmdbId}`, () => lookup(title, tmdbId));
}

async function lookup(title: string, tmdbId: string): Promise<MovieIptv | null> {
  const want = Number(tmdbId);
  if (!isFinite(want) || want <= 0) return null;
  const pr = await providers();
  if (pr.status !== 200 || !pr.data?.providers?.length) return null;
  const providerIds = pr.data.providers.map((p) => String(p.id));
  const list = await iptvGet<{ items?: unknown }>(`v1/iptv/vod?kind=movie&includeLibrary=1&q=${encodeURIComponent(title)}&limit=50`);
  if (list.status !== 200) return null;
  const hits = ownedVod(parseVodItems(list.data?.items), providerIds).filter((i) => i.kind === "movie" && i.tmdbId === want);
  return { providerIds, copies: hits.map((h) => ({ id: h.id, providerName: h.providerName ?? null, quality: h.quality, tech: h.tech })) };
}
