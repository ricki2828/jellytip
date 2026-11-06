// Guided film journeys (pure; tested in web/test/journeys.test.ts). A journey is a short ordered viewing path built from data
// the app already has: franchises (companion collection), directors (the hub's own library credits) and Letterboxd lists.
// No invented copy: titles are plain facts ("Hitchcock in order · 6 films").
import type { CollectionSection } from "./collections.ts";
import { createLookupCache } from "./lookupCache.ts";

export type JourneyKind = "franchise" | "director" | "list";

export const FRANCHISE_MIN = 2; // films in the library for a franchise to be a path
export const DIRECTOR_MIN = 4; // films in the library for a director to be a path
export const DIRECTOR_CAP = 6; // films on a director path
export const LIST_CAP = 8; // films on a list path
export const FRANCHISES_SHOWN = 12;
export const DIRECTORS_SHOWN = 12;
export const LISTS_SHOWN = 10;

/** One film on a path; itemId null = not in the user's library (shown greyed, skipped). */
export interface Part {
  itemId: string | null;
  title: string;
  year: number | null;
}

export interface JourneyDef {
  id: string;
  kind: JourneyKind;
  /** The subject: a franchise name, a director's name, a list's name. */
  name: string;
  parts: Part[];
}

export interface JourneySummary {
  id: string;
  kind: JourneyKind;
  title: string;
  films: number;
}

/** The slice of a Jellyfin item the path needs (fresh each visit: watched state moves). */
export interface StateItem {
  Id: string;
  Name?: string;
  ProductionYear?: number;
  LocationType?: string;
  UserData?: { Played?: boolean; PlaybackPositionTicks?: number };
}

export interface Entry {
  n: number;
  itemId: string | null;
  title: string;
  year: number | null;
  playable: boolean;
  watched: boolean;
  inProgress: boolean;
}

// ---------- ids (route-safe) ----------

const PREFIX: Record<JourneyKind, string> = { franchise: "f", director: "d", list: "l" };
const KEY_RE: Record<JourneyKind, RegExp> = { franchise: /^\d{1,9}$/, director: /^[0-9a-f]{32}$/i, list: /^[a-z0-9][a-z0-9_-]{0,120}$/i };

export const journeyId = (kind: JourneyKind, key: string) => `${PREFIX[kind]}:${key}`;

export function parseJourneyId(id: string): { kind: JourneyKind; key: string } | null {
  const m = /^([fdl]):(.+)$/.exec(id);
  if (!m) return null;
  const kind = (Object.keys(PREFIX) as JourneyKind[]).find((k) => PREFIX[k] === m[1])!;
  return KEY_RE[kind].test(m[2]) ? { kind, key: kind === "director" ? m[2].toLowerCase() : m[2] } : null;
}

// ---------- ordering ----------

/** By release year; films without a year, and ties, keep the order they came in. */
export function orderByRelease<T extends { year: number | null }>(parts: T[]): T[] {
  return parts
    .map((p, i) => ({ p, i }))
    .sort((a, b) => (a.p.year ?? Infinity) - (b.p.year ?? Infinity) || a.i - b.i)
    .map((x) => x.p);
}

const titleFor = (name: string, films: number, kind: JourneyKind) =>
  kind === "list" ? `${name} · ${films} film${films === 1 ? "" : "s"}` : `${name} in order · ${films} film${films === 1 ? "" : "s"}`;
export const journeyTitle = (d: Pick<JourneyDef, "kind" | "name" | "parts">) => titleFor(d.name, d.parts.length, d.kind);

// ---------- definitions ----------

interface FranchisePageLike {
  name: string;
  partsOrder: { title: string; year?: number | null; itemId?: string | null }[];
}

/** Every film of a franchise by release date; parts the library lacks stay in place, flagged missing. */
export function franchiseDef(tmdbId: number | string, page: FranchisePageLike): JourneyDef | null {
  const parts: Part[] = page.partsOrder.map((p) => ({ itemId: p.itemId || null, title: p.title, year: typeof p.year === "number" ? p.year : null }));
  if (parts.filter((p) => p.itemId).length < FRANCHISE_MIN) return null;
  return { id: journeyId("franchise", String(tmdbId)), kind: "franchise", name: page.name, parts: orderByRelease(parts) };
}

/** A director's films from the library's own credits: eligible at DIRECTOR_MIN, chronological, first DIRECTOR_CAP. */
export function directorDef(personId: string, name: string, films: StateItem[]): JourneyDef | null {
  const seen = new Set<string>();
  const movies = films.filter((f) => f.LocationType !== "Virtual" && !seen.has(f.Id) && !!seen.add(f.Id));
  if (!name || movies.length < DIRECTOR_MIN) return null;
  const parts = orderByRelease(movies.map((f) => ({ itemId: f.Id, title: f.Name ?? "", year: f.ProductionYear ?? null }))).slice(0, DIRECTOR_CAP);
  return { id: journeyId("director", personId), kind: "director", name, parts };
}

/** A Letterboxd list in the list's own order, first LIST_CAP. */
export function listDef(list: { slug: string; name: string; itemIds: string[] }): JourneyDef | null {
  const ids = [...new Set(list.itemIds)].slice(0, LIST_CAP);
  if (ids.length < 2) return null;
  return { id: journeyId("list", list.slug), kind: "list", name: list.name, parts: ids.map((itemId) => ({ itemId, title: "", year: null })) };
}

// ---------- the journeys page (no per-journey calls: counts from the hub and manifest) ----------

export interface JourneyGroups {
  franchise: JourneySummary[];
  director: JourneySummary[];
  list: JourneySummary[];
}

export function summaries(hub: CollectionSection[] | null, lists: { slug: string; name: string; itemIds: string[] }[] | null): JourneyGroups {
  const items = (key: string) => hub?.find((s) => s.key === key)?.items ?? [];
  const franchise = items("franchises")
    .filter((c) => /^tmdb:\d{1,9}$/.test(c.id) && c.mediaType !== "tv" && c.libCount >= FRANCHISE_MIN)
    .sort((a, b) => b.libCount - a.libCount)
    .slice(0, FRANCHISES_SHOWN)
    .map((c) => ({ id: journeyId("franchise", c.id.slice(5)), kind: "franchise" as const, title: titleFor(c.title, c.libCount, "franchise"), films: c.libCount }));
  const director = items("directors")
    .filter((c) => c.person && parseJourneyId(journeyId("director", c.person.id)) && c.mediaType !== "tv" && c.libCount >= DIRECTOR_MIN)
    .sort((a, b) => b.libCount - a.libCount)
    .slice(0, DIRECTORS_SHOWN)
    .map((c) => {
      const films = Math.min(c.libCount, DIRECTOR_CAP);
      return { id: journeyId("director", c.person!.id), kind: "director" as const, title: titleFor(c.title, films, "director"), films };
    });
  const list = (lists ?? [])
    .filter((l) => parseJourneyId(journeyId("list", l.slug)) && new Set(l.itemIds).size >= 2)
    .slice(0, LISTS_SHOWN)
    .map((l) => {
      const films = Math.min(new Set(l.itemIds).size, LIST_CAP);
      return { id: journeyId("list", l.slug), kind: "list" as const, title: titleFor(l.name, films, "list"), films };
    });
  return { franchise, director, list };
}

// ---------- progress ----------

/** Merge fresh item state into a path. Items the server no longer returns (or that are virtual placeholders) are not playable. */
export function buildEntries(def: JourneyDef, items: StateItem[]): Entry[] {
  const by = new Map(items.map((i) => [i.Id.replace(/-/g, "").toLowerCase(), i]));
  return def.parts.map((p, i) => {
    const it = p.itemId ? by.get(p.itemId.replace(/-/g, "").toLowerCase()) : undefined;
    const playable = !!it && it.LocationType !== "Virtual";
    const watched = playable && !!it!.UserData?.Played;
    return {
      n: i + 1,
      itemId: playable ? it!.Id : null,
      title: it?.Name || p.title,
      year: it?.ProductionYear ?? p.year,
      playable,
      watched,
      inProgress: playable && !watched && (it!.UserData?.PlaybackPositionTicks ?? 0) > 0,
    };
  });
}

/** The first unwatched film you can play; missing ones are skipped. Null when everything playable is watched. */
export function nextUp(entries: Entry[]): { entry: Entry; resume: boolean } | null {
  const e = entries.find((x) => x.playable && !x.watched);
  return e ? { entry: e, resume: e.inProgress } : null;
}

export function progress(entries: Entry[]): { watched: number; playable: number; total: number } {
  return { watched: entries.filter((e) => e.watched).length, playable: entries.filter((e) => e.playable).length, total: entries.length };
}

// ---------- session-scoped cache of definitions ----------

/** Definitions cached per user (a hit lives 10 min, a failure 2); clear() on sign-in/out. Watched state is never cached here. */
export function createJourneyCache(now?: () => number) {
  return createLookupCache<JourneyDef>(now);
}
