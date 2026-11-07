// Personal scheduled channels (N7). Pure; tested in web/test/channels.test.ts.
// A channel is a seeded-shuffle lineup of the signed-in person's own library items, laid back to back from the start of
// the current week (UTC, Monday 00:00). "What's on now" is therefore a function of the wall clock alone: the same item
// at the same offset whenever, and on whichever device, someone tunes in. The lineup loops when the week outlasts it, and
// starts again from a fresh shuffle at the week boundary.

export const TICKS_PER_SECOND = 10_000_000;
export const WEEK_MS = 7 * 24 * 3600 * 1000;
/** 1970-01-05 00:00 UTC, the first Monday after the Unix epoch. */
const MONDAY_EPOCH_MS = 4 * 24 * 3600 * 1000;
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
}

export const CHANNELS: ChannelDef[] = [
  { id: "90s-action", name: "90s Action", blurb: "Action films from 1990 to 1999", type: "Movie", genres: ["Action"], yearFrom: 1990, yearTo: 1999 },
  { id: "sunday-sitcoms", name: "Sunday Sitcoms", blurb: "Half-hour comedy episodes", type: "Episode", genres: ["Comedy"], maxMinutes: 40 },
  { id: "comedy", name: "Comedy", blurb: "Comedy films", type: "Movie", genres: ["Comedy"] },
  { id: "sci-fi-nights", name: "Sci-Fi Nights", blurb: "Science fiction films", type: "Movie", genres: ["Science Fiction"] },
  { id: "documentaries", name: "Documentaries", blurb: "Documentary films", type: "Movie", genres: ["Documentary"] },
  { id: "family-night", name: "Family Night", blurb: "Family and animated films", type: "Movie", genres: ["Family", "Animation"] },
];

/** The slice of an Item a channel keeps (small enough for sessionStorage). */
export interface ChanItem {
  Id: string;
  Name: string;
  Type?: string;
  SeriesName?: string;
  ParentIndexNumber?: number;
  IndexNumber?: number;
  ProductionYear?: number;
  RunTimeTicks?: number;
}

export interface Slot {
  item: ChanItem;
  /** Absolute times, ms since the Unix epoch. */
  startMs: number;
  endMs: number;
}

// ---------- week + seed ----------

/** Start of the UTC week (Monday 00:00) holding `nowMs`. */
export const weekStart = (nowMs: number): number => MONDAY_EPOCH_MS + Math.floor((nowMs - MONDAY_EPOCH_MS) / WEEK_MS) * WEEK_MS;

/** Whole weeks since the first Monday: the "week number" in the seed. */
export const weekNumber = (nowMs: number): number => Math.floor((nowMs - MONDAY_EPOCH_MS) / WEEK_MS);

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

export const seedFor = (channelId: string, nowMs: number): number => hashString(`${channelId}|w${weekNumber(nowMs)}`);

// ---------- lineup ----------

const seconds = (it: ChanItem): number => (typeof it.RunTimeTicks === "number" && isFinite(it.RunTimeTicks) ? Math.floor(it.RunTimeTicks / TICKS_PER_SECOND) : 0);

/** Items with a usable runtime, once each, whatever order the server returned them in. */
export function playable(items: ChanItem[], def?: Pick<ChannelDef, "maxMinutes">): ChanItem[] {
  const seen = new Set<string>();
  const out: ChanItem[] = [];
  for (const it of items) {
    if (!it?.Id || seen.has(it.Id)) continue;
    const s = seconds(it);
    if (s < 60) continue; // no runtime (or a stub): can't be placed on a timeline
    if (def?.maxMinutes && s > def.maxMinutes * 60) continue;
    seen.add(it.Id);
    out.push(it);
  }
  return out;
}

/** A channel exists when its query yields enough playable items. */
export const channelViable = (items: ChanItem[], def?: Pick<ChannelDef, "maxMinutes">): boolean => playable(items, def).length >= MIN_ITEMS;

/** Deterministic lineup: items sorted by id (so server order can't matter), then a seeded Fisher-Yates shuffle. */
export function lineup(items: ChanItem[], seed: number, def?: Pick<ChannelDef, "maxMinutes">): ChanItem[] {
  const a = playable(items, def).sort((x, y) => (x.Id < y.Id ? -1 : x.Id > y.Id ? 1 : 0));
  const r = rng(seed);
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export const cycleSeconds = (list: ChanItem[]): number => list.reduce((n, it) => n + seconds(it), 0);

/**
 * What is on at `nowMs` and what follows: `count` slots starting with the current one (so count 4 = now + next 3).
 * The week's schedule starts at weekStart(now) with list[0] and loops. Slot times are absolute. Empty list → [].
 * `offsetS` is how far into the current item the clock is.
 */
export function schedule(list: ChanItem[], nowMs: number, count = 4): { slots: Slot[]; offsetS: number; progress: number } {
  const total = cycleSeconds(list);
  if (!list.length || total <= 0) return { slots: [], offsetS: 0, progress: 0 };
  const elapsed = Math.max(0, Math.floor((nowMs - weekStart(nowMs)) / 1000));
  let pos = elapsed % total;
  let i = 0;
  while (pos >= seconds(list[i])) {
    pos -= seconds(list[i]);
    i++;
  }
  const startMs = nowMs - ((pos * 1000) + ((nowMs - weekStart(nowMs)) % 1000));
  const slots: Slot[] = [];
  let t = startMs;
  for (let k = 0; k < count; k++) {
    const it = list[(i + k) % list.length];
    const end = t + seconds(it) * 1000;
    slots.push({ item: it, startMs: t, endMs: end });
    t = end;
  }
  return { slots, offsetS: pos, progress: pos / seconds(list[i]) };
}

/** Where "Tune in" goes: the current item at its offset, or the next item from 0 when the current one is nearly over. */
export function tuneTarget(slots: Slot[], offsetS: number): { id: string; startS: number; thenId: string | null } | null {
  if (!slots.length) return null;
  const cur = slots[0];
  const left = (cur.endMs - cur.startMs) / 1000 - offsetS;
  if (left < TUNE_MIN_LEFT_S && slots.length > 1) return { id: slots[1].item.Id, startS: 0, thenId: slots[2]?.item.Id ?? null };
  return { id: cur.item.Id, startS: Math.floor(offsetS), thenId: slots[1]?.item.Id ?? null };
}

/** "/play/<id>?start=<s>&then=<id>" for a tune target. */
export const tunePath = (t: { id: string; startS: number; thenId: string | null }): string => `/play/${t.id}?start=${t.startS}${t.thenId ? `&then=${t.thenId}` : ""}`;

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
