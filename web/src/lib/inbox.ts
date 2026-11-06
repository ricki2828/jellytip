// Availability inbox (pure; tested in web/test/inbox.test.ts). Events are derived by diffing what the client can fetch
// now against a per-user last-seen snapshot kept in localStorage. Nothing here touches the network or the DOM.
import { ownedVod } from "./seasonEpisodes.ts";
import type { VodItem } from "./iptvVod.ts";

export const EXPIRY_MS = 30 * 24 * 3600_000;
export const THROTTLE_MS = 15 * 60_000;
export const MAX_EVENTS = 200;
export const MAX_TITLES = 500;
export const MAX_EPS_PER_SERIES = 150;
export const MAX_SERIES = 200;
export const MAX_REQUESTS = 100;

/** 0 = not available anywhere the person can watch, 1 = on their IPTV, 2 = in the Jellyfin library. */
export type Rank = 0 | 1 | 2;
export type EventKind = "library" | "iptv" | "episode";

export interface InboxEvent {
  /** Dedupe key: one event per (kind, title / episode). */
  id: string;
  kind: EventKind;
  title: string;
  /** The title came from a request the person made. */
  requested?: boolean;
  season?: number;
  episode?: number;
  /** Where the event opens (a route path). */
  link: string;
  at: number;
  read: boolean;
}

export interface RequestRec {
  key: string;
  tmdbId: number;
  media: "movie" | "tv";
  title: string;
  at: number;
}

export interface Snapshot {
  titles: Record<string, { r: Rank }>;
  /** Series id → episode ids already seen (newest first). */
  eps: Record<string, string[]>;
}

export interface Store {
  v: 1;
  userId: string;
  seeded: boolean;
  lastRefresh: number;
  snap: Snapshot;
  events: InboxEvent[];
  requests: RequestRec[];
}

export interface ObservedTitle {
  r: Rank;
  title: string;
  requested?: boolean;
  libId?: string;
  iptvId?: string;
  /** The IPTV lookup failed: keep an IPTV rank we already knew rather than treat it as gone. */
  partial?: boolean;
}

export interface ObservedEpisode {
  id: string;
  show: string;
  season?: number;
  episode?: number;
  at: number;
}

/** What a refresh saw. A source that failed is left out (undefined): its part of the snapshot stays as it was. */
export interface Observed {
  titles?: Record<string, ObservedTitle>;
  /** Series id → that series' newest episodes (every followed series is a key, even with none). */
  eps?: Record<string, ObservedEpisode[]>;
}

export const reqKey = (media: "movie" | "tv", tmdbId: number) => `${media}:${tmdbId}`;

export const emptyStore = (userId: string): Store => ({ v: 1, userId, seeded: false, lastRefresh: 0, snap: { titles: {}, eps: {} }, events: [], requests: [] });

/** Parse what localStorage held for this person. Another person's data, junk and old shapes all come back empty. */
export function parseStore(raw: string | null, userId: string): Store {
  if (!raw) return emptyStore(userId);
  try {
    const o = JSON.parse(raw) as Partial<Store> | null;
    if (!o || o.v !== 1 || o.userId !== userId || !o.snap || !Array.isArray(o.events) || !Array.isArray(o.requests)) return emptyStore(userId);
    const events = o.events.filter((e): e is InboxEvent => !!e && typeof e.id === "string" && typeof e.title === "string" && typeof e.at === "number" && typeof e.link === "string" && (e.kind === "library" || e.kind === "iptv" || e.kind === "episode")).map((e) => ({ ...e, read: e.read === true }));
    const requests = o.requests.filter((r): r is RequestRec => !!r && typeof r.key === "string" && typeof r.tmdbId === "number" && typeof r.title === "string" && typeof r.at === "number" && (r.media === "movie" || r.media === "tv"));
    return {
      v: 1,
      userId,
      seeded: o.seeded === true,
      lastRefresh: typeof o.lastRefresh === "number" ? o.lastRefresh : 0,
      snap: { titles: o.snap.titles && typeof o.snap.titles === "object" ? o.snap.titles : {}, eps: o.snap.eps && typeof o.snap.eps === "object" ? o.snap.eps : {} },
      events,
      requests,
    };
  } catch {
    return emptyStore(userId);
  }
}

export const storageKey = (userId: string) => `jellytip.inbox.v1.${userId}`;

/** Drop events past 30 days, dedupe by id (the earliest-detected wins), newest first, bounded. */
export function tidyEvents(events: InboxEvent[], now: number): InboxEvent[] {
  const seen = new Map<string, InboxEvent>();
  for (const e of events) {
    if (now - e.at > EXPIRY_MS) continue;
    const prev = seen.get(e.id);
    if (!prev) seen.set(e.id, e);
    else if (prev.read && !e.read) continue; // keep what the person already read as read
  }
  return [...seen.values()].sort((a, b) => b.at - a.at).slice(0, MAX_EVENTS);
}

export const unreadCount = (s: Store, now = Date.now()) => s.events.filter((e) => !e.read && now - e.at <= EXPIRY_MS).length;
export const markAllRead = (s: Store): Store => ({ ...s, events: s.events.map((e) => (e.read ? e : { ...e, read: true })) });

/** Add (or replace) a request; it enters the snapshot at rank 0 so its first arrival is an event, not a silent seed. */
export function addRequest(s: Store, r: RequestRec): Store {
  const requests = [r, ...s.requests.filter((x) => x.key !== r.key)].slice(0, MAX_REQUESTS);
  const titles = { ...s.snap.titles };
  if (!titles[r.key]) titles[r.key] = { r: 0 };
  return { ...s, requests, snap: { ...s.snap, titles } };
}

/**
 * Fold one observation into the store. The very first one only seeds the snapshot (no events); later ones raise an
 * event when a tracked title's availability rises or a followed series gets an episode it had not shown before. A key
 * or series new to the snapshot is seeded silently too (following something already available is not news).
 */
export function applyObservation(s: Store, obs: Observed, now: number): Store {
  const first = !s.seeded;
  const fresh: InboxEvent[] = [];
  const titles: Snapshot["titles"] = obs.titles ? {} : { ...s.snap.titles };
  if (obs.titles) {
    for (const [key, o] of Object.entries(obs.titles)) {
      const prev = s.snap.titles[key];
      const r: Rank = o.partial && prev ? (Math.max(o.r, Math.min(prev.r, 1)) as Rank) : o.r;
      titles[key] = { r };
      if (first || !prev || r <= prev.r) continue;
      if (r === 2 && o.libId) fresh.push({ id: `lib:${key}`, kind: "library", title: o.title, requested: o.requested || undefined, link: `/item/${o.libId}`, at: now, read: false });
      else if (r === 1 && o.iptvId) fresh.push({ id: `iptv:${key}`, kind: "iptv", title: o.title, requested: o.requested || undefined, link: `/iptv/item?id=${encodeURIComponent(o.iptvId)}`, at: now, read: false });
    }
  }
  const eps: Snapshot["eps"] = obs.eps ? {} : { ...s.snap.eps };
  if (obs.eps) {
    for (const [sid, list] of Object.entries(obs.eps)) {
      const prev = s.snap.eps[sid];
      const ids = list.slice().sort((a, b) => b.at - a.at).map((e) => e.id);
      if (!first && prev) {
        const known = new Set(prev);
        for (const e of list) {
          if (known.has(e.id)) continue;
          fresh.push({ id: `ep:${e.id}`, kind: "episode", title: e.show, season: e.season, episode: e.episode, link: `/item/${e.id}`, at: Math.min(now, e.at > 0 ? e.at : now), read: false });
        }
      }
      eps[sid] = [...new Set([...ids, ...(prev ?? [])])].slice(0, MAX_EPS_PER_SERIES);
    }
  }
  const capped = (o: Snapshot["titles"]) => (Object.keys(o).length > MAX_TITLES ? Object.fromEntries(Object.entries(o).slice(-MAX_TITLES)) : o);
  const seriesKeys = Object.keys(eps);
  const cappedEps = seriesKeys.length > MAX_SERIES ? Object.fromEntries(Object.entries(eps).slice(-MAX_SERIES)) : eps;
  return {
    ...s,
    seeded: true,
    lastRefresh: now,
    snap: { titles: capped(titles), eps: cappedEps },
    events: tidyEvents([...fresh, ...s.events], now),
  };
}

export const dueForRefresh = (s: Store, now: number) => now - s.lastRefresh >= THROTTLE_MS;

/** "<Title> is now in your library", "New episode: <Show> S2E5", … */
export function eventText(e: InboxEvent): string {
  if (e.kind === "episode") {
    const num = e.season != null && e.episode != null ? ` S${e.season}E${e.episode}` : "";
    return `New episode: ${e.title}${num}`;
  }
  if (e.kind === "iptv") return `${e.title} is now available via IPTV`;
  return e.requested ? `Your request for ${e.title} is now in your library` : `${e.title} is now in your library`;
}

/** The IPTV copy of a title: same TMDB id and kind, on a provider the person owns or has shared with them. */
export function pickIptv(items: VodItem[], media: "movie" | "tv", tmdbId: number, allowedProviderIds: Iterable<string | number>): VodItem | null {
  const kind = media === "tv" ? "series" : "movie";
  return ownedVod(items, allowedProviderIds).find((i) => i.kind === kind && i.tmdbId === tmdbId) ?? null;
}

/** Relative time for the list ("2h ago", "3d ago"). */
export function ago(at: number, now = Date.now()): string {
  const m = Math.max(0, Math.round((now - at) / 60_000));
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}
