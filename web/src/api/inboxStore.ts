// Availability inbox: the per-user store in localStorage, the refresh (existing endpoints only) and a hook for the
// nav badge and the screen. Refreshes on app start and when the Inbox opens, at most every 15 minutes; no polling.
import { useEffect, useState } from "react";
import { getItems, getSession, nextUp, type Item } from "./jellyfin";
import { iptvGet, providers } from "./iptv";
import { debugEvent } from "../lib/debugLog";
import { afterStartup } from "../lib/startup";
import { parseVodItems } from "../lib/iptvVod";
import { observeEpisodes, observeTitles, type EpisodeDeps, type TitleDeps } from "../lib/inboxObserve";
import {
  addRequest, applyObservation, dueForRefresh, emptyStore, markAllRead, parseStore, reqKey, storageKey, unreadCount,
  type Observed, type RequestRec, type Store,
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

/** Re-read from storage (another tab may have written), replacing the in-memory copy. */
function reload(): Store {
  cache = null;
  return load();
}

function save(s: Store) {
  if (!s.userId) return;
  try {
    localStorage.setItem(storageKey(s.userId), JSON.stringify(s));
  } catch (e) {
    // Full or blocked: do not move ahead of what is stored (events would be lost on reload while the snapshot said "seen")
    debugEvent("inbox", "could not save the inbox", { error: String((e as Error)?.message ?? e) });
    return;
  }
  cache = s;
  window.dispatchEvent(new Event(EVENT));
}

// A different person must never see the previous person's inbox: drop the in-memory copy (storage is keyed per user)
window.addEventListener("jellytip-session", () => {
  cache = null;
  inflight = null;
  window.dispatchEvent(new Event(EVENT));
});

// Another tab wrote this person's inbox: pick it up
window.addEventListener("storage", (e) => {
  if (e.key && e.key === storageKey(userId())) {
    cache = null;
    window.dispatchEvent(new Event(EVENT));
  }
});

export const inboxNow = (): Store => load();

/** Remember a request the person made ("Get it" on a title) so its arrival can be reported. */
export function recordRequest(tmdbId: number, media: "movie" | "tv", title: string) {
  if (!userId() || !tmdbId || !title) return;
  const r: RequestRec = { key: reqKey(media, tmdbId), tmdbId, media, title, at: Date.now() };
  save(addRequest(reload(), r));
}

export function markRead() {
  save(markAllRead(reload()));
}

const tmdbOf = (it: Item): number => Number(it.ProviderIds?.Tmdb ?? it.ProviderIds?.tmdb) || 0;

const titleDeps: TitleDeps = {
  async libraryByTmdb(ids) {
    const r = await getItems({ IncludeItemTypes: "Movie,Series", Recursive: true, Fields: "ProviderIds", EnableUserData: false, AnyProviderIdEquals: ids.map((i) => `tmdb.${i}`).join(","), Limit: 100 });
    const out: { media: "movie" | "tv"; tmdbId: number; id: string }[] = [];
    for (const it of r.Items) {
      const media = it.Type === "Series" ? "tv" : it.Type === "Movie" ? "movie" : null;
      const t = tmdbOf(it);
      if (media && t) out.push({ media, tmdbId: t, id: it.Id });
    }
    return out;
  },
  async providerIds() {
    const pr = await providers();
    return pr.status === 200 && pr.data?.providers ? pr.data.providers.map((p) => p.id) : null;
  },
  async vodSearch(media, title) {
    const a = await iptvGet<{ items?: unknown }>(`v1/iptv/vod?kind=${media === "tv" ? "series" : "movie"}&q=${encodeURIComponent(title)}&limit=50`);
    return a.status === 200 ? parseVodItems(a.data?.items) : null;
  },
};

const episodeDeps: EpisodeDeps = {
  async favouriteSeries() {
    const r = await getItems({ IncludeItemTypes: "Series", Filters: "IsFavorite", Recursive: true, Limit: 200 });
    return r.Items.map((s) => ({ id: s.Id, name: s.Name }));
  },
  async nextUpSeries() {
    return (await nextUp()).filter((e) => e.SeriesId).map((e) => ({ id: e.SeriesId!, name: e.SeriesName ?? "" }));
  },
  async episodesOf(seriesId) {
    const r = await getItems({ ParentId: seriesId, IncludeItemTypes: "Episode", Recursive: true, SortBy: "DateCreated", SortOrder: "Descending", Fields: "DateCreated", EnableUserData: false, Limit: 100 });
    return r.Items.map((e) => ({ id: e.Id, name: e.Name, seriesName: e.SeriesName, season: e.ParentIndexNumber, episode: e.IndexNumber, virtual: e.LocationType === "Virtual", created: (e as Item & { DateCreated?: string }).DateCreated }));
  },
};

/** Look again (skipped inside 15 minutes of the last look unless `force`). */
export function refreshInbox(force = false): Promise<void> {
  if (inflight) return inflight;
  const u = userId();
  if (!u) return Promise.resolve();
  const s0 = reload();
  if (!force && s0.seeded && !dueForRefresh(s0, Date.now())) return Promise.resolve();
  const job = (async () => {
    const now = Date.now();
    const [t, e] = await Promise.all([observeTitles(titleDeps, s0.requests, s0.checked, now).catch(() => undefined), observeEpisodes(episodeDeps, s0.checked).catch(() => undefined)]);
    if (userId() !== u) return; // signed out / switched while we looked: this answer is not theirs
    if (!t && !e) return; // nothing reachable: try again next time, change nothing
    const obs: Observed = { titles: t?.titles, eps: e?.eps, checked: [...(t?.checked ?? []), ...(e?.checked ?? [])] };
    save(applyObservation(reload(), obs, Date.now())); // re-read first: another tab may have written meanwhile
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
    // Login or a user switch: look again for the new person (the throttle is per person)
    const onSession = () => alive && void (opts.refresh ? refreshInbox() : undefined);
    window.addEventListener(EVENT, on);
    if (opts.refresh) window.addEventListener("jellytip-session", onSession);
    if (opts.refresh) void (opts.soon ? afterStartup() : Promise.resolve()).then(() => (alive ? refreshInbox() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
      window.removeEventListener("jellytip-session", onSession);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return load();
}

export const unread = () => unreadCount(load());
