// Per-title track memory: a manual audio / subtitle choice is remembered per series (or movie) for this user and
// re-applied before the global language prefs. Stored by language, not index, so it carries to the next episode.
// localStorage keyed by user: the companion prefs API drops unknown keys (me.py _validate), so it can't hold this.
// Pure parts tested in web/test/trackMemory.test.ts.
import { canonLang, isCommentary, type Stream } from "./tracks.ts";

export const MEMORY_MAX = 200;

/** a: audio language; s: subtitle language, "" = subtitles off. t: last used (LRU). */
export interface Entry {
  a?: string;
  s?: string;
  t: number;
}
export type Store = Record<string, Entry>;

export function memoryKey(item: { Id: string; Type?: string; SeriesId?: string }): string {
  return item.Type === "Episode" && item.SeriesId ? `s:${item.SeriesId}` : `m:${item.Id}`;
}

export function parseStore(raw: unknown): Store {
  const out: Store = {};
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return out;
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    if (!v || typeof v !== "object") continue;
    const e = v as Record<string, unknown>;
    const entry: Entry = { t: typeof e.t === "number" && isFinite(e.t) ? e.t : 0 };
    if (typeof e.a === "string" && e.a) entry.a = e.a;
    if (typeof e.s === "string") entry.s = e.s;
    if (entry.a !== undefined || entry.s !== undefined) out[k] = entry;
  }
  return out;
}

/** Record a manual change (stream indexes in `streams`); unknown-language tracks can't be generalised and are skipped. */
export function recordChoice(store: Store, key: string, streams: Stream[], change: { audio?: number; subtitle?: number }, now: number): Store {
  const prev = store[key];
  const e: Entry = { ...prev, t: now };
  if (change.audio !== undefined) {
    const lang = canonLang(streams.find((s) => s.Type === "Audio" && s.Index === change.audio)?.Language);
    if (lang) e.a = lang;
  }
  if (change.subtitle !== undefined) {
    if (change.subtitle < 0) e.s = "";
    else {
      const lang = canonLang(streams.find((s) => s.Type === "Subtitle" && s.Index === change.subtitle)?.Language);
      if (lang) e.s = lang;
    }
  }
  if (e.a === prev?.a && e.s === prev?.s) return store;
  const next: Store = { ...store, [key]: e };
  const keys = Object.keys(next);
  if (keys.length > MEMORY_MAX) for (const k of keys.sort((x, y) => next[x].t - next[y].t).slice(0, keys.length - MEMORY_MAX)) delete next[k];
  return next;
}

/** The remembered choice as indexes in this file (undefined where the file doesn't have that language: prefs decide). */
export function resolveMemory(streams: Stream[], e: Entry | undefined): { audio?: number; subtitle?: number } {
  const out: { audio?: number; subtitle?: number } = {};
  if (!e) return out;
  const pick = (list: Stream[]) => {
    const main = list.filter((s) => !isCommentary(s));
    return main.find((s) => s.IsDefault) ?? main[0];
  };
  if (e.a) {
    const a = pick(streams.filter((s) => s.Type === "Audio" && canonLang(s.Language) === e.a));
    if (a) out.audio = a.Index;
  }
  if (e.s === "") out.subtitle = -1;
  else if (e.s) {
    const subs = streams.filter((s) => s.Type === "Subtitle" && s.IsTextSubtitleStream && canonLang(s.Language) === e.s);
    const full = pick(subs.filter((s) => !s.IsForced));
    const any = full ?? pick(subs);
    if (any) out.subtitle = any.Index;
  }
  return out;
}

// ---------- storage (per user, this browser) ----------
const key = (userId: string) => `jellytip.trackMemory.${userId}`;
const alwaysKey = (userId: string) => `jellytip.alwaysSubs.${userId}`;

export function loadStore(userId: string, st: Pick<Storage, "getItem"> = localStorage): Store {
  try {
    return parseStore(JSON.parse(st.getItem(key(userId)) ?? "{}"));
  } catch {
    return {};
  }
}
export function saveStore(userId: string, store: Store, st: Pick<Storage, "setItem"> = localStorage) {
  try {
    st.setItem(key(userId), JSON.stringify(store));
  } catch {
    /* storage full or blocked: the memory just isn't kept */
  }
}
/** "Always show subtitles": per user, this device only (the companion drops unknown prefs keys). */
export const getAlwaysSubs = (userId: string, st: Pick<Storage, "getItem"> = localStorage) => {
  try {
    return st.getItem(alwaysKey(userId)) === "1";
  } catch {
    return false;
  }
};
export const setAlwaysSubs = (userId: string, on: boolean, st: Pick<Storage, "setItem" | "removeItem"> = localStorage) => {
  try {
    if (on) st.setItem(alwaysKey(userId), "1");
    else st.removeItem(alwaysKey(userId));
  } catch {
    /* ignore */
  }
};
