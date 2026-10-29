// Jellytip companion API (same origin, /jellytip-api). Authenticates with the user's Jellyfin token.
// Everything fails soft: a problem returns null and the UI falls back to plain Jellyfin data.

import { getSession } from "./jellyfin";
import { isNative, nativeGetJson, nativePostJson, SERVER_ORIGIN } from "../lib/native";
import { parseCollection, parseRows, type Collection, type ServerRow } from "../lib/rows";
import { registerLogoStyles } from "../lib/logoStyle";
export type { Collection, CollectionPart, RowLayout, ServerRow } from "../lib/rows";

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
  /** Top of the highest face minus 40% of its height, as a fraction of image height: must stay in frame. */
  headTop?: number | null;
}

export interface BackdropInfo {
  /** Photo-ranked best index (-1 when animation / none). */
  best: number;
  heroIndex: number | null;
  detailsIndex: number | null;
  thumbGood: boolean;
  /** Whether the Thumb has the title in the picture (null = not checked yet; treated as titled). */
  thumbTitled: boolean | null;
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
    backdrops?: { index: number; tag?: string; focalX?: number | null; focalY?: number | null; face?: boolean; headTop?: number | null }[] | null;
    heroIndex?: number | null;
    detailsIndex?: number | null;
    thumb?: { good?: boolean; titled?: boolean | null } | null;
    thumbTitled?: boolean | null;
    logoStyle?: string | null;
  }>(`/v1/items/${id}/backdrops`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  if (raw.logoStyle) registerLogoStyles({ [id]: raw.logoStyle });
  const arr = raw.backdrops ?? [];
  const focal = new Map<number, Focal>();
  const tags = new Map<number, string>();
  arr.forEach((b, i) => {
    const idx = b.index ?? i;
    if (b.tag) tags.set(idx, b.tag);
    if (b.focalX != null && b.focalY != null)
      focal.set(idx, { x: b.focalX, y: b.focalY, face: !!b.face, headTop: typeof b.headTop === "number" ? Math.min(Math.max(b.headTop, 0), 1) : null });
  });
  const idx = (v: number | null | undefined) => (v != null && v >= 0 ? v : null);
  return {
    best: raw.animation || arr.length === 0 ? -1 : arr[0].index ?? -1,
    heroIndex: idx(raw.heroIndex),
    detailsIndex: idx(raw.detailsIndex),
    thumbGood: raw.thumb?.good ?? true,
    thumbTitled: raw.thumbTitled ?? raw.thumb?.titled ?? null,
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

// ---------- reviews ----------

export interface Review {
  id: string;
  author: string;
  own: boolean;
  /** Out of 5 in half steps; null when the reviewer gave none. */
  rating: number | null;
  likes: number | null;
  /** ~200 chars; empty for spoilers and stars-only own ratings. */
  excerpt: string;
  text: string;
  spoiler: boolean;
  source: string;
  url: string | null;
}

export interface Quote {
  text: string;
  author: string;
  source: string;
}

export interface Reviews {
  /** "letterboxd" | "tmdb" | "none" */
  source: string;
  quote: Quote | null;
  reviews: Review[];
}

const reviewsCache = cached<Reviews>(async (id, job) => {
  const raw = await getJson<Partial<Reviews>>(`/v1/items/${id}/reviews`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  return { source: raw.source ?? "none", quote: raw.quote?.text ? raw.quote : null, reviews: raw.reviews ?? [] };
});

/** Reviews for a movie or series: Ricki's own first, then the most-liked (the server filters spoilers and profanity). */
export const reviews = (id: string, opts?: RequestOptions) => reviewsCache.get(id, opts);

const quoteCache = cached<{ quote: Quote | null }>(async (id, job) => {
  const full = reviewsCache.peek(id);
  if (full) return { quote: full.quote };
  const raw = await getJson<{ quote?: Quote | null }>(`/v1/items/${id}/reviews?fields=quote`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  return { quote: raw.quote?.text ? raw.quote : null };
});

/** Short Letterboxd pull-quote for the home hero (never triggers a TMDB lookup); null when there is none. */
export const reviewQuote = (id: string, opts?: RequestOptions) => quoteCache.get(id, opts).then((q) => q?.quote ?? null);

// ---------- specials (new carousel formats; contract: ~/ai/plans/jellytip-specials.md) ----------

export type SpecialsPage = "home" | "movies" | "shows";

interface SpecialBase {
  id: string;
  layout: string;
  /** Insert before the Nth normal carousel row (0 = right under Continue Watching). */
  position: number;
  title?: string;
  /** Item ids whose tile art has no title in the picture: those tiles draw the title over it. */
  untitled?: string[];
}

export interface TrailerBreak extends SpecialBase {
  layout: "trailer_break";
  itemId: string;
  trailerItemId?: string;
  reason?: string;
}
export interface PersonSpotlight extends SpecialBase {
  layout: "person_spotlight";
  personId: string;
  name: string;
  role: "actor" | "director";
  imageTag?: string;
  filmCount: number;
  unwatchedCount: number;
  itemIds: string[];
}
export interface TonightsPick extends SpecialBase {
  layout: "tonights_pick";
  itemId: string;
  reason?: string;
  kicker?: string;
}
export interface Top10 extends SpecialBase {
  layout: "top10";
  variant: "household" | "letterboxd";
  itemIds: string[];
}
export interface DoubleFeature extends SpecialBase {
  layout: "double_feature";
  pairs: { a: string; b: string; reason?: string; totalRuntimeMin?: number }[];
}
export interface BecauseLoved extends SpecialBase {
  layout: "because_loved";
  anchorItemId: string;
  rating?: number | null;
  quote?: string | null;
  itemIds: string[];
}
export interface AroundHouse extends SpecialBase {
  layout: "around_house";
  entries: { itemId: string; who: string; what: "finished" | "watching" | "requested"; when: string }[];
}
export interface ClosetPick {
  title: string;
  year?: number;
  itemId?: string | null;
  tmdbId?: number | null;
  posterUrl?: string | null;
  atSec?: number | null;
}
export interface ClosetPicks extends SpecialBase {
  layout: "closet_picks";
  guest: string;
  videoUrl: string;
  posterUrl?: string | null;
  durationSec?: number;
  picks: ClosetPick[];
}

export type Special = TrailerBreak | PersonSpotlight | TonightsPick | Top10 | DoubleFeature | BecauseLoved | AroundHouse | ClosetPicks;

export const SPECIAL_LAYOUTS = ["trailer_break", "person_spotlight", "tonights_pick", "top10", "double_feature", "because_loved", "around_house", "closet_picks"];

const today = () => new Date().toISOString().slice(0, 10);

// One call per page per day (the server picks the same specials all day); 404 / failure = page as before
const specialsCache = cached<Special[]>(async (key, job) => {
  const page = key.split("|")[0];
  const raw = await getJson<{ specials?: (Partial<Special> & { logoStyles?: unknown })[]; logoStyles?: unknown }>(`/v1/home/specials?page=${page}`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  registerLogoStyles(raw.logoStyles);
  raw.specials?.forEach((s) => registerLogoStyles(s?.logoStyles));
  return (raw.specials ?? []).filter((s): s is Special => !!s && typeof s.id === "string" && SPECIAL_LAYOUTS.includes(s.layout ?? "")).map((s) => ({
      ...s,
      position: Math.max(0, Number(s.position) || 0),
      untitled: Array.isArray(s.untitled) ? s.untitled.filter((x) => typeof x === "string") : undefined,
    }));
});

export function specials(page: SpecialsPage): Promise<Special[]> {
  return specialsCache.get(`${page}|${today()}`, { priority: "high" }).then((s) => s ?? []);
}

/** Media/image URL from the companion: absolute stays (http/https), "/path" is on the server origin, else relative to the API. */
export function companionUrl(u: string): string {
  if (/^https?:\/\//i.test(u)) return u;
  if (u.startsWith("/")) return `${SERVER_ORIGIN}${u}`;
  return `${COMPANION_BASE}/${u}`;
}

/** Closet clip / poster from the companion: needs the token as api_key (a <video> can't send the auth header). */
export function companionMediaUrl(u: string): string {
  const url = companionUrl(u);
  const token = getSession()?.token;
  const ours = url.startsWith(COMPANION_BASE) || (!SERVER_ORIGIN && url.startsWith("/jellytip-api/"));
  if (!ours || !token || /[?&]api_key=/.test(url)) return url;
  return `${url}${url.includes("?") ? "&" : "?"}api_key=${encodeURIComponent(token)}`;
}

export interface RequestResult {
  status?: "exists" | "blocked" | "added";
  reason?: string;
  title?: string;
  freeGbAfter?: number | null;
  error?: string;
}

/** "Get it" (admins): POST /v1/request {tmdbId, type}; dryRun first to show what would happen. */
export async function requestTitle(tmdbId: number, type: "movie" | "tv", dryRun: boolean): Promise<{ code: number; body: RequestResult }> {
  const token = getSession()?.token;
  if (!token) return { code: 401, body: { error: "signed out" } };
  const url = `${BASE}/v1/request${dryRun ? "?dryRun=1" : ""}`;
  const headers = { Authorization: `MediaBrowser Token="${token}"` };
  const body = { tmdbId, type };
  try {
    if (isNative) {
      const r = await nativePostJson<RequestResult>(url, headers, body);
      return { code: r.status, body: r.data ?? {} };
    }
    const res = await fetch(url, { method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    return { code: res.status, body: ((await res.json().catch(() => ({}))) as RequestResult) ?? {} };
  } catch (e) {
    return { code: 0, body: { error: String((e as Error).message ?? e) } };
  }
}

// ---------- rows v1 (server-picked rows per page per day; contract: ~/ai/plans/jellytip-10x.md) ----------

// One call per page per day; null (404, old server, no `rows` field) = the page builds its rows as before
const rowsCache = cached<ServerRow[]>(async (key, job) => {
  const page = key.split("|")[0];
  const raw = await getJson<{ rowsVersion?: number; rows?: unknown; logoStyles?: unknown }>(`/v1/home/carousels?rows=1&page=${page}`, job, 12000);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  registerLogoStyles(raw.logoStyles);
  if (Array.isArray(raw.rows)) raw.rows.forEach((r) => registerLogoStyles((r as { logoStyles?: unknown } | null)?.logoStyles));
  return parseRows(raw.rows);
});

export function serverRows(page: SpecialsPage): Promise<ServerRow[] | null> {
  return rowsCache.get(`${page}|${today()}`, { priority: "high" });
}

export interface CollectionPage extends Collection {
  missing: { tmdbId: number; title: string; year?: number | null; posterUrl?: string | null; released?: boolean }[];
  /** Caller is an admin: missing parts get "Get it". */
  canRequest: boolean;
  art: { itemId: string; name?: string; thumb?: string; lang?: string }[];
}

const collections = cached<CollectionPage>(async (id, job) => {
  const raw = await getJson<Partial<CollectionPage>>(`/v1/collections/${id}`, job);
  if (raw === CANCELLED || !raw) return raw === CANCELLED ? CANCELLED : null;
  const c = parseCollection(raw);
  if (!c) return null;
  return {
    ...c,
    missing: Array.isArray(raw.missing) ? raw.missing.filter((m) => m && typeof m.tmdbId === "number" && m.title) : [],
    canRequest: raw.canRequest === true,
    art: Array.isArray(raw.art) ? raw.art.filter((a) => a && typeof a.itemId === "string") : [],
  };
});

/** Franchise page data; null when the server has no such collection (404: fewer than 2 parts in the library). */
export const collectionPage = (tmdbId: number) => collections.get(String(tmdbId), { priority: "high" });
