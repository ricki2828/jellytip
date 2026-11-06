// Per-title track memory: a manual audio / subtitle choice is remembered per series (or movie) for this user and
// re-applied before the global language prefs. Stored by language, not index, so it carries to the next episode.
// localStorage keyed by user: the companion prefs API drops unknown keys (me.py _validate), so it can't hold this.
// Pure parts tested in web/test/trackMemory.test.ts.
import { canonLang, isCommentary, pickAudio, pickSubtitle, type LangPrefs, type Stream } from "./tracks.ts";

export const MEMORY_MAX = 200;

/**
 * a: audio language; s: subtitle language ("" = explicitly off, "?" = a track with no language tag, can't be generalised);
 * sa: the audio language the subtitle choice was made under ("" = unknown). t: last used (LRU).
 */
export interface Entry {
  a?: string;
  s?: string;
  sa?: string;
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
    if (typeof e.sa === "string") entry.sa = e.sa;
    if (entry.a !== undefined || entry.s !== undefined) out[k] = entry;
  }
  return out;
}

const langOf = (streams: Stream[], type: string, index: number | null | undefined) =>
  canonLang(streams.find((s) => s.Type === type && s.Index === index)?.Language);

/**
 * Record a manual change (stream indexes in `streams`). `currentAudio` is the audio playing when a subtitle is picked.
 * A subtitle remembered under one audio language is not applied under another; changing audio language without picking a
 * subtitle in the same action forgets the old subtitle choice.
 */
export function recordChoice(store: Store, key: string, streams: Stream[], change: { audio?: number; subtitle?: number }, now: number, currentAudio?: number | null): Store {
  const prev = store[key];
  const e: Entry = { ...prev, t: now };
  if (change.audio !== undefined) {
    const lang = langOf(streams, "Audio", change.audio);
    if (lang) e.a = lang;
    else delete e.a;
    if (change.subtitle === undefined && (e.sa ?? "") !== lang) {
      delete e.s;
      delete e.sa;
    }
  }
  if (change.subtitle !== undefined) {
    e.s = change.subtitle < 0 ? "" : langOf(streams, "Subtitle", change.subtitle) || "?";
    e.sa = langOf(streams, "Audio", change.audio ?? currentAudio);
  }
  if (e.a === prev?.a && e.s === prev?.s && e.sa === prev?.sa) return store;
  if (e.a === undefined && e.s === undefined) {
    const { [key]: _drop, ...rest } = store;
    return rest;
  }
  const next: Store = { ...store, [key]: e };
  const keys = Object.keys(next);
  if (keys.length > MEMORY_MAX) for (const k of keys.sort((x, y) => next[x].t - next[y].t).slice(0, keys.length - MEMORY_MAX)) delete next[k];
  return next;
}

const pickMain = (list: Stream[]) => {
  const main = list.filter((s) => !isCommentary(s));
  return main.find((s) => s.IsDefault) ?? main[0];
};

/** The remembered audio as an index in this file (undefined where the file doesn't have that language). */
export function resolveAudio(streams: Stream[], e: Entry | undefined): number | undefined {
  if (!e?.a) return undefined;
  return pickMain(streams.filter((s) => s.Type === "Audio" && canonLang(s.Language) === e.a))?.Index;
}

/**
 * The remembered subtitle (-1 = off) as an index, only when the audio chosen this time is in the language the choice was
 * made under. Unknown-language ("?") and absent languages return undefined: the prefs decide.
 */
export function resolveSubtitle(streams: Stream[], e: Entry | undefined, audioIndex: number | null): number | undefined {
  if (!e || e.s === undefined || e.s === "?") return undefined;
  const under = e.sa ?? e.a;
  if (under !== undefined && under !== langOf(streams, "Audio", audioIndex)) return undefined;
  if (e.s === "") return -1;
  const subs = streams.filter((s) => s.Type === "Subtitle" && s.IsTextSubtitleStream && canonLang(s.Language) === e.s);
  return (pickMain(subs.filter((s) => !s.IsForced)) ?? pickMain(subs))?.Index;
}

/**
 * The start-of-playback choice: remembered pick, then language prefs, then the file's defaults.
 * `forceAudio` fixes the audio (e.g. the negotiated source differs from the one the audio was chosen on).
 */
export function chooseStart(
  streams: Stream[],
  entry: Entry | undefined,
  prefs: LangPrefs | null,
  opts: { defaultAudio?: number | null; defaultSub: number; forceAudio?: number | null },
): { audio: number | null; subtitle: number } {
  const audio =
    opts.forceAudio !== undefined
      ? opts.forceAudio
      : resolveAudio(streams, entry) ?? (prefs?.audioLangs.length ? pickAudio(streams, prefs.audioLangs, opts.defaultAudio) : null);
  const eff = audio ?? opts.defaultAudio ?? null;
  const byPrefs = prefs ? pickSubtitle(streams, eff, prefs, opts.defaultSub) : opts.defaultSub;
  return { audio, subtitle: resolveSubtitle(streams, entry, eff) ?? byPrefs };
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
