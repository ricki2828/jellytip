// Reader-adjustable subtitle appearance, shared by the Jellyfin and IPTV players and persisted in localStorage.
export const SUB_SIZES = ["S", "M", "L", "XL"] as const;
export type SubSize = (typeof SUB_SIZES)[number];
export const SUB_BACKGROUNDS = ["none", "shadow", "box"] as const;
export type SubBackground = (typeof SUB_BACKGROUNDS)[number];

export type SubtitlePrefs = { size: SubSize; background: SubBackground; /** raise (+) or lower (-) the captions, % of player height */ offset: number };

export const SUB_PREFS_KEY = "jellytip.subtitleStyle";
export const SUB_OFFSET_MIN = -5;
export const SUB_OFFSET_MAX = 30;
export const SUB_OFFSET_STEP = 5;
export const DEFAULT_SUBTITLE_PREFS: SubtitlePrefs = { size: "M", background: "shadow", offset: 0 };

const SCALE: Record<SubSize, number> = { S: 0.8, M: 1, L: 1.3, XL: 1.7 };

export const clampOffset = (n: number) => Math.min(SUB_OFFSET_MAX, Math.max(SUB_OFFSET_MIN, Math.round(n / SUB_OFFSET_STEP) * SUB_OFFSET_STEP));

/** Anything stored or typed that is not a known value falls back to the readable default. */
export function parseSubtitlePrefs(raw: unknown): SubtitlePrefs {
  const o = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const size = SUB_SIZES.find((s) => s === o.size) ?? DEFAULT_SUBTITLE_PREFS.size;
  const background = SUB_BACKGROUNDS.find((b) => b === o.background) ?? DEFAULT_SUBTITLE_PREFS.background;
  const offset = typeof o.offset === "number" && Number.isFinite(o.offset) ? clampOffset(o.offset) : DEFAULT_SUBTITLE_PREFS.offset;
  return { size, background, offset };
}

type ReadStore = Pick<Storage, "getItem">;
type WriteStore = Pick<Storage, "setItem">;

export function loadSubtitlePrefs(store?: ReadStore): SubtitlePrefs {
  try {
    const s = store ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
    const raw = s?.getItem(SUB_PREFS_KEY);
    return parseSubtitlePrefs(raw ? JSON.parse(raw) : null);
  } catch {
    return { ...DEFAULT_SUBTITLE_PREFS };
  }
}

export function saveSubtitlePrefs(prefs: SubtitlePrefs, store?: WriteStore): void {
  try {
    const s = store ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
    s?.setItem(SUB_PREFS_KEY, JSON.stringify(parseSubtitlePrefs(prefs)));
  } catch {
    /* storage full / blocked: the choice still applies for this session */
  }
}

export const stepOffset = (offset: number, dir: 1 | -1) => clampOffset(offset + dir * SUB_OFFSET_STEP);

export const offsetLabel = (offset: number) => (offset === 0 ? "Default" : `${offset > 0 ? "+" : "−"}${Math.abs(offset)}%`);

/** CSS custom properties consumed by `.subs` (custom-rendered) and `video::cue` (native text tracks). */
export function subtitleStyleVars(p: SubtitlePrefs): Record<string, string> {
  return { "--sub-scale": String(SCALE[p.size]), "--sub-lift": `${p.offset}%` };
}
