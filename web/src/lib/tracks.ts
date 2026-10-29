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

interface Stream {
  Index: number;
  Type: string;
  Language?: string;
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

/** Audio: the first pref language the file has (its default track among them first); else the file's default. */
export function pickAudio(streams: Stream[], langs: string[], fallback: number | null | undefined): number | null {
  const audio = streams.filter((s) => s.Type === "Audio");
  for (const code of langs) {
    const m = audio.filter((s) => langIs(s.Language, code));
    if (m.length) return (m.find((s) => s.IsDefault) ?? m[0]).Index;
  }
  return fallback ?? null;
}

/**
 * Subtitles: when the chosen audio is one of the person's audio languages, keep the file's default (forced parts);
 * otherwise turn on the first text subtitle in a subtitle pref language (full subtitles before forced ones).
 * -1 = off. Image subtitles are never picked (the web player draws text only).
 */
export function pickSubtitle(
  streams: Stream[],
  audioIndex: number | null,
  prefs: { audioLangs: string[]; subtitleLangs: string[] },
  fallback: number,
): number {
  if (!prefs.subtitleLangs.length) return fallback;
  const audio = streams.find((s) => s.Type === "Audio" && s.Index === audioIndex);
  if (audio && prefs.audioLangs.some((c) => langIs(audio.Language, c))) return fallback;
  const subs = streams.filter((s) => s.Type === "Subtitle" && s.IsTextSubtitleStream);
  for (const code of prefs.subtitleLangs) {
    const m = subs.filter((s) => langIs(s.Language, code));
    if (m.length) return (m.find((s) => !s.IsForced) ?? m[0]).Index;
  }
  return fallback;
}
