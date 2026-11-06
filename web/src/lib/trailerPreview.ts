// Trailer auto-preview rules, pure so they can be tested: which source a title uses (Jellyfin local trailer vs the
// IPTV companion's cached path), when a preview may start (prefs, touch, reduced motion, Save-Data, hidden page),
// the per-session cache with its single re-check, and the one-preview-at-a-time slot.

/** A title to preview: a Jellyfin item (its local trailer), an IPTV title (companion detail), or an address we already hold. */
export type PreviewTarget = { kind: "jf"; id: string } | { kind: "iptv"; id: string } | { kind: "direct"; key: string; url: string };

export const targetKey = (t: PreviewTarget) => (t.kind === "direct" ? `direct:${t.key}` : `${t.kind}:${t.id}`);

/** Jellyfin item -> whose trailer plays (episodes and seasons use their series'); null for types without one. */
export function jfTarget(item: { Id: string; Type: string; SeriesId?: string }): PreviewTarget | null {
  const id = item.Type === "Episode" || item.Type === "Season" ? item.SeriesId : item.Id;
  if (!id || !["Movie", "Series", "Episode", "Season"].includes(item.Type)) return null;
  return { kind: "jf", id };
}

/**
 * A companion trailer address a <video> can play: a companion-relative path (resolved by `resolve`) or an https
 * video file. Anything else (a YouTube page, a scheme we do not know) is a web link, not a preview.
 */
export function iptvPlayable(trailer: string | null | undefined, resolve: (rel: string) => string): string | null {
  if (!trailer) return null;
  if (/^https?:\/\//i.test(trailer)) return /\.(mp4|webm|m3u8)(\?|$)/i.test(trailer) ? trailer : null;
  if (trailer.startsWith("//") || /^[a-z][a-z0-9+.-]*:/i.test(trailer)) return null;
  return resolve(trailer);
}

export interface PreviewEnv {
  /** No hover, coarse pointer: a phone or tablet. */
  touch: boolean;
  reducedMotion: boolean;
  saveData: boolean;
  /** The page is in a background tab. */
  hidden: boolean;
}

/** `focus`: the person moved to this title (focus / hover / tap); `passive`: it appeared on its own (a details page opening, the rotating mix). */
export type Trigger = "focus" | "passive";

export type GateReason = "pref" | "rotating" | "reduced-motion" | "save-data" | "hidden" | "touch-passive";

/** May a preview start now? `autoplayPref` is playback.trailerAutoplay (undefined = prefs not loaded = on, as before). */
export function previewGate(o: { autoplayPref?: boolean; env: PreviewEnv; trigger: Trigger; rotating?: boolean }): { ok: true } | { ok: false; reason: GateReason } {
  if (o.autoplayPref === false) return { ok: false, reason: "pref" };
  if (o.rotating) return { ok: false, reason: "rotating" };
  if (o.env.reducedMotion) return { ok: false, reason: "reduced-motion" };
  if (o.env.saveData) return { ok: false, reason: "save-data" };
  if (o.env.hidden) return { ok: false, reason: "hidden" };
  // Touch has no hover: a tap is a deliberate focus, but nothing may start by itself (scrolling, a page opening)
  if (o.env.touch && o.trigger === "passive") return { ok: false, reason: "touch-passive" };
  return { ok: true };
}

/** Reads the browser; safe where matchMedia / navigator.connection are missing. */
export function readEnv(): PreviewEnv {
  const mq = (q: string) => typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(q).matches;
  const conn = (typeof navigator !== "undefined" ? (navigator as Navigator & { connection?: { saveData?: boolean } }).connection : undefined) ?? undefined;
  return {
    touch: mq("(hover: none) and (pointer: coarse)"),
    reducedMotion: mq("(prefers-reduced-motion: reduce)"),
    saveData: conn?.saveData === true,
    hidden: typeof document !== "undefined" && document.visibilityState === "hidden",
  };
}

/**
 * Per-session answers. A 200 with no trailer yet (the companion may not have cached it) is asked again at most
 * `maxMisses - 1` more times, each on a later focus; a final answer (nothing playable there) is never re-asked.
 * A transient failure (network, 401, 429, 5xx) is remembered only for `TRANSIENT_MS` and does not use up the budget.
 */
export const TRANSIENT_MS = 60_000;

export class TrailerCache {
  private m = new Map<string, { url: string | null; misses: number; final: boolean; until: number }>();
  private maxMisses: number;
  constructor(maxMisses = 2) {
    this.maxMisses = maxMisses;
  }
  /** True when the caller should fetch: never asked, or a not-final miss with re-check budget left (and not in a transient back-off). */
  shouldFetch(key: string, now = Date.now()): boolean {
    const e = this.m.get(key);
    if (!e) return true;
    if (e.until > now) return false;
    return e.url === null && !e.final && (e.misses < this.maxMisses || e.until > 0);
  }
  get(key: string): string | null {
    return this.m.get(key)?.url ?? null;
  }
  /** An authoritative answer. `final` = never ask again. */
  record(key: string, url: string | null, final = false) {
    const prev = this.m.get(key);
    this.m.set(key, { url, misses: url ? 0 : (prev?.misses ?? 0) + 1, final: final || url !== null, until: 0 });
  }
  /** A failed ask: back off for a minute, keep the miss count as it was. */
  transient(key: string, now = Date.now(), ms = TRANSIENT_MS) {
    const prev = this.m.get(key);
    this.m.set(key, { url: null, misses: prev?.misses ?? 0, final: false, until: now + ms });
  }
  clear() {
    this.m.clear();
  }
}

/** Run `fn` after `ms`; the returned function cancels it. Moving on before it fires means it never runs (no fetch for tiles passed quickly). */
export function settle(ms: number, fn: () => void): () => void {
  const t = setTimeout(fn, ms);
  return () => clearTimeout(t);
}

// One preview video on the page at a time: taking the slot stops whoever held it.
let holder: { id: string; stop: () => void } | null = null;

/** Take the slot; the previous holder's `stop` runs. Returns release (a no-op if someone else has since taken it). */
export function acquirePreview(id: string, stop: () => void): () => void {
  if (holder && holder.id !== id) holder.stop();
  const mine = { id, stop };
  holder = mine;
  return () => {
    if (holder === mine) holder = null;
  };
}

export const previewHolder = () => holder?.id ?? null;
