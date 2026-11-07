// Taste signals (pure; tested in web/test/signals.test.ts). Events queue up on the device and go to the companion in
// batches (contract: ~/ai/state/jellytip-taste-contract.md). Ids and durations only: nothing else about the person.

export type SignalType = "trailer" | "detail" | "play" | "finish" | "remind" | "record" | "thumbUp" | "thumbDown" | "mylist" | "skip";
export interface Signal {
  type: SignalType;
  /** ms since the Unix epoch */
  at: number;
  kind: "movie" | "series";
  tmdbId?: number;
  itemId?: string;
  iptvId?: string;
  /** dwell or watch duration */
  ms?: number;
}

export const BATCH_MAX = 50;
/** Most events kept while the server can't be reached; the oldest go first. */
export const QUEUE_CAP = 200;
export const TRAILER_MIN_S = 5;
export const DETAIL_MIN_MS = 8_000;
export const DETAIL_MAX_MS = 10 * 60_000;
export const SKIP_UNDER_S = 120;
/** Less than this is a mis-click or a channel flick, not a verdict. */
export const SKIP_MIN_PLAYED_S = 10;
/** A film must be at least this long for leaving early to read as "not for me". */
export const SKIP_MIN_RUNTIME_S = 40 * 60;
export const FINISH_FRACTION = 0.9;
/** Shortest watch worth a 'play' event. */
export const PLAY_MIN_S = 60;
export const CHANNEL_PLAY_MIN_S = 30;

const idOf = (s: Signal) => s.tmdbId != null ? `t${s.tmdbId}` : s.itemId ? `i${s.itemId}` : s.iptvId ? `v${s.iptvId}` : "";

/** Events with no id at all say nothing about taste. */
export const valid = (s: Signal): boolean => !!idOf(s) && (s.kind === "movie" || s.kind === "series");

/** Same (type, title, minute) is one event: the server dedupes the same way, so a double-fire costs nothing. */
export const dedupeKey = (s: Signal): string => `${s.type}|${idOf(s)}|${Math.floor(s.at / 60_000)}`;

export interface Queue {
  add(s: Signal): boolean;
  /** Up to `max` events to send; they stay queued until `done` or `requeue`. */
  take(max?: number): Signal[];
  /** The taken events went (or must not be retried). */
  done(sent: Signal[]): void;
  size(): number;
  clear(): void;
}

export function createQueue(cap = QUEUE_CAP): Queue {
  let list: Signal[] = [];
  const keys = new Set<string>();
  return {
    add(s) {
      if (!valid(s)) return false;
      const k = dedupeKey(s);
      if (keys.has(k)) return false;
      keys.add(k);
      list.push(s);
      while (list.length > cap) keys.delete(dedupeKey(list.shift()!));
      return true;
    },
    take: (max = BATCH_MAX) => list.slice(0, Math.min(max, BATCH_MAX)),
    done(sent) {
      const gone = new Set(sent);
      list = list.filter((s) => !gone.has(s));
    },
    size: () => list.length,
    clear() {
      list = [];
      keys.clear();
    },
  };
}

// ---------- watching ----------

export interface WatchMeta {
  kind: "movie" | "series";
  tmdbId?: number;
  itemId?: string;
  iptvId?: string;
  /** Film, episode or channel pick: only a film leaving early counts as 'skip'. */
  isFilm: boolean;
  /** Started from a personal channel (the schedule chose it): a weaker signal, never finish or skip. */
  channel: boolean;
}

/** Real playing time: counts the steps of normal playback, never a seek (a jump of more than a couple of seconds). */
export class PlayTracker {
  private last: number | null = null;
  playedS = 0;
  tick(t: number, maxStep = 2.5): void {
    if (!isFinite(t)) return;
    const d = this.last == null ? 0 : t - this.last;
    if (d > 0 && d <= maxStep) this.playedS += d;
    this.last = t;
  }
  /** After a seek or a new source, the next tick starts a fresh step. */
  seeked(): void {
    this.last = null;
  }
  reset(): void {
    this.last = null;
    this.playedS = 0;
  }
}

/** What a watch of `playedS` seconds says. `runtimeS` 0 = unknown. Null when it is too short to say anything. */
export function watchSignal(meta: WatchMeta, playedS: number, runtimeS: number, ended: boolean, at: number): Signal | null {
  const base = { kind: meta.kind, tmdbId: meta.tmdbId, itemId: meta.itemId, iptvId: meta.iptvId, at };
  const ms = Math.round(Math.max(0, playedS) * 1000);
  if (meta.channel) return playedS >= CHANNEL_PLAY_MIN_S ? { ...base, type: "play", ms } : null;
  if (ended || (runtimeS > 0 && playedS >= runtimeS * FINISH_FRACTION)) return playedS >= PLAY_MIN_S ? { ...base, type: "finish", ms } : null;
  if (meta.isFilm && runtimeS >= SKIP_MIN_RUNTIME_S && playedS >= SKIP_MIN_PLAYED_S && playedS < SKIP_UNDER_S) return { ...base, type: "skip", ms };
  return playedS >= PLAY_MIN_S ? { ...base, type: "play", ms } : null;
}

export const trailerSignal = (m: Pick<Signal, "kind" | "tmdbId" | "itemId" | "iptvId">, maxS: number, at: number): Signal | null =>
  maxS >= TRAILER_MIN_S ? { ...m, type: "trailer", at, ms: Math.round(maxS * 1000) } : null;

export const detailSignal = (m: Pick<Signal, "kind" | "tmdbId" | "itemId" | "iptvId">, dwellMs: number, at: number): Signal | null =>
  dwellMs >= DETAIL_MIN_MS ? { ...m, type: "detail", at, ms: Math.min(DETAIL_MAX_MS, Math.round(dwellMs)) } : null;

/** The signal target for a library item: films by their own id, episodes by their series (taste is about shows, not episodes). */
export function libraryTarget(it: { Id: string; Type?: string; SeriesId?: string; ProviderIds?: Record<string, string> }): Pick<Signal, "kind" | "tmdbId" | "itemId"> | null {
  const ep = it.Type === "Episode" || it.Type === "Season";
  if (it.Type !== "Movie" && it.Type !== "Series" && !ep) return null;
  const id = ep ? it.SeriesId : it.Id;
  if (!id) return null;
  const tm = Number(it.ProviderIds?.Tmdb);
  return { kind: it.Type === "Movie" ? "movie" : "series", itemId: id, ...(!ep && tm > 0 ? { tmdbId: tm } : {}) };
}

/**
 * One title's watch: real playing time, the meta and runtime as they were when it started playing (a screen that moves on to
 * another title must not close this one with the next one's), and closed once so a finish is never followed by a second event.
 */
export class WatchSession {
  private tracker = new PlayTracker();
  private meta: WatchMeta | null = null;
  private runtimeS = 0;
  private closed = false;
  tick(t: number, maxStep: number | undefined, meta: WatchMeta | null, runtimeS: number): void {
    if (this.closed) return;
    if (!this.meta && meta) this.meta = meta;
    if (!this.runtimeS && runtimeS > 0) this.runtimeS = runtimeS;
    this.tracker.tick(t, maxStep);
  }
  seeked(): void {
    this.tracker.seeked();
  }
  /** The event for this watch, once; later calls (and calls for a watch that never played) give null. */
  end(ended: boolean, at: number): Signal | null {
    if (this.closed) return null;
    this.closed = true;
    return this.meta ? watchSignal(this.meta, this.tracker.playedS, this.runtimeS, ended, at) : null;
  }
  /** A new title: forget everything. */
  reset(): void {
    this.tracker.reset();
    this.meta = null;
    this.runtimeS = 0;
    this.closed = false;
  }
}
