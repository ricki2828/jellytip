// Library management centre (admins): pure query building, classification, confirm gate and the task poller.
// Tested in web/test/manage.test.ts. No imports from the API layer so it runs under node --test.
import { toSearchLanguage } from "./remoteSubtitles.ts";

export const PAGE_SIZE = 50;
export const RECENT_SIZE = 12;
export const TASK_POLL_MS = 10_000;
export const CONFIRM_WINDOW_MS = 8_000;

export type ManageFilter = "recent" | "no-overview" | "no-poster" | "no-subs";
export const FILTERS: [ManageFilter, string][] = [
  ["recent", "Recently added"],
  ["no-overview", "No overview"],
  ["no-poster", "No poster"],
  ["no-subs", "No subtitles"],
];

const ID_RE = /^(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;
export const validId = (id: string): boolean => ID_RE.test(id);

export interface Library {
  id: string;
  name: string;
  kind: "movies" | "tvshows" | "other";
}

/** /Library/VirtualFolders rows → libraries; rows without a valid id are dropped. */
export function mapLibraries(raw: unknown): Library[] {
  if (!Array.isArray(raw)) return [];
  const out: Library[] = [];
  for (const r of raw) {
    const o = r as { ItemId?: unknown; Name?: unknown; CollectionType?: unknown };
    if (typeof o?.ItemId !== "string" || !validId(o.ItemId) || typeof o.Name !== "string") continue;
    out.push({ id: o.ItemId.toLowerCase(), name: o.Name, kind: o.CollectionType === "movies" ? "movies" : o.CollectionType === "tvshows" ? "tvshows" : "other" });
  }
  return out;
}

/** The item type each library kind is counted / listed by. Episodes are left out of series libraries to keep the list per-show. */
export const typesFor = (k: Library["kind"]): string => (k === "movies" ? "Movie" : k === "tvshows" ? "Series" : "Movie,Series,Episode,Audio,MusicAlbum");

type Params = Record<string, string | number | boolean>;

/** Query for one page of a library under a filter. Server-side where Jellyfin can (HasOverview, DateCreated sort); the rest is classified client-side. */
export function buildItemsQuery(lib: Library, filter: ManageFilter, startIndex = 0, limit = PAGE_SIZE): Params {
  if (!validId(lib.id)) throw new Error("bad library id");
  const base: Params = {
    ParentId: lib.id,
    Recursive: true,
    IncludeItemTypes: typesFor(lib.kind),
    Fields: filter === "no-subs" ? "Overview,MediaStreams,DateCreated" : "Overview,DateCreated",
    EnableUserData: false,
    StartIndex: Math.max(0, Math.floor(startIndex)),
    Limit: Math.min(200, Math.max(1, Math.floor(limit))),
  };
  if (filter === "recent") return { ...base, SortBy: "DateCreated", SortOrder: "Descending", Limit: RECENT_SIZE, StartIndex: 0 };
  if (filter === "no-overview") return { ...base, HasOverview: false, SortBy: "SortName" };
  return { ...base, SortBy: "SortName" };
}

/** Count-only query (Limit=0 still returns TotalRecordCount). */
export function buildCountQuery(lib: Library): Params {
  return { ParentId: lib.id, Recursive: true, IncludeItemTypes: typesFor(lib.kind), Limit: 0, EnableUserData: false };
}

export interface ManageItem {
  Id: string;
  Name: string;
  Type: string;
  Overview?: string;
  ProductionYear?: number;
  ImageTags?: Record<string, string>;
  MediaStreams?: { Type?: string; Language?: string }[];
}

export const missingOverview = (i: ManageItem): boolean => !(i.Overview ?? "").trim();
export const missingPoster = (i: ManageItem): boolean => !i.ImageTags?.Primary;

/**
 * No subtitle track in `lang` (3-letter code; stream tags in 2- or 3-letter form both match).
 * Returns null when the streams were not loaded: unknown is not "missing". Series carry no streams of their own, so also null.
 */
export function missingSubtitle(i: ManageItem, lang: string): boolean | null {
  if (i.Type === "Series" || i.Type === "Season") return null;
  if (!i.MediaStreams) return null;
  const want = toSearchLanguage(lang) ?? lang;
  const subs = i.MediaStreams.filter((s) => s.Type === "Subtitle");
  if (subs.some((s) => (toSearchLanguage(s.Language) ?? "") === want)) return false;
  if (subs.some((s) => !(s.Language ?? "").trim())) return null; // an untagged track might be it: unknown, not missing
  return true;
}

/** Has subtitle tracks but at least one carries no language tag. */
export const hasUntaggedSubtitles = (i: ManageItem): boolean => !!i.MediaStreams?.some((s) => s.Type === "Subtitle" && !(s.Language ?? "").trim());

/** The filters a library kind supports ("No subtitles" is per file, so not for series libraries). */
export const filtersFor = (k: Library["kind"]): [ManageFilter, string][] => (k === "tvshows" ? FILTERS.filter(([f]) => f !== "no-subs") : FILTERS);

export function classify(i: ManageItem, lang: string): { overview: boolean; poster: boolean; subs: boolean | null; untagged: boolean } {
  return { overview: missingOverview(i), poster: missingPoster(i), subs: missingSubtitle(i, lang), untagged: missingSubtitle(i, lang) === null && hasUntaggedSubtitles(i) };
}

/** Does an item belong in the list for this filter? "recent" shows everything. */
export function matchesFilter(i: ManageItem, f: ManageFilter, lang: string): boolean {
  if (f === "recent") return true;
  if (f === "no-overview") return missingOverview(i);
  if (f === "no-poster") return missingPoster(i);
  return missingSubtitle(i, lang) === true;
}

// ---------- requests ----------

export interface RefreshOpts {
  full?: boolean;
  replaceImages?: boolean;
}
/** POST /Items/{id}/Refresh query: defaults never replace existing images or metadata. */
export function refreshPath(id: string, o: RefreshOpts = {}): string {
  if (!validId(id)) throw new Error("bad item id");
  const p = new URLSearchParams({
    metadataRefreshMode: o.full ? "FullRefresh" : "Default",
    imageRefreshMode: o.full ? "FullRefresh" : "Default",
    replaceAllMetadata: "false",
    replaceAllImages: o.replaceImages ? "true" : "false",
  });
  return `/Items/${id}/Refresh?${p}`;
}
export const SCAN_PATH = "/Library/Refresh";
export const TASKS_PATH = "/ScheduledTasks?isHidden=false";

// ---------- scheduled tasks ----------

export interface Task {
  id: string;
  name: string;
  state: string;
  percent: number | null;
  category: string;
}
export function mapTasks(raw: unknown): Task[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map((r) => r as Record<string, unknown>)
    .filter((o) => typeof o?.Id === "string" && typeof o?.Name === "string")
    .map((o) => ({
      id: o.Id as string,
      name: o.Name as string,
      state: typeof o.State === "string" ? o.State : "Idle",
      percent: typeof o.CurrentProgressPercentage === "number" ? Math.max(0, Math.min(100, Math.round(o.CurrentProgressPercentage))) : null,
      category: typeof o.Category === "string" ? o.Category : "",
    }));
}
/** Running first (then cancelling), idle tasks last; the screen shows only the running ones plus a count. */
export const runningTasks = (t: Task[]): Task[] => t.filter((x) => x.state === "Running" || x.state === "Cancelling");

// ---------- errors ----------

export function errorText(e: unknown, what: string): string {
  const s = (e as { status?: number })?.status;
  if (s === 401 || s === 403) return "Admin only: the server refused this account.";
  if (s === 404) return `${what}: not found on the server.`;
  if (typeof s === "number") return `${what} failed (server error ${s}).`;
  return `${what} failed (no connection?).`;
}

// ---------- confirmation gate ----------

/** Two-step tap gate: arm, then confirm inside the window. Anything else (late, never armed, another target) is refused. */
export function createTapGate(windowMs = CONFIRM_WINDOW_MS) {
  let armed: { key: string; at: number } | null = null;
  return {
    arm(key: string, now: number) {
      armed = { key, at: now };
    },
    isArmed(key: string, now: number): boolean {
      return !!armed && armed.key === key && now - armed.at <= windowMs;
    },
    /** true exactly once per arm */
    confirm(key: string, now: number): boolean {
      const ok = !!armed && armed.key === key && now - armed.at <= windowMs;
      armed = null;
      return ok;
    },
    reset() {
      armed = null;
    },
  };
}

// ---------- poller ----------

export interface IntervalEnv {
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
  hidden(): boolean;
  onVisible(cb: () => void): () => void;
}
export type PollResult<T> = { kind: "ok"; value: T } | { kind: "denied" } | { kind: "error" };

/**
 * Poll every `ms` while the page is visible: fetch, report, wait, repeat. Never overlaps itself; pauses while hidden and
 * refreshes at once on return; stops for good on "denied"; cancel() turns any late answer into a no-op.
 */
export function startIntervalPoll<T>(o: { fetch(): Promise<PollResult<T>>; onResult(r: PollResult<T>): void; env: IntervalEnv; ms?: number }): { cancel(): void } {
  const ms = o.ms ?? TASK_POLL_MS;
  let dead = false;
  let inFlight = false;
  let timer: unknown = null;
  const clear = () => {
    if (timer != null) o.env.clearTimer(timer);
    timer = null;
  };
  const run = async () => {
    timer = null;
    if (dead || inFlight) return;
    if (o.env.hidden()) return; // the visibility callback restarts it
    inFlight = true;
    let r: PollResult<T>;
    try {
      r = await o.fetch();
    } catch {
      r = { kind: "error" };
    }
    inFlight = false;
    if (dead) return;
    o.onResult(r);
    if (r.kind === "denied") return;
    clear();
    timer = o.env.setTimer(() => void run(), ms);
  };
  const off = o.env.onVisible(() => {
    if (dead || inFlight) return;
    clear();
    void run();
  });
  void run();
  return {
    cancel() {
      dead = true;
      clear();
      off();
    },
  };
}

// ---------- scan arm (gate + its expiry timer in one place, so label and gate cannot disagree) ----------

export interface ArmEnv {
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
  now(): number;
}
export function createArm(env: ArmEnv, onChange: (armed: boolean) => void, windowMs = CONFIRM_WINDOW_MS) {
  const gate = createTapGate(windowMs);
  let timer: unknown = null;
  const clear = () => {
    if (timer != null) env.clearTimer(timer);
    timer = null;
  };
  return {
    /** First press arms (returns false); a press inside the window confirms (returns true, once). */
    press(key: string): boolean {
      const now = env.now();
      if (gate.isArmed(key, now)) {
        const ok = gate.confirm(key, now);
        clear();
        onChange(false);
        return ok;
      }
      clear();
      gate.arm(key, now);
      onChange(true);
      timer = env.setTimer(() => {
        timer = null;
        gate.reset();
        onChange(false);
      }, windowMs);
      return false;
    },
    dispose() {
      clear();
      gate.reset();
    },
  };
}

// ---------- paging ----------

export const MIN_HITS = 10;
export const MAX_HOPS = 6;
export function mergeById<T extends { Id: string }>(have: readonly T[], add: readonly T[]): T[] {
  const seen = new Set(have.map((i) => i.Id));
  const out = have.slice();
  for (const i of add) if (!seen.has(i.Id)) (seen.add(i.Id), out.push(i));
  return out;
}

/**
 * Read pages from `start` until MIN_HITS matches or the end (at most MAX_HOPS pages). `current()` false at any point
 * (library / filter changed) → null, nothing is applied. `next` = where "Load more" continues, null = the end.
 */
export async function loadPages<T extends ManageItem>(o: {
  fetchPage(startIndex: number): Promise<{ Items?: T[]; TotalRecordCount?: number }>;
  filter: ManageFilter;
  lang: string;
  start: number;
  current(): boolean;
}): Promise<{ items: T[]; next: number | null } | null> {
  let at = o.start;
  let got: T[] = [];
  let next: number | null = null;
  for (let hops = 0; hops < MAX_HOPS; hops++) {
    const r = await o.fetchPage(at);
    if (!o.current()) return null;
    const page = r.Items ?? [];
    at += page.length;
    next = o.filter === "recent" || page.length === 0 || at >= (r.TotalRecordCount ?? 0) ? null : at;
    got = mergeById(got, page.filter((i) => matchesFilter(i, o.filter, o.lang)));
    if (got.length >= MIN_HITS || next == null) break;
  }
  return { items: got, next };
}
