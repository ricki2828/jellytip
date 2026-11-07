import { canonLang, isCommentaryText, langIs, type LangPrefs } from "./tracks.ts";

export type TrackChoice = { index: number; label: string; active: boolean; /** Provider-declared language tag (never inferred from the label). */ lang?: string; name?: string };

export function trackLabel(language?: string, name?: string, index = 0): string {
  const lang = language && language !== "und" ? language : "";
  return [name, lang].filter((part, i, parts) => part && (i === 0 || part !== parts[0])).join(" · ") || `Track ${index + 1}`;
}

export function hlsChoices(tracks: readonly { name?: string; lang?: string }[], active: number): TrackChoice[] {
  return tracks.map((track, index) => ({ index, label: trackLabel(track.lang, track.name, index), active: index === active, lang: track.lang, name: track.name }));
}

export function nativeChoices(tracks: ArrayLike<{ label?: string; language?: string; kind?: string; enabled?: boolean; mode?: string }>, subtitle: boolean): TrackChoice[] {
  return Array.from(tracks, (track, index) => ({ track, index }))
    .filter(({ track }) => !subtitle || track.kind === "subtitles" || track.kind === "captions")
    .map(({ track, index }) => ({ index, label: trackLabel(track.language, track.label, index), active: subtitle ? track.mode === "showing" : !!track.enabled, lang: track.language, name: track.label }));
}

export function nativeSubtitlesOff(tracks: ArrayLike<{ kind?: string; mode?: string }>): boolean {
  return !Array.from(tracks).some((track) => (track.kind === "subtitles" || track.kind === "captions") && track.mode === "showing");
}


/**
 * Start-of-playback track choice for IPTV: the same rules as Jellyfin, from the language tags the engine exposes.
 * A track whose tag is missing/"und" is unknown and never matched; names are only used to avoid commentary.
 * Returns the audio index to switch to (null = leave) and the subtitle index (-1 = off, null = leave).
 */
export function pickIptvTracks(audio: readonly TrackChoice[], subs: readonly TrackChoice[], prefs: LangPrefs): { audio: number | null; subtitle: number | null } {
  const main = audio.filter((t) => !isCommentaryText(t.name ?? t.label));
  let audioPick: TrackChoice | undefined;
  for (const code of prefs.audioLangs) {
    const m = main.filter((t) => langIs(t.lang, code));
    audioPick = m.find((t) => t.active) ?? m[0];
    if (audioPick) break;
  }
  const chosen = audioPick ?? audio.find((t) => t.active);
  const known = !!chosen && !!canonLang(chosen.lang);
  const matches = !!audioPick;
  const foreign = known && !matches && prefs.audioLangs.length > 0;
  let subtitle: number | null = null;
  const subLangs = prefs.subtitleLangs.length ? prefs.subtitleLangs : prefs.audioLangs;
  if (foreign || prefs.alwaysSubs) {
    for (const code of subLangs) {
      const m = subs.filter((t) => langIs(t.lang, code) && !isCommentaryText(t.name ?? t.label));
      if (m.length) {
        subtitle = (m.find((t) => !/forced/i.test(t.name ?? t.label)) ?? m[0]).index;
        break;
      }
    }
  } else if (matches) {
    for (const code of subLangs) {
      const f = subs.find((t) => langIs(t.lang, code) && /forced/i.test(t.name ?? t.label));
      if (f) {
        subtitle = f.index;
        break;
      }
    }
  }
  return { audio: audioPick && !audioPick.active ? audioPick.index : null, subtitle };
}

/** A manual track pick, remembered by identity (lang/name) so it survives an engine rebuild that renumbers tracks. */
export type TrackPick = { index: number; lang?: string; name?: string };
export type SubtitlePick = { off: true } | ({ off?: false } & TrackPick);

export function makePick(choices: readonly TrackChoice[], index: number): TrackPick {
  const c = choices.find((t) => t.index === index);
  return { index, lang: c?.lang, name: c?.name };
}

const norm = (s?: string) => (s ?? "").trim().toLowerCase();
const known = (s?: string) => norm(s) !== "" && norm(s) !== "und";

/**
 * Index of the track in `choices` that matches a remembered pick, or null. lang+name, then name, then a unique lang match.
 * The old index is only trusted when the pick carried neither a name nor a language.
 */
export function matchPick(choices: readonly TrackChoice[], pick: TrackPick): number | null {
  const hasName = known(pick.name);
  const hasLang = known(pick.lang);
  if (hasName && hasLang) {
    const m = choices.find((t) => norm(t.name) === norm(pick.name) && norm(t.lang) === norm(pick.lang));
    if (m) return m.index;
  }
  if (hasName) {
    const m = choices.filter((t) => norm(t.name) === norm(pick.name));
    if (m.length === 1 || (m.length > 1 && hasLang && m.find((t) => norm(t.lang) === norm(pick.lang)))) return (m.length === 1 ? m[0] : m.find((t) => norm(t.lang) === norm(pick.lang))!).index;
  }
  if (hasLang) {
    const m = choices.filter((t) => norm(t.lang) === norm(pick.lang));
    if (m.length === 1) return m[0].index;
    return null;
  }
  if (!hasName) return choices.find((t) => t.index === pick.index)?.index ?? null;
  return null;
}
