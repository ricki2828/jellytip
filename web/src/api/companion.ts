// Jellytip companion API (same origin, /jellytip-api). Authenticates with the user's Jellyfin token.
// Everything fails soft: a problem returns null and the UI falls back to plain Jellyfin data.

import { getSession } from "./jellyfin";

const BASE = "/jellytip-api";
const FAILURE_TTL_MS = 60_000;
// Server allows 15 req/s per IP with a burst of 60; stay well under it so a screen full of cards never trips 429.
const MAX_CONCURRENT = 4;
const MIN_GAP_MS = 90;

let active = 0;
let lastStart = 0;
const waiting: (() => void)[] = [];

function pump() {
  if (active >= MAX_CONCURRENT || waiting.length === 0) return;
  const wait = lastStart + MIN_GAP_MS - Date.now();
  if (wait > 0) {
    setTimeout(pump, wait);
    return;
  }
  lastStart = Date.now();
  active++;
  waiting.shift()!();
}

async function throttled<T>(fn: () => Promise<T>): Promise<T> {
  await new Promise<void>((resolve) => {
    waiting.push(resolve);
    pump();
  });
  try {
    return await fn();
  } finally {
    active--;
    pump();
  }
}

async function getJson<T>(path: string, timeoutMs = 8000): Promise<T | null> {
  const token = getSession()?.token;
  if (!token) return null;
  return throttled(async () => {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const res = await fetch(BASE + path, { headers: { Authorization: `MediaBrowser Token="${token}"` }, signal: ctl.signal });
      if (!res.ok) return null;
      return (await res.json()) as T;
    } catch {
      return null;
    } finally {
      clearTimeout(t);
    }
  });
}

/** Per-key cache: values forever (per page load), failures retried after a minute, one request in flight per key. */
function cached<T>(fetcher: (key: string) => Promise<T | null>) {
  const values = new Map<string, T>();
  const failedAt = new Map<string, number>();
  const inFlight = new Map<string, Promise<T | null>>();
  return {
    peek: (key: string): T | undefined => values.get(key),
    get(key: string): Promise<T | null> {
      if (values.has(key)) return Promise.resolve(values.get(key)!);
      const f = failedAt.get(key);
      if (f && Date.now() - f < FAILURE_TTL_MS) return Promise.resolve(null);
      let p = inFlight.get(key);
      if (!p) {
        p = fetcher(key).then((v) => {
          if (v == null) failedAt.set(key, Date.now());
          else values.set(key, v);
          inFlight.delete(key);
          return v;
        });
        inFlight.set(key, p);
      }
      return p;
    },
  };
}

// ---------- types ----------

export interface Score {
  source: string;
  label: string;
  value: number;
  max?: number | null;
  votes?: number | null;
  url?: string | null;
}

export interface LetterboxdMine {
  myRating?: number | null;
  liked?: boolean;
  watchedDates?: string[];
  rewatch?: boolean;
  url?: string | null;
}

export interface Extras {
  scores: Score[];
  letterboxd: LetterboxdMine | null;
}

export interface Focal {
  x: number;
  y: number;
  face: boolean;
}

export interface BackdropInfo {
  /** Photo-ranked best index (-1 when animation / none). */
  best: number;
  heroIndex: number | null;
  detailsIndex: number | null;
  thumbGood: boolean;
  focal: Map<number, Focal>;
  tags: Map<number, string>;
}

export interface Carousel {
  id: string;
  title: string;
  order: string;
  mediaType: "movie" | "tv";
  itemIds: string[];
}

export interface LetterboxdRow {
  slug: string;
  name: string;
  mediaType: string;
  itemIds: string[];
}

export interface Manifest {
  carousels: Carousel[];
  letterboxd: LetterboxdRow[];
}

// ---------- endpoints ----------

const backdrops = cached<BackdropInfo>(async (id) => {
  const raw = await getJson<{
    animation?: boolean;
    backdrops?: { index: number; tag?: string; focalX?: number | null; focalY?: number | null; face?: boolean }[] | null;
    heroIndex?: number | null;
    detailsIndex?: number | null;
    thumb?: { good?: boolean } | null;
  }>(`/v1/items/${id}/backdrops`);
  if (!raw) return null;
  const arr = raw.backdrops ?? [];
  const focal = new Map<number, Focal>();
  const tags = new Map<number, string>();
  arr.forEach((b, i) => {
    const idx = b.index ?? i;
    if (b.tag) tags.set(idx, b.tag);
    if (b.focalX != null && b.focalY != null) focal.set(idx, { x: b.focalX, y: b.focalY, face: !!b.face });
  });
  const idx = (v: number | null | undefined) => (v != null && v >= 0 ? v : null);
  return {
    best: raw.animation || arr.length === 0 ? -1 : arr[0].index ?? -1,
    heroIndex: idx(raw.heroIndex),
    detailsIndex: idx(raw.detailsIndex),
    thumbGood: raw.thumb?.good ?? true,
    focal,
    tags,
  };
});

export const backdropInfo = (id: string) => backdrops.get(id);
export const peekBackdropInfo = (id: string) => backdrops.peek(id);

const extrasCache = cached<Extras>(async (key) => {
  const [id, type] = key.split("|");
  const raw = await getJson<{ scores?: Score[]; letterboxd?: LetterboxdMine | null }>(`/v1/items/${id}/extras?type=${type}`);
  if (!raw) return null;
  return { scores: (raw.scores ?? []).filter((s) => s.value != null), letterboxd: raw.letterboxd ?? null };
});

/** Scores + Letterboxd for a movie or series (the server resolves provider ids from the item). */
export function extras(id: string, type: "movie" | "series") {
  return extrasCache.get(`${id}|${type}`);
}

const episodeArt = cached<Map<string, boolean>>(async (parentId) => {
  const raw = await getJson<{ episodeId: string; usable?: boolean }[]>(`/v1/items/${parentId}/episodes-art`);
  if (!raw) return null;
  return new Map(raw.map((e) => [e.episodeId.replace(/-/g, ""), e.usable !== false]));
});

/** Usable-still flags for every episode of a series (unknown counts as usable). */
export const episodeArtFor = (seriesId: string) => episodeArt.get(seriesId);
export const peekEpisodeArt = (seriesId: string) => episodeArt.peek(seriesId);

let manifestPromise: Promise<Manifest | null> | null = null;

export function manifest(): Promise<Manifest | null> {
  if (!manifestPromise) {
    manifestPromise = getJson<{ carousels?: Partial<Carousel>[]; letterboxd?: Partial<LetterboxdRow>[] }>("/v1/home/carousels").then((raw) => {
      if (!raw) {
        manifestPromise = null; // retry on the next visit
        return null;
      }
      return {
        carousels: (raw.carousels ?? [])
          .filter((c) => c.id && (c.title || (c as { name?: string }).name))
          .map((c) => ({
            id: c.id!,
            title: c.title || (c as { name?: string }).name || "",
            order: c.order ?? "custom",
            mediaType: c.mediaType === "tv" ? "tv" : "movie",
            itemIds: c.itemIds ?? [],
          })),
        letterboxd: (raw.letterboxd ?? [])
          .filter((l) => l.slug && l.name && l.itemIds?.length)
          .map((l) => ({ slug: l.slug!, name: l.name!, mediaType: l.mediaType ?? "movie", itemIds: l.itemIds! })),
      };
    });
  }
  return manifestPromise;
}
