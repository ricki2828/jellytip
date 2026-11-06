// Default audio / subtitle tracks from the person's language prefs (pure; tested in web/test/tracks.test.ts).
// Prefs hold ISO 639-1 codes in order ("en", "fr"); Jellyfin streams carry ISO 639-2 ("eng", "fre"/"fra").

const ALIASES: Record<string, string[]> = {
  en: ["eng"],
  mi: ["mao", "mri"],
  fr: ["fre", "fra"],
  es: ["spa"],
  de: ["ger", "deu"],
  it: ["ita"],
  pt: ["por"],
  nl: ["dut", "nld"],
  sv: ["swe"],
  ja: ["jpn"],
  ko: ["kor"],
  zh: ["chi", "zho", "cmn", "yue"],
  hi: ["hin"],
};

export interface Stream {
  Index: number;
  Type: string;
  Language?: string;
  Title?: string;
  DisplayTitle?: string;
  IsDefault?: boolean;
  IsForced?: boolean;
  IsTextSubtitleStream?: boolean;
}

/** Does a stream's language tag mean this pref code? */
export function langIs(tag: string | undefined, code: string): boolean {
  const t = (tag ?? "").toLowerCase().split(/[-_]/)[0];
  if (!t) return false;
  return t === code || (ALIASES[code] ?? []).includes(t);
}

/** One spelling per language ("fre"/"fra"/"fr" -> "fr"); "" for unknown ("und", missing). Never guessed from titles. */
export function canonLang(tag: string | undefined): string {
  const t = (tag ?? "").toLowerCase().split(/[-_]/)[0];
  if (!t || t === "und" || t === "unk" || t === "zxx") return "";
  for (const [code, list] of Object.entries(ALIASES)) if (t === code || list.includes(t)) return code;
  return t;
}

/** Commentary / audio-description wording. Shared with iptvTracks. A bare "ad" is not enough: "AD" must be a whole upper-case token. */
export const COMMENTARY = /commentar|director'?s? ?(cut )?track|audio[ -]?desc|described|descriptive|visually impaired|isolated|karaoke|\bfx only\b|behind the scenes/i;
/** Commentary / audio-description style tracks (judged from the file's own title text only). */
export function isCommentaryText(t: string): boolean {
  return COMMENTARY.test(t) || /\bAD\b/.test(t);
}
export function isCommentary(s: Stream): boolean {
  return isCommentaryText(`${s.Title ?? ""} ${s.DisplayTitle ?? ""}`);
}

/** Best of several same-language tracks: not commentary, then the file's default, then the first. */
function best<T extends Stream>(m: T[]): T | undefined {
  const main = m.filter((s) => !isCommentary(s));
  return main.find((s) => s.IsDefault) ?? main[0];
}

/**
 * Audio: the first pref language the file has (never a commentary track; its default among them first).
 * No match: the fallback, unless that is a commentary track and a plain one exists.
 */
export function pickAudio(streams: Stream[], langs: string[], fallback: number | null | undefined): number | null {
  const audio = streams.filter((s) => s.Type === "Audio");
  for (const code of langs) {
    const b = best(audio.filter((s) => langIs(s.Language, code)));
    if (b) return b.Index;
  }
  const fb = audio.find((s) => s.Index === fallback);
  if (fb && isCommentary(fb)) {
    const alt = best(audio);
    if (alt) return alt.Index;
  }
  return fallback ?? null;
}

export interface LangPrefs {
  audioLangs: string[];
  subtitleLangs: string[];
  /** "Always show subtitles" (this device). */
  alwaysSubs?: boolean;
}

/**
 * Subtitles (-1 = off; image subtitles are never picked, the web player draws text only):
 * - audio in a preferred audio language: forced subs in the preferred language, else the file's default (`fallback`);
 * - audio in another language, or "always show subtitles": the first full text subtitle in a subtitle pref language;
 * - audio language unknown: never assumed foreign, so only the always-on toggle turns subtitles on.
 */
export function pickSubtitle(streams: Stream[], audioIndex: number | null, prefs: LangPrefs, fallback: number): number {
  const audio = streams.find((s) => s.Type === "Audio" && s.Index === audioIndex);
  const known = !!audio && !!canonLang(audio.Language);
  const matches = known && prefs.audioLangs.some((c) => langIs(audio!.Language, c));
  const foreign = known && !matches && prefs.audioLangs.length > 0;
  const subs = streams.filter((s) => s.Type === "Subtitle" && s.IsTextSubtitleStream && !isCommentary(s));
  const subLangs = prefs.subtitleLangs.length ? prefs.subtitleLangs : prefs.audioLangs;
  if (foreign || prefs.alwaysSubs) {
    for (const code of subLangs) {
      const m = subs.filter((s) => langIs(s.Language, code));
      const pick = m.find((s) => !s.IsForced && s.IsDefault) ?? m.find((s) => !s.IsForced) ?? m[0];
      if (pick) return pick.Index;
    }
    return fallback;
  }
  if (matches) {
    for (const code of subLangs) {
      const f = subs.find((s) => s.IsForced && langIs(s.Language, code));
      if (f) return f.Index;
    }
  }
  return fallback;
}
