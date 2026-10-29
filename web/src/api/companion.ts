// Jellytip companion API (same origin, /jellytip-api). Authenticates with the user's Jellyfin token.
// Everything fails soft: a problem returns null and the UI falls back to plain Jellyfin data.

import { getSession } from "./jellyfin";
import { isNative, nativeGetJson, SERVER_ORIGIN } from "../lib/native";

export const COMPANION_BASE = `${SERVER_ORIGIN}/jellytip-api`;
const BASE = COMPANION_BASE;
const FAILURE_TTL_MS = 60_000;
// Server allows 15 req/s per IP with a burst of 60; stay well under it so a screen full of cards never trips 429.
const MAX_CONCURRENT = 4;
const MIN_GAP_MS = 90;

/** One queued companion request. Higher priority starts first; a cancelled job that hasn't started is dropped. */
interface Job {
  priority: number;
  cancelled: boolean;
}

export interface RequestOptions {
  /** Details / hero lookups jump ahead of the tile rankings queued for a screen full of cards. */
  priority?: "high";
  /** Abort when the caller goes away (an unmounted card); the request is dropped once no caller still wants it. */
  signal?: AbortSignal;
}

const CANCELLED = Symbol("cancelled");
const PRIORITY_HIGH = 1;

let active = 0;
let lastStart = 0;
const waiting: { job: Job; go: (run: boolean) => void }[] = [];

function pump() {
  for (let i = waiting.length - 1; i >= 0; i--) if (waiting[i].job.cancelled) waiting.splice(i, 1)[0].go(false);
  if (active >= MAX_CONCURRENT || waiting.length === 0) return;
  const wait = lastStart + MIN_GAP_MS - Date.now();
  if (wait > 0) {
    setTimeout(pump, wait);
    return;
  }
  let best = 0; // highest priority, FIFO within a priority
  for (let i = 1; i < waiting.length; i++) if (waiting[i].job.priority > waiting[best].job.priority) best = i;
  const [next] = waiting.splice(best, 1);
  lastStart = Date.now();
  active++;
  next.go(true);
}

async function throttled<T>(job: Job, fn: () => Promise<T>): Promise<T | typeof CANCELLED> {
  const run = await new Promise<boolean>((go) => {
    waiting.push({ job, go });
    pump();
  });
  if (!run) return CANCELLED;
  try {
    return await fn();
  } finally {
    active--;
    pump();
  }
}

async function getJson<T>(path: string, job: Job = { priority: PRIORITY_HIGH, cancelled: false }, timeoutMs = 8000): Promise<T | null | typeof CANCELLED> {
  const token = getSession()?.token;
  if (!token) return null;
  return throttled(job, async () => {
    const headers = { Authorization: `MediaBrowser Token="${token}"` };
    if (isNative) {
      try {
        const r = await nativeGetJson<T>(BASE + path, headers, timeoutMs);
        return r.status >= 200 && r.status < 300 ? r.data : null;
      } catch {
        return null;
      }
    }
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const res = await fetch(BASE + path, { headers, signal: ctl.signal });
      if (!res.ok) return null;
      return (await res.json()) as T;
    } catch {
      return null;
    } finally {
      clearTimeout(t);
    }
  });
}

// Everything cached here belongs to one signed-in user: dropped when the session changes (sign out / other user)
let generation = 0;
const resetters: (() => void)[] = [];
window.addEventListener("jellytip-session", () => {
  generation++;
  resetters.forEach((r) => r());
});

interface Entry<T> {
  job: Job;
  refs: number;
  pinned: boolean;
  promise: Promise<T | null>;
}

/**
 * Per-key cache: values kept for the session, failures retried after a minute, one request in flight per key
 * (shared by every caller; dropped from the queue only when all callers that passed a signal have aborted).
 */
function cached<T>(fetcher: (key: string, job: Job) => Promise<T | null | typeof CANCELLED>) {
  const values = new Map<string, T>();
  const failedAt = new Map<string, number>();
  const inFlight = new Map<string, Entry<T>>();
  resetters.push(() => {
    values.clear();
    failedAt.clear();
    inFlight.forEach((e) => (e.job.cancelled = true));
    inFlight.clear();
  });
  return {
    peek: (key: string): T | undefined => values.get(key),
    get(key: string, opts: RequestOptions = {}): Promise<T | null> {
      if (values.has(key)) return Promise.resolve(values.get(key)!);
      const f = failedAt.get(key);
      if (f && Date.now() - f < FAILURE_TTL_MS) return Promise.resolve(null);
      if (opts.signal?.aborted) return Promise.resolve(null);
      let e = inFlight.get(key);
      if (!e) {
        const job: Job = { priority: 0, cancelled: false };
        const gen = generation;
        const entry: Entry<T> = { job, refs: 0, pinned: false, promise: Promise.resolve(null) };
        entry.promise = fetcher(key, job).then((v) => {
          if (inFlight.get(key) === entry) inFlight.delete(key);
          if (v === CANCELLED || gen !== generation) return null; // dropped, or answered for a previous session
          if (v == null) failedAt.set(key, Date.now());
          else values.set(key, v);
          return v;
        });
        inFlight.set(key, entry);
        e = entry;
      }
      const entry = e;
      if (opts.priority === "high") entry.job.priority = PRIORITY_HIGH;
      if (opts.signal) {
        entry.refs++;
        opts.signal.addEventListener(
          "abort",
          () => {
            if (--entry.refs > 0 || entry.pinned) return;
            entry.job.cancelled = true;
            if (inFlight.get(key) === entry) inFlight.delete(key);
            pump();
          },
          { once: true },
        );
      } else entry.pinned = true; // a caller without a signal always wants the answer
      return entry.promise;
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

const backdrops = cached<BackdropInfo>(async (id, job) => {
  const raw = await getJson<{
    animation?: boolean;
    backdrops?: { index: number; tag?: string; focalX?: number | null; focalY?: number | null; face?: boolean }[] | null;
    heroIndex?: number | null;
    detailsIndex?: number | null;
    thumb?: { good?: boolean } | null;
  }>(`/v1/items/${id}/backdrops`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
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

export const backdropInfo = (id: string, opts?: RequestOptions) => backdrops.get(id, opts);
export const peekBackdropInfo = (id: string) => backdrops.peek(id);

const extrasCache = cached<Extras>(async (key, job) => {
  const [id, type] = key.split("|");
  const raw = await getJson<{ scores?: Score[]; letterboxd?: LetterboxdMine | null }>(`/v1/items/${id}/extras?type=${type}`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  return { scores: (raw.scores ?? []).filter((s) => s.value != null), letterboxd: raw.letterboxd ?? null };
});

/** Scores + Letterboxd for a movie or series (the server resolves provider ids from the item). */
export function extras(id: string, type: "movie" | "series") {
  return extrasCache.get(`${id}|${type}`, { priority: "high" });
}

const episodeArt = cached<Map<string, boolean>>(async (parentId, job) => {
  const raw = await getJson<{ episodeId: string; usable?: boolean }[]>(`/v1/items/${parentId}/episodes-art`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  return new Map(raw.map((e) => [e.episodeId.replace(/-/g, ""), e.usable !== false]));
});

/** Usable-still flags for every episode of a series (unknown counts as usable). */
export const episodeArtFor = (seriesId: string, opts?: RequestOptions) => episodeArt.get(seriesId, opts);
export const peekEpisodeArt = (seriesId: string) => episodeArt.peek(seriesId);

let manifestPromise: Promise<Manifest | null> | null = null;
resetters.push(() => (manifestPromise = null));

export function manifest(): Promise<Manifest | null> {
  if (!manifestPromise) {
    const gen = generation;
    const p: Promise<Manifest | null> = getJson<{ carousels?: Partial<Carousel>[]; letterboxd?: Partial<LetterboxdRow>[] }>("/v1/home/carousels").then((raw) => {
      if (!raw || raw === CANCELLED || gen !== generation) {
        if (manifestPromise === p) manifestPromise = null; // retry on the next visit
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
    manifestPromise = p;
  }
  return manifestPromise;
}
