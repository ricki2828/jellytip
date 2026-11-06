// Home row plan (pure; tested in web/test/homePlan.test.ts): which rows show on the first screen, which sit behind
// "More rows", which are dropped, and when a late change must wait because it would move the row in focus.

import { placeSpecials, type Placeable } from "./specialsPlacement.ts";

export const MIN_ROW_ITEMS = 4;
export const WATCHED_CUTOFF = 0.8;
export const DEDUPE_AT = 3; // an item already in this many earlier rows is skipped
export const MAX_DISCOVERY = 4;

export interface Cand {
  key: string;
  /** Undashed library ids, in display order. null = not filterable (IPTV-only, ranked, person, collection rows, most specials). */
  ids: string[] | null;
  /** Tiles the row will show (library + IPTV + missing); used for the minimum. */
  count: number;
  /** The one personal pick row (Tonight's pick), shown right after Continue Watching. */
  pick?: boolean;
}

export interface UserHome {
  pinned: string[];
  hidden: string[];
}

export interface HomePlan {
  /** First screen, in order. */
  visible: string[];
  /** Behind "More rows", in order. */
  more: string[];
  /** Dropped by the quality filter or by the person's Hide. */
  dropped: string[];
  /** Rows whose items were thinned by the dedupe: key -> ids to show. */
  ids: Record<string, string[]>;
}

export const undash = (id: string) => id.replace(/-/g, "").toLowerCase();

export function watchedRatio(ids: string[], watched: ReadonlySet<string>): number {
  return ids.length ? ids.filter((i) => watched.has(i)).length / ids.length : 0;
}

/** Remove ids that already appear in `DEDUPE_AT`+ earlier rows, but only if at least `min` remain; else the row keeps all. */
export function dedupeIds(ids: string[], seenCount: ReadonlyMap<string, number>, min = MIN_ROW_ITEMS): string[] {
  const kept = ids.filter((i) => (seenCount.get(i) ?? 0) < DEDUPE_AT);
  return kept.length >= min ? kept : ids;
}

/**
 * Rules, in order:
 *  1. Hidden by the person: dropped (a row both pinned and hidden stays hidden: Hide clears the pin in Edit Home).
 *  2. Quality (not for pinned rows): under 4 tiles, or 80%+ of known items watched: dropped. `watched` null = not known yet.
 *  3. Order: the pick, pinned rows (their order), then the rest in the given order.
 *  4. Dedupe, walking that order (not for pinned rows or unfilterable rows).
 *  5. First screen = pick + pinned + up to 4 others; the rest go to More rows.
 */
export function planHome(cands: Cand[], user: UserHome, watched: ReadonlySet<string> | null, maxDiscovery = MAX_DISCOVERY): HomePlan {
  const pinned = new Set(user.pinned);
  const hidden = new Set(user.hidden);
  const dropped: string[] = [];
  const kept: Cand[] = [];
  for (const c of cands) {
    if (hidden.has(c.key)) dropped.push(c.key);
    else if (!pinned.has(c.key) && (c.count < MIN_ROW_ITEMS || (watched && c.ids && watchedRatio(c.ids, watched) >= WATCHED_CUTOFF))) dropped.push(c.key);
    else kept.push(c);
  }
  const pick = kept.find((c) => c.pick && !pinned.has(c.key));
  const pins = kept.filter((c) => pinned.has(c.key)).sort((a, b) => user.pinned.indexOf(a.key) - user.pinned.indexOf(b.key));
  const rest = kept.filter((c) => c !== pick && !pinned.has(c.key));
  const ordered = [...(pick ? [pick] : []), ...pins, ...rest];

  const seen = new Map<string, number>();
  const ids: Record<string, string[]> = {};
  for (const c of ordered) {
    if (!c.ids) continue;
    const shown = pinned.has(c.key) ? c.ids : dedupeIds(c.ids, seen);
    if (shown.length !== c.ids.length) ids[c.key] = shown;
    for (const i of new Set(shown)) seen.set(i, (seen.get(i) ?? 0) + 1);
  }

  const visible: string[] = [];
  const more: string[] = [];
  let disc = 0;
  for (const c of ordered) {
    if (c === pick || pinned.has(c.key)) visible.push(c.key);
    else if (disc < maxDiscovery) {
      visible.push(c.key);
      disc++;
    } else more.push(c.key);
  }
  return { visible, more, dropped, ids };
}

/**
 * A late plan must wait when someone is on a row and the change would move it: anything before the focused row
 * differs, or the focused row is going away. No focus (or focus outside the planned rows) = apply at once.
 */
export function shouldDefer(current: string[], next: string[], focusedKey: string | null): boolean {
  if (!focusedKey) return false;
  const at = current.indexOf(focusedKey);
  if (at < 0) return false;
  const to = next.indexOf(focusedKey);
  if (to !== at) return true;
  return current.slice(0, at).some((k, i) => k !== next[i]);
}

/** Per-person Home choices when the server has no personal prefs: localStorage. */
const LOCAL_KEY = "jellytip.homeLocal.";
export function readLocalHome(store: Pick<Storage, "getItem">, user: string): UserHome {
  try {
    const o = JSON.parse(store.getItem(LOCAL_KEY + user) ?? "{}") as Partial<UserHome>;
    const s = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);
    return { pinned: s(o.pinned), hidden: s(o.hidden) };
  } catch {
    return { pinned: [], hidden: [] };
  }
}
export function writeLocalHome(store: Pick<Storage, "setItem">, user: string, h: UserHome): boolean {
  try {
    store.setItem(LOCAL_KEY + user, JSON.stringify(h));
    return true;
  } catch {
    return false;
  }
}

/** One candidate list: rows in server order with each special before the row it asked for (keys `special-<id>`). */
export function interleaveSpecials(cands: Cand[], specials: Placeable[]): Cand[] {
  const rows = cands.filter((c) => !c.key.startsWith("special-"));
  const byKey = new Map(cands.map((c) => [c.key, c]));
  const slots = placeSpecials(rows.length, specials);
  const out: Cand[] = [];
  slots.forEach((list, i) => {
    for (const s of list) {
      const c = byKey.get(`special-${s.id}`);
      if (c) out.push(c);
    }
    if (i < rows.length) out.push(rows[i]);
  });
  return out;
}
