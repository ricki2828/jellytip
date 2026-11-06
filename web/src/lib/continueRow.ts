// One coherent Continue Watching row (pure; tested in web/test/continueRow.test.ts): Jellyfin Resume + Next Up with IPTV
// progress, one tile per series, labelled "S2 E5 · 23 min left" / "Next: S2 E6", plus the optimistic hide/rollback helper
// and the per-user "removed" list. No Jellyfin/companion runtime imports here, so node can load it directly.
import type { ContinueEntry } from "./continue.ts";
import type { Item } from "../api/jellyfin.ts";

const TICKS = 10_000_000;
/** At or past this share watched, or with under this many seconds left, an in-progress title is finished. */
export const DONE_PCT = 95;
export const DONE_LEFT_S = 120;

export interface RowEntry {
  /** "lib:<undashed id>" or "iptv:<kind>:<id>": the tile's identity (stable across refreshes). */
  key: string;
  source: "lib" | "iptv";
  /** One tile per series: "lib:<series id>" / "iptv:<series id>"; films use their own key. */
  group: string;
  /** Up-next episode (nothing watched yet) rather than something in progress. */
  next: boolean;
  /** Last played, epoch seconds (0 = unknown). */
  at: number;
  /** Seconds in, and total (0 = unknown). */
  pos: number;
  dur: number;
  /** Watched share, 0-100 (0 for a next-up tile). */
  pct: number;
  label: string;
  lib?: Item;
  iptv?: ContinueEntry;
}

const undash = (s: string) => s.replace(/-/g, "");

/** "23 min left", "1h 5m left"; under a minute counts as 1 min. */
export function leftText(seconds: number): string {
  const m = Math.max(1, Math.ceil(seconds / 60));
  return m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""} left` : `${m} min left`;
}

const se = (s: number | null | undefined, e: number | null | undefined) => (s != null && e != null ? `S${s} E${e}` : e != null ? `E${e}` : null);

/** The tile's line: "S2 E5 · 23 min left", "23 min left" (film), "Next: S2 E6", or "In progress" when the length is unknown. */
export function rowLabel(o: { next: boolean; episode: string | null; pos: number; dur: number }): string {
  if (o.next) return o.episode ? `Next: ${o.episode}` : "Up next";
  const left = o.dur > 0 ? leftText(Math.max(0, o.dur - o.pos)) : "In progress";
  return [o.episode, left].filter(Boolean).join(" · ");
}

/** True when an in-progress title is finished: from 95 % watched, or under two minutes left (needs a known length). */
export function nearlyDone(pos: number, dur: number): boolean {
  if (!(dur > 0)) return false;
  return (pos / dur) * 100 >= DONE_PCT || dur - pos < DONE_LEFT_S;
}

const stampS = (i: Item): number => {
  const t = i.UserData?.LastPlayedDate ? Date.parse(i.UserData.LastPlayedDate) / 1000 : NaN;
  return isFinite(t) ? t : 0;
};

function fromLib(i: Item, next: boolean): RowEntry {
  const id = undash(i.Id);
  const dur = (i.RunTimeTicks ?? 0) / TICKS;
  const ud = i.UserData;
  const pos = next ? 0 : (ud?.PlaybackPositionTicks ?? 0) / TICKS || (dur && ud?.PlayedPercentage ? (dur * ud.PlayedPercentage) / 100 : 0);
  const episode = i.Type === "Episode" ? se(i.ParentIndexNumber, i.IndexNumber) : null;
  return {
    key: `lib:${id}`,
    source: "lib",
    group: i.Type === "Episode" && (i.SeriesId || i.SeriesName) ? `lib:${undash(i.SeriesId ?? i.SeriesName!)}` : `lib:${id}`,
    next,
    at: stampS(i),
    pos,
    dur,
    pct: next || !dur ? (next ? 0 : ud?.PlayedPercentage ?? 0) : Math.min(100, (pos / dur) * 100),
    label: rowLabel({ next, episode, pos, dur }),
    lib: i,
  };
}

function fromIptv(e: ContinueEntry, next: boolean): RowEntry {
  const episode = e.kind === "episode" ? se(e.season, e.episode) : null;
  return {
    key: `iptv:${e.kind}:${e.id}`,
    source: "iptv",
    group: e.kind === "episode" ? `iptv:${e.seriesId ?? e.id.replace(/:e:.*/, ":s:")}` : `iptv:${e.kind}:${e.id}`,
    next,
    at: e.at,
    pos: next ? 0 : e.pos,
    dur: e.dur,
    pct: next || !(e.dur > 0) ? 0 : Math.min(100, (e.pos / e.dur) * 100),
    label: rowLabel({ next, episode, pos: next ? 0 : e.pos, dur: e.dur }),
    iptv: e,
  };
}

export interface BuildOpts {
  /** Keys the person removed (see the dismiss store); an entry played since stays out of it. */
  dismissed?: Record<string, number>;
  /** Keys hidden right now by an optimistic action in flight. */
  hidden?: ReadonlySet<string>;
}

/** Whether a removal (ms timestamp) still hides this entry: played again after the removal brings it back. */
export const isDismissed = (e: RowEntry, dismissed: Record<string, number> | undefined) => {
  const at = dismissed?.[e.key];
  return at != null && e.at * 1000 <= at;
};

/**
 * The row: in-progress titles (library and IPTV) newest played first, then up-next episodes (the server's order; they carry
 * no play time). One tile per series, the in-progress episode beating its next-up one; the most recently played in-progress
 * episode wins between two. Finished titles (>= 95 % or < 2 min left) and removed ones are left out. Library and IPTV copies
 * of one show can't be matched (different ids), so they stay separate tiles.
 */
export function buildContinue(resume: Item[], next: Item[], iptvResume: ContinueEntry[], iptvNext: ContinueEntry[], opts: BuildOpts = {}): RowEntry[] {
  const keep = (e: RowEntry) => !opts.hidden?.has(e.key) && !isDismissed(e, opts.dismissed);
  // Jellyfin returns resume newest first: an undated one is no newer than the one before it
  let prevAt = Infinity;
  const progress = [
    ...resume.map((i) => {
      const e = fromLib(i, false);
      if (!e.at) e.at = prevAt === Infinity ? Number.MAX_SAFE_INTEGER / 1000 : prevAt - 1;
      prevAt = e.at;
      return e;
    }),
    ...iptvResume.map((c) => fromIptv(c, false)),
  ].filter((e) => !nearlyDone(e.pos, e.dur));
  const best = new Map<string, RowEntry>();
  for (const e of progress) {
    const cur = best.get(e.group);
    if (!cur || e.at > cur.at) best.set(e.group, e);
  }
  // A series whose in-progress episode was removed must not be replaced by an older one of the same series
  const live = new Set(progress.map((e) => e.group));
  const tier1 = [...best.values()].filter(keep).sort((a, b) => b.at - a.at);
  const tier2: RowEntry[] = [];
  const seen = new Set<string>();
  for (const e of [...next.map((i) => fromLib(i, true)), ...iptvNext.map((c) => fromIptv(c, true))]) {
    if (live.has(e.group) || seen.has(e.group)) continue;
    seen.add(e.group);
    if (keep(e)) tier2.push(e);
  }
  return [...tier1, ...tier2];
}

// ---------- actions ----------

export type ActionId = "remove" | "watched" | "restart";

export interface ActionSet {
  remove: boolean;
  watched: boolean;
  restart: boolean;
}

/**
 * What each tile may offer. Library: all three. IPTV: the companion has only a progress write (no remove route, so no
 * Remove); "Mark as watched" needs a known length (progress is written as finished) and an in-progress tile.
 */
export function actionsFor(e: RowEntry): ActionSet {
  if (e.source === "lib") return { remove: true, watched: true, restart: !e.next };
  return { remove: false, watched: !e.next && e.dur > 0, restart: !e.next };
}

/**
 * Hide a tile at once, run the call, and bring the tile back with a message when it fails. Hiding is a set of keys (not a
 * snapshot of the row), so two actions in flight can't undo each other. Resolves true when the call succeeded.
 */
export async function optimisticHide(
  io: { hide: (key: string) => void; unhide: (key: string) => void; notify: (msg: string) => void },
  key: string,
  commit: () => Promise<unknown>,
  failMsg: string,
  okMsg?: string,
): Promise<boolean> {
  io.hide(key);
  try {
    await commit();
  } catch {
    io.unhide(key);
    io.notify(failMsg);
    return false;
  }
  if (okMsg) io.notify(okMsg);
  return true;
}

// ---------- removed list (per user) ----------

const DISMISS_PREFIX = "jellytip.continue.dismissed.";
const MAX_DISMISSED = 200;
type Store = Pick<Storage, "getItem" | "setItem">;

/** Removals are kept per signed-in user (and server), so nothing carries over when someone else signs in. */
export const dismissKey = (userId: string) => `${DISMISS_PREFIX}${userId}`;

export function readDismissed(store: Store, userId: string): Record<string, number> {
  if (!userId) return {};
  try {
    const v = JSON.parse(store.getItem(dismissKey(userId)) ?? "{}") as unknown;
    if (!v || typeof v !== "object" || Array.isArray(v)) return {};
    return Object.fromEntries(Object.entries(v).filter(([, t]) => typeof t === "number" && isFinite(t)));
  } catch {
    return {};
  }
}

export function writeDismissed(store: Store, userId: string, key: string, on: boolean, now = Date.now()): Record<string, number> {
  if (!userId) return {};
  const d = readDismissed(store, userId);
  if (on) d[key] = now;
  else delete d[key];
  const keys = Object.keys(d).sort((a, b) => d[b] - d[a]);
  for (const k of keys.slice(MAX_DISMISSED)) delete d[k];
  try {
    store.setItem(dismissKey(userId), JSON.stringify(d));
  } catch {
    // storage full or blocked: the removal still holds for this visit
  }
  return d;
}
