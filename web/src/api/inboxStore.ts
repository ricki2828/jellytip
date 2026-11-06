// Availability inbox: the per-user store in localStorage, the refresh (existing endpoints only) and a hook for the
// nav badge and the screen. Refreshes on app start and when the Inbox opens, at most every 15 minutes; no polling.
import { useEffect, useState } from "react";
import { getItems, getSession, nextUp, type Item } from "./jellyfin";
import { iptvGet, providers } from "./iptv";
import { afterStartup } from "../lib/startup";
import { parseVodItems } from "../lib/iptvVod";
import {
  addRequest, applyObservation, dueForRefresh, emptyStore, markAllRead, parseStore, pickIptv, reqKey, storageKey, unreadCount,
  type Observed, type ObservedEpisode, type ObservedTitle, type RequestRec, type Store,
} from "../lib/inbox";

const EVENT = "jellytip-inbox";
let cache: Store | null = null;
let inflight: Promise<void> | null = null;

const userId = () => getSession()?.userId ?? "";

function load(): Store {
  const u = userId();
  if (!u) return emptyStore("");
  if (cache?.userId === u) return cache;
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(storageKey(u));
  } catch {
    // storage blocked: the inbox works for this visit only
  }
  return (cache = parseStore(raw, u));
}

function save(s: Store) {
  if (!s.userId) return;
  cache = s;
  try {
    localStorage.setItem(storageKey(s.userId), JSON.stringify(s));
  } catch {
    // full or blocked: the in-memory copy still serves this visit
  }
  window.dispatchEvent(new Event(EVENT));
}

// A different person must never see the previous person's inbox: drop the in-memory copy (storage is keyed per user)
window.addEventListener("jellytip-session", () => {
  cache = null;
  inflight = null;
  window.dispatchEvent(new Event(EVENT));
});

export const inboxNow = (): Store => load();

/** Remember a request the person made ("Get it" on a title) so its arrival can be reported. */
export function recordRequest(tmdbId: number, media: "movie" | "tv", title: string) {
  if (!userId() || !tmdbId || !title) return;
  const r: RequestRec = { key: reqKey(media, tmdbId), tmdbId, media, title, at: Date.now() };
  save(addRequest(load(), r));
}

export function markRead() {
  save(markAllRead(load()));
}

const tmdbOf = (it: Item): number => Number(it.ProviderIds?.Tmdb ?? it.ProviderIds?.tmdb) || 0;

async function observeTitles(reqs: RequestRec[]): Promise<Record<string, ObservedTitle> | undefined> {
  if (!reqs.length) return {};
  const out: Record<string, ObservedTitle> = {};
  const inLib = new Map<string, string>();
  try {
    for (let i = 0; i < reqs.length; i += 40) {
      const chunk = reqs.slice(i, i + 40);
      const r = await getItems({ IncludeItemTypes: "Movie,Series", Recursive: true, Fields: "ProviderIds", EnableUserData: false, AnyProviderIdEquals: chunk.map((c) => `tmdb.${c.tmdbId}`).join(","), Limit: 100 });
      for (const it of r.Items) {
        const media = it.Type === "Series" ? "tv" : it.Type === "Movie" ? "movie" : null;
        const t = tmdbOf(it);
        if (media && t) inLib.set(reqKey(media, t), it.Id);
      }
    }
  } catch {
    return undefined; // library unreachable: leave the whole titles snapshot as it was
  }
  const todo = reqs.filter((r) => !inLib.has(r.key));
  let allowed: (string | number)[] | null = null;
  if (todo.length) {
    const pr = await providers();
    if (pr.status === 200 && pr.data?.providers) allowed = pr.data.providers.map((p) => p.id);
  }
  for (const r of reqs) {
    const libId = inLib.get(r.key);
    if (libId) {
      out[r.key] = { r: 2, title: r.title, requested: true, libId };
      continue;
    }
    let hit = null;
    let partial = allowed == null;
    if (allowed && allowed.length) {
      const a = await iptvGet<{ items?: unknown }>(`v1/iptv/vod?kind=${r.media === "tv" ? "series" : "movie"}&q=${encodeURIComponent(r.title)}&limit=50`);
      if (a.status === 200) hit = pickIptv(parseVodItems(a.data?.items), r.media, r.tmdbId, allowed);
      else partial = true;
    }
    out[r.key] = { r: hit ? 1 : 0, title: r.title, requested: true, iptvId: hit?.id, partial: partial || undefined };
  }
  return out;
}

async function observeEpisodes(): Promise<Record<string, ObservedEpisode[]> | undefined> {
  const names = new Map<string, string>();
  try {
    const fav = await getItems({ IncludeItemTypes: "Series", Filters: "IsFavorite", Recursive: true, Limit: 200 });
    for (const s of fav.Items) names.set(s.Id, s.Name);
    for (const e of await nextUp()) if (e.SeriesId) names.set(e.SeriesId, e.SeriesName ?? "");
    if (!names.size) return {};
    const r = await getItems({ IncludeItemTypes: "Episode", Recursive: true, SortBy: "DateCreated", SortOrder: "Descending", Fields: "DateCreated", EnableUserData: false, Limit: 200 });
    const out: Record<string, ObservedEpisode[]> = {};
    for (const id of names.keys()) out[id] = [];
    for (const e of r.Items) {
      if (!e.SeriesId || !out[e.SeriesId] || e.LocationType === "Virtual") continue;
      const at = Date.parse((e as Item & { DateCreated?: string }).DateCreated ?? "");
      out[e.SeriesId].push({ id: e.Id, show: e.SeriesName || names.get(e.SeriesId) || e.Name, season: e.ParentIndexNumber, episode: e.IndexNumber, at: Number.isFinite(at) ? at : 0 });
    }
    return out;
  } catch {
    return undefined;
  }
}

/** Look again (skipped inside 15 minutes of the last look unless `force`). */
export function refreshInbox(force = false): Promise<void> {
  if (inflight) return inflight;
  const u = userId();
  if (!u) return Promise.resolve();
  const s0 = load();
  if (!force && s0.seeded && !dueForRefresh(s0, Date.now())) return Promise.resolve();
  const job = (async () => {
    const [titles, eps] = await Promise.all([observeTitles(s0.requests), observeEpisodes()]);
    if (userId() !== u) return; // signed out / switched while we looked: this answer is not theirs
    if (!titles && !eps) return; // nothing reachable: try again next time, change nothing
    const obs: Observed = { titles, eps };
    save(applyObservation(load(), obs, Date.now()));
  })().finally(() => {
    if (inflight === job) inflight = null;
  });
  inflight = job;
  return job;
}

/** The store, kept current; `soon` waits for the first screen before the first look. */
export function useInbox(opts: { soon?: boolean; refresh?: boolean } = {}): Store {
  const [, bump] = useState(0);
  useEffect(() => {
    let alive = true;
    const on = () => alive && bump((n) => n + 1);
    window.addEventListener(EVENT, on);
    if (opts.refresh) void (opts.soon ? afterStartup() : Promise.resolve()).then(() => (alive ? refreshInbox() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return load();
}

export const unread = () => unreadCount(load());
