// Personal scheduled channels (N7). Pure; tested in web/test/channels.test.ts.
// A channel is a seeded-shuffle lineup of the signed-in person's own library items, laid back to back from the start of
// a fixed epoch. "What's on now" is a function of the wall clock alone: the same item at the same offset whenever, and
// on whichever device, someone tunes in. Each full pass over the pool is reshuffled.

import { VOD_ID_RE } from "./iptvVod.ts";

export const TICKS_PER_SECOND = 10_000_000;
/** Fixed origin of every channel's timeline (2026-01-05 00:00 UTC). */
export const EPOCH_MS = Date.UTC(2026, 0, 5);
/** A channel needs at least this many playable items to exist. */
export const MIN_ITEMS = 3;
/** Don't tune in to something with less than this left; start the next item instead. */
export const TUNE_MIN_LEFT_S = 15;

export interface ChannelDef {
  id: string;
  name: string;
  blurb: string;
  /** Movie: films matched by genre/years. Episode: episodes of series matched by genre (genres live on the series). */
  type: "Movie" | "Episode";
  genres?: string[];
  yearFrom?: number;
  yearTo?: number;
  /** Longest single item allowed, in minutes (a "sitcom" channel does not want a double-length special). */
  maxMinutes?: number;
  /** Shortest single item allowed, in minutes. */
  minMinutes?: number;
  /** TMDB genre ids: a channel also draws films (Movie) or episodes (Episode, TV genre ids) from the person's IPTV catalogue. */
  tmdbGenres?: number[];
}

export const CHANNELS: ChannelDef[] = [
  { id: "90s-action", name: "90s Action", blurb: "Action films from 1990 to 1999", type: "Movie", genres: ["Action"], yearFrom: 1990, yearTo: 1999, tmdbGenres: [28] },
  { id: "sunday-sitcoms", name: "Sunday Sitcoms", blurb: "Half-hour comedy episodes", type: "Episode", genres: ["Comedy"], maxMinutes: 40, tmdbGenres: [35] },
  { id: "sitcom-classics", name: "Sitcom Classics", blurb: "Comedy episodes, a few of a show back to back", type: "Episode", genres: ["Comedy"], minMinutes: 20, maxMinutes: 35, tmdbGenres: [35] },
  { id: "light-bites", name: "Light Bites", blurb: "Easy reality, talk and lifestyle episodes", type: "Episode", genres: ["Reality", "Talk Show", "Talk"], minMinutes: 15, maxMinutes: 35, tmdbGenres: [10764, 10767] },
  { id: "comedy", name: "Comedy", blurb: "Comedy films", type: "Movie", genres: ["Comedy"], tmdbGenres: [35] },
  { id: "sci-fi-nights", name: "Sci-Fi Nights", blurb: "Science fiction films", type: "Movie", genres: ["Science Fiction"], tmdbGenres: [878] },
  { id: "documentaries", name: "Documentaries", blurb: "Documentary films", type: "Movie", genres: ["Documentary"], tmdbGenres: [99] },
  { id: "family-night", name: "Family Night", blurb: "Family and animated films", type: "Movie", genres: ["Family", "Animation"], tmdbGenres: [10751, 16] },
];

/** The slice of an Item a channel keeps (small enough for sessionStorage). */
export interface ChanItem {
  Id: string;
  Name: string;
  Type?: string;
  SeriesName?: string;
  ParentIndexNumber?: number;
  IndexNumber?: number;
  SeriesId?: string;
  ProductionYear?: number;
  RunTimeTicks?: number;
  /** TMDB id (films): the same title in the library and on IPTV is kept once, the library copy. */
  Tmdb?: string;
  /** TMDB id of the series (episodes): the same show in the library and on IPTV is kept once, the library's. */
  SeriesTmdb?: string;
  /** IPTV items only: https TMDB backdrop for the Home card. */
  Art?: string;
}

export interface Slot {
  item: ChanItem;
  /** Absolute times, ms since the Unix epoch. */
  startMs: number;
  endMs: number;
}

// ---------- IPTV films ----------

/** An IPTV episode id (pid:e:eid), as the channel pool serves it. */
export const EPISODE_ID_RE = /^[A-Za-z0-9_-]{1,40}:e:[A-Za-z0-9_.-]{1,64}$/;
export const isIptvEpisodeId = (id: string): boolean => EPISODE_ID_RE.test(id);
/** An IPTV id (pid:m:sid / pid:s:sid / pid:e:eid): never a Jellyfin id (32 hex). */
export const isIptvId = (id: string): boolean => VOD_ID_RE.test(id) || EPISODE_ID_RE.test(id);

/** Runtime window (seconds) an IPTV film must sit in to be scheduled: anything else is a stub or a pack, not a runtime. */
export const IPTV_MIN_S = 40 * 60;
export const IPTV_MAX_S = 300 * 60;
/** Most IPTV films one channel keeps. */
export const IPTV_CAP = 100;

const TMDB_ART = /^https:\/\/image\.tmdb\.org\//;

/** GET /v1/iptv/channel-pool → ChanItems. Films only; needs a valid id, a title, a trustworthy runtime; langUnknown is out. */
export function iptvPoolItems(raw: unknown): { items: ChanItem[]; pending: number } {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const list = Array.isArray(r.items) ? r.items : [];
  const seen = new Set<string>();
  const items: ChanItem[] = [];
  for (const x of list) {
    const o = x && typeof x === "object" ? (x as Record<string, unknown>) : null;
    if (!o) continue;
    const id = typeof o.id === "string" ? o.id : "";
    const title = typeof o.title === "string" ? o.title : "";
    const secs = typeof o.runtime === "number" && isFinite(o.runtime) ? Math.floor(o.runtime) : 0;
    if (!isIptvId(id) || !id.includes(":m:") || !title || seen.has(id) || o.langUnknown === true) continue;
    if (secs < IPTV_MIN_S || secs > IPTV_MAX_S) continue;
    seen.add(id);
    const art = typeof o.backdrop === "string" && TMDB_ART.test(o.backdrop) ? o.backdrop : typeof o.poster === "string" && TMDB_ART.test(o.poster) ? o.poster : undefined;
    items.push({
      Id: id,
      Name: title,
      Type: "Movie",
      ProductionYear: typeof o.year === "number" ? o.year : undefined,
      RunTimeTicks: secs * TICKS_PER_SECOND,
      Tmdb: typeof o.tmdbId === "number" ? String(o.tmdbId) : undefined,
      Art: art,
    });
  }
  return { items, pending: typeof r.pending === "number" && r.pending > 0 ? Math.floor(r.pending) : 0 };
}

/** Library items plus IPTV films. The library copy wins when both have the TMDB title; IPTV films are capped by a stable hash sample. */
export function mixItems(library: ChanItem[], iptv: ChanItem[], cap = IPTV_CAP, salt = "iptv"): ChanItem[] {
  const have = new Set(library.map((i) => i.Tmdb).filter((t): t is string => !!t));
  const seen = new Set<string>();
  const extra = iptv.filter((i) => {
    if (i.Tmdb) {
      if (have.has(i.Tmdb) || seen.has(i.Tmdb)) return false;
      seen.add(i.Tmdb);
    }
    return true;
  });
  return [...library, ...sample(extra, cap, salt)];
}

/** Runtime window (seconds) an IPTV episode must sit in (the server serves 8-100 min). */
export const IPTV_EP_MIN_S = 5 * 60;
export const IPTV_EP_MAX_S = 120 * 60;
/** Most IPTV episodes one episode channel keeps (whole series runs, never a partial run cut at the cap). */
export const IPTV_EP_CAP = 200;

/** GET /v1/iptv/channel-pool?kind=episode → ChanItems (Type Episode, SeriesId = the IPTV series id the player needs). */
export function iptvEpisodeItems(raw: unknown): { items: ChanItem[]; pending: number } {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const list = Array.isArray(r.items) ? r.items : [];
  const seen = new Set<string>();
  const items: ChanItem[] = [];
  for (const x of list) {
    const o = x && typeof x === "object" ? (x as Record<string, unknown>) : null;
    if (!o) continue;
    const id = typeof o.id === "string" ? o.id : "";
    const seriesId = typeof o.seriesId === "string" ? o.seriesId : "";
    const seriesTitle = typeof o.seriesTitle === "string" ? o.seriesTitle : "";
    const season = typeof o.season === "number" && isFinite(o.season) ? Math.floor(o.season) : 0;
    const ep = typeof o.episode === "number" && isFinite(o.episode) ? Math.floor(o.episode) : -1;
    const secs = typeof o.runtime === "number" && isFinite(o.runtime) ? Math.floor(o.runtime) : 0;
    if (!isIptvEpisodeId(id) || !VOD_ID_RE.test(seriesId) || !seriesId.includes(":s:") || !seriesTitle || season < 1 || ep < 0 || seen.has(id) || o.langUnknown === true) continue;
    if (secs < IPTV_EP_MIN_S || secs > IPTV_EP_MAX_S) continue;
    seen.add(id);
    const art = typeof o.backdrop === "string" && TMDB_ART.test(o.backdrop) ? o.backdrop : typeof o.poster === "string" && TMDB_ART.test(o.poster) ? o.poster : undefined;
    items.push({
      Id: id,
      Name: typeof o.title === "string" && o.title ? o.title : `Episode ${ep}`,
      Type: "Episode",
      SeriesName: seriesTitle,
      SeriesId: seriesId,
      ParentIndexNumber: season,
      IndexNumber: ep,
      ProductionYear: typeof o.year === "number" ? o.year : undefined,
      RunTimeTicks: secs * TICKS_PER_SECOND,
      SeriesTmdb: typeof o.tmdbId === "number" ? String(o.tmdbId) : undefined,
      Art: art,
    });
  }
  return { items, pending: typeof r.pending === "number" && r.pending > 0 ? Math.floor(r.pending) : 0 };
}

const byEpisode = (a: ChanItem, b: ChanItem): number =>
  (a.ParentIndexNumber ?? 0) - (b.ParentIndexNumber ?? 0) || (a.IndexNumber ?? 0) - (b.IndexNumber ?? 0) || (a.Id < b.Id ? -1 : a.Id > b.Id ? 1 : 0);

/**
 * Library episodes plus IPTV episodes. A library series wins over an IPTV series with the same TMDB id (all its IPTV episodes
 * go), and two IPTV series with one TMDB id collapse to the first by id. IPTV series are kept whole, in a stable hash order,
 * until `cap` episodes: a cap never splits a series' run.
 */
export function mixEpisodes(library: ChanItem[], iptv: ChanItem[], cap = IPTV_EP_CAP, salt = "iptv-ep"): ChanItem[] {
  const have = new Set(library.map((i) => i.SeriesTmdb).filter((t): t is string => !!t));
  const bySeries = new Map<string, ChanItem[]>();
  for (const e of iptv) {
    if (!e.SeriesId || (e.SeriesTmdb && have.has(e.SeriesTmdb))) continue;
    bySeries.set(e.SeriesId, [...(bySeries.get(e.SeriesId) ?? []), e]);
  }
  const claimed = new Set<string>();
  const groups = [...bySeries.entries()]
    .sort((p, q) => (p[0] < q[0] ? -1 : p[0] > q[0] ? 1 : 0))
    .filter(([, eps]) => {
      const t = eps[0].SeriesTmdb;
      if (!t) return true;
      if (claimed.has(t)) return false;
      claimed.add(t);
      return true;
    })
    .map(([sid, eps]) => [hashString(`${salt}|${sid}`), sid, eps.sort(byEpisode)] as const)
    .sort((p, q) => p[0] - q[0] || (p[1] < q[1] ? -1 : 1));
  const out: ChanItem[] = [];
  for (const [, , eps] of groups) {
    if (out.length + eps.length > cap) continue;
    out.push(...eps);
  }
  return [...library, ...out];
}

// ---------- seed ----------

/** FNV-1a, 32 bit. */
export function hashString(s: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** mulberry32: small seeded PRNG, [0,1). */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const seedFor = (channelId: string, cycle: number): number => hashString(`${channelId}|c${cycle}`);

// ---------- pool ----------

const seconds = (it: ChanItem): number => (typeof it.RunTimeTicks === "number" && isFinite(it.RunTimeTicks) ? Math.floor(it.RunTimeTicks / TICKS_PER_SECOND) : 0);

/** Items with a usable runtime, once each, whatever order the server returned them in. */
export function playable(items: ChanItem[], def?: Pick<ChannelDef, "maxMinutes" | "minMinutes">): ChanItem[] {
  const seen = new Set<string>();
  const out: ChanItem[] = [];
  for (const it of items) {
    if (!it?.Id || seen.has(it.Id)) continue;
    const s = seconds(it);
    if (s < 60) continue; // no runtime (or a stub): can't be placed on a timeline
    if (def?.maxMinutes && s > def.maxMinutes * 60) continue;
    if (def?.minMinutes && s < def.minMinutes * 60) continue;
    seen.add(it.Id);
    out.push(it);
  }
  return out;
}

/** A channel exists when its query yields enough playable items. */
export const channelViable = (items: ChanItem[], def?: Pick<ChannelDef, "maxMinutes" | "minMinutes">): boolean => playable(items, def).length >= MIN_ITEMS;

/** Keep at most `cap` items by smallest hash of id + salt: the same items every time, unbiased by server order. */
export function sample<T extends { Id: string }>(items: T[], cap: number, salt = "pool"): T[] {
  if (items.length <= cap) return items;
  return items
    .map((x) => [hashString(`${salt}|${x.Id}`), x] as const)
    .sort((p, q) => p[0] - q[0] || (p[1].Id < q[1].Id ? -1 : 1))
    .slice(0, cap)
    .map((p) => p[1]);
}

/** Like sample, but at most `perGroup` per group (e.g. per series), then `cap` overall. Items with no group key are dropped. */
export function sampleByGroup<T extends { Id: string }>(items: T[], group: (x: T) => string | undefined, perGroup: number, cap: number, salt = "pool"): T[] {
  const by = new Map<string, T[]>();
  for (const x of items) {
    const g = group(x);
    if (g) by.set(g, [...(by.get(g) ?? []), x]);
  }
  return sample([...by.values()].flatMap((l) => sample(l, perGroup, salt)), cap, salt);
}

/** The channel's playable pool in a canonical order (by id), so the schedule never depends on server order. */
export const poolOf = (items: ChanItem[], def?: Pick<ChannelDef, "maxMinutes" | "minMinutes">): ChanItem[] => playable(items, def).sort((x, y) => (x.Id < y.Id ? -1 : x.Id > y.Id ? 1 : 0));

/** Episodes per back-to-back block of one series in a lineup. */
export const BLOCK_MIN = 2;
export const BLOCK_MAX = 4;

/**
 * One pass of the channel: the pool in a seeded shuffle. Same pool + same cycle → same order.
 * Episodes of one series are not shuffled apart: each series is cut, in episode order, into blocks of 2-4 consecutive
 * episodes (a lone leftover joins the block before it) and the blocks are shuffled. Items with no SeriesId (films) are
 * single-item blocks, so a films-only pool shuffles exactly as it always did.
 */
export function lineup(pool: ChanItem[], channelId: string, cycle: number): ChanItem[] {
  const r = rng(seedFor(channelId, cycle));
  let blocks: ChanItem[][];
  if (pool.some((x) => x.SeriesId)) {
    const by = new Map<string, ChanItem[]>();
    blocks = [];
    for (const x of pool) {
      if (!x.SeriesId) blocks.push([x]);
      else by.set(x.SeriesId, [...(by.get(x.SeriesId) ?? []), x]);
    }
    for (const sid of [...by.keys()].sort()) {
      const eps = by.get(sid)!.sort(byEpisode);
      let i = 0;
      while (i < eps.length) {
        let n = BLOCK_MIN + Math.floor(r() * (BLOCK_MAX - BLOCK_MIN + 1));
        if (eps.length - i - n === 1) n++; // never strand one episode
        if (n > BLOCK_MAX + 1) n = BLOCK_MAX + 1;
        blocks.push(eps.slice(i, i + n));
        i += n;
      }
    }
    // blocks were assembled in a canonical order (films in pool order, then series by id), so the shuffle depends only on the pool
  } else blocks = pool.map((x) => [x]);
  for (let i = blocks.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [blocks[i], blocks[j]] = [blocks[j], blocks[i]];
  }
  // two blocks of one show back to back would read as a jumble (5,6 then 1,2,3): pull a later block of another show forward
  const sid = (b: ChanItem[]) => b[0].SeriesId;
  for (let i = 1; i < blocks.length; i++) {
    if (!sid(blocks[i]) || sid(blocks[i]) !== sid(blocks[i - 1])) continue;
    const j = blocks.findIndex((b, k) => k > i && sid(b) !== sid(blocks[i - 1]) && sid(b) !== sid(blocks[i + 1] ?? blocks[i]) );
    const k = j >= 0 ? j : blocks.findIndex((b, k2) => k2 > i && sid(b) !== sid(blocks[i - 1]));
    if (k > i) [blocks[i], blocks[k]] = [blocks[k], blocks[i]];
  }
  return blocks.flat();
}

export const cycleSeconds = (list: ChanItem[]): number => list.reduce((n, it) => n + seconds(it), 0);

// ---------- schedule ----------

/**
 * What is on at `nowMs` and what follows: `count` slots starting with the current one (count 4 = now + next 3).
 * Time runs from a fixed epoch. A pass over the whole pool takes the same time however it is shuffled, so pass number
 * floor((now - EPOCH) / total) is known from the clock alone; each pass is reshuffled (seed = channel + pass number).
 * An item playing across a pass boundary is never cut: the next pass simply starts when it ends. Slots after the
 * boundary come from the next pass's lineup. `pool` must come from poolOf. Empty pool → nothing.
 */
export function schedule(pool: ChanItem[], channelId: string, nowMs: number, count = 4): { slots: Slot[]; offsetS: number; progress: number } {
  const total = cycleSeconds(pool);
  if (!pool.length || total <= 0) return { slots: [], offsetS: 0, progress: 0 };
  const totalMs = total * 1000;
  const sinceMs = Math.max(0, nowMs - EPOCH_MS);
  let cycle = Math.floor(sinceMs / totalMs);
  let posMs = sinceMs - cycle * totalMs;
  let list = lineup(pool, channelId, cycle);
  let i = 0;
  while (posMs >= seconds(list[i]) * 1000) {
    posMs -= seconds(list[i]) * 1000;
    i++;
  }
  const slots: Slot[] = [];
  let t = nowMs - posMs;
  for (let k = 0; k < count; k++) {
    if (i >= list.length) {
      cycle++;
      list = lineup(pool, channelId, cycle);
      i = 0;
    }
    const it = list[i++];
    const end = t + seconds(it) * 1000;
    slots.push({ item: it, startMs: t, endMs: end });
    t = end;
  }
  const offsetS = Math.floor(posMs / 1000);
  return { slots, offsetS, progress: posMs / (seconds(slots[0].item) * 1000) };
}

export interface Target {
  id: string;
  startS: number;
  thenId: string | null;
  title: string;
  /** IPTV episodes only: the series id the IPTV player needs next to the episode id. */
  seriesId?: string;
}

/** The title a player is opened with. An IPTV episode: "Show · S1 E2 · Name" (what Continue Watching uses); anything else, the card title. */
export function playTitle(it: ChanItem): string {
  if (!isIptvEpisodeId(it.Id)) return itemLabel(it).title;
  const code = it.ParentIndexNumber != null && it.IndexNumber != null ? `S${it.ParentIndexNumber} E${it.IndexNumber}` : "";
  return [it.SeriesName ?? "", code, it.Name].filter(Boolean).join(" · ");
}

const targetFor = (it: ChanItem, startS: number, then: ChanItem | undefined): Target => ({
  id: it.Id,
  startS,
  thenId: then?.Id ?? null,
  title: playTitle(it),
  ...(isIptvEpisodeId(it.Id) && it.SeriesId ? { seriesId: it.SeriesId } : {}),
});

/** Where "Tune in" goes: the current item at its offset, or the next item from 0 when the current one is nearly over. */
export function tuneTarget(slots: Slot[], offsetS: number): Target | null {
  if (!slots.length) return null;
  const cur = slots[0];
  const left = (cur.endMs - cur.startMs) / 1000 - offsetS;
  if (left < TUNE_MIN_LEFT_S && slots.length > 1) return targetFor(slots[1].item, 0, slots[2]?.item);
  return targetFor(cur.item, Math.floor(offsetS), slots[1]?.item);
}

/**
 * Where a player goes when `currentId` has ended: the tune target, except that an item which has just ended is never tuned to
 * again (a file shorter than its listed runtime would restart on the clock and loop): the following slot, from 0.
 */
export function chainTarget(slots: Slot[], offsetS: number, currentId?: string): Target | null {
  const t = tuneTarget(slots, offsetS);
  if (!t || !currentId || t.id !== currentId) return t;
  const nxt = slots.findIndex((s) => s.item.Id !== currentId);
  if (nxt < 0) return null;
  return targetFor(slots[nxt].item, 0, slots[nxt + 1]?.item);
}

/** Chaining budget: more than CHAIN_MAX automatic hops inside CHAIN_WINDOW_MS means something is looping (stub files, bad runtimes). */
export const CHAIN_MAX = 3;
export const CHAIN_WINDOW_MS = 120_000;
export function chainBudget(stamps: number[], nowMs: number): { ok: boolean; stamps: number[] } {
  const recent = stamps.filter((t) => nowMs - t < CHAIN_WINDOW_MS && t <= nowMs);
  return recent.length >= CHAIN_MAX ? { ok: false, stamps: recent } : { ok: true, stamps: [...recent, nowMs] };
}

/**
 * A film that ended after only a few seconds when much more was expected is a stub or a short file: chaining on would just
 * burn provider connections. OK when it played a minute, or most of what was left.
 */
export const playedEnough = (playedS: number, expectedLeftS: number): boolean => playedS >= Math.min(60, Math.max(0, expectedLeftS) * 0.8);

/**
 * Where a tune target plays. A library item: "/play/<id>?start=<s>&then=<id>&ch=<channel>". An IPTV film: the IPTV player at the
 * same offset ("/iptv/play?vod=<id>&resume=<s>&t=<title>&ch=<channel>"); either way the player moves on by the clock at the end.
 */
export function tunePath(t: { id: string; startS: number; thenId: string | null; title?: string; seriesId?: string }, channelId: string, off = false): string {
  const ch = encodeURIComponent(channelId);
  const o = off ? "&off=1" : "";
  // An IPTV episode: the same address Continue Watching uses (series + episode ids), at the scheduled offset
  if (isIptvEpisodeId(t.id) && t.seriesId) return `/iptv/play?series=${encodeURIComponent(t.seriesId)}&episode=${encodeURIComponent(t.id)}&t=${encodeURIComponent(t.title ?? "")}${t.startS > 0 ? `&resume=${t.startS}` : ""}&ch=${ch}${o}`;
  if (isIptvId(t.id)) return `/iptv/play?vod=${encodeURIComponent(t.id)}&t=${encodeURIComponent(t.title ?? "")}${t.startS > 0 ? `&resume=${t.startS}` : ""}&ch=${ch}${o}`;
  return `/play/${t.id}?start=${t.startS}${t.thenId && !isIptvId(t.thenId) ? `&then=${t.thenId}` : ""}&ch=${ch}${o}`;
}

/** "From start": the item on now, from 0, opened off the clock (`off=1`). Null when nothing is scheduled. */
export function fromStartTarget(slots: Slot[]): Target | null {
  if (!slots.length) return null;
  return targetFor(slots[0].item, 0, slots[1]?.item);
}

/**
 * "Back to live" from an off-schedule item: if `currentId` is still what the clock says is on, seek it to the clock offset;
 * otherwise open whatever the clock says is on now (the tune target).
 */
export function rejoinLive(slots: Slot[], offsetS: number, currentId: string): { kind: "seek"; s: number } | { kind: "go"; target: Target } | null {
  const t = tuneTarget(slots, offsetS);
  if (!t) return null;
  return t.id === currentId ? { kind: "seek", s: t.startS } : { kind: "go", target: t };
}

/** A channel id from a URL, or null if it is not a built-in one. */
export const channelById = (id: string | null | undefined): ChannelDef | null => CHANNELS.find((c) => c.id === id) ?? null;

// ---------- labels ----------

export function itemLabel(it: ChanItem): { title: string; sub: string } {
  if (it.Type === "Episode" || it.SeriesName) {
    const code = it.ParentIndexNumber != null && it.IndexNumber != null ? `S${it.ParentIndexNumber}E${it.IndexNumber}` : "";
    return { title: it.SeriesName || it.Name, sub: [code, it.SeriesName ? it.Name : ""].filter(Boolean).join(" · ") };
  }
  return { title: it.Name, sub: it.ProductionYear ? String(it.ProductionYear) : "" };
}

// ---------- hidden channels (localStorage, per person) ----------

export function parseHidden(raw: string | null): string[] {
  try {
    const v: unknown = JSON.parse(raw ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export const toggleHidden = (hidden: string[], id: string): string[] => (hidden.includes(id) ? hidden.filter((x) => x !== id) : [...hidden, id]);

/** Jellyfin `Years` value for a channel: "1990,1991,…,1999". */
export function yearsParam(def: Pick<ChannelDef, "yearFrom" | "yearTo">): string | undefined {
  if (def.yearFrom == null || def.yearTo == null || def.yearTo < def.yearFrom || def.yearTo - def.yearFrom > 100) return undefined;
  return Array.from({ length: def.yearTo - def.yearFrom + 1 }, (_, i) => def.yearFrom! + i).join(",");
}
