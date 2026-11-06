// The person's IPTV copy of a Jellyfin series, for the season view. Existing endpoints only:
// GET v1/iptv/providers (the providers this person owns or has shared with them), GET v1/iptv/vod (their titles),
// GET v1/iptv/vod/{id} (seasons + episodes). Any failure = no IPTV info (null), never an error on the page.
import { iptvGet, providers, vodItem } from "./iptv";
import { parseVodItems, type VodDetail } from "../lib/iptvVod";
import { getSession } from "./jellyfin";
import { createLookupCache } from "../lib/lookupCache";
import { pickIptvSeries } from "../lib/seasonEpisodes";

const cache = createLookupCache<VodDetail>();
// A different person (or none) must never see the previous person's provider titles
if (typeof window !== "undefined")
  window.addEventListener("jellytip-session", () => cache.clear());

export function iptvSeriesFor(
  title: string,
  tmdbId: string | undefined,
): Promise<VodDetail | null> {
  const user = getSession()?.userId;
  if (!user || !tmdbId || !title) return Promise.resolve(null);
  return cache.get(user, tmdbId, () => lookup(title, tmdbId));
}

async function lookup(
  title: string,
  tmdbId: string,
): Promise<VodDetail | null> {
  const pr = await providers();
  if (pr.status !== 200 || !pr.data?.providers?.length) return null;
  const owned = pr.data.providers.map((p) => p.id);
  // includeLibrary=1: the title is in the library, which the default list hides
  const list = await iptvGet<{ items?: unknown }>(
    `v1/iptv/vod?kind=series&includeLibrary=1&q=${encodeURIComponent(title)}&limit=50`,
  );
  if (list.status !== 200) return null;
  const hit = pickIptvSeries(parseVodItems(list.data?.items), tmdbId, owned);
  if (!hit) return null;
  const d = await vodItem(hit.id);
  return d.status === 200 && d.data ? d.data : null;
}
