export type TrackChoice = { index: number; label: string; active: boolean };

export function trackLabel(language?: string, name?: string, index = 0): string {
  const lang = language && language !== "und" ? language : "";
  return [name, lang].filter((part, i, parts) => part && (i === 0 || part !== parts[0])).join(" · ") || `Track ${index + 1}`;
}

export function hlsChoices(tracks: readonly { name?: string; lang?: string }[], active: number): TrackChoice[] {
  return tracks.map((track, index) => ({ index, label: trackLabel(track.lang, track.name, index), active: index === active }));
}

export function nativeChoices(tracks: ArrayLike<{ label?: string; language?: string; kind?: string; enabled?: boolean; mode?: string }>, subtitle: boolean): TrackChoice[] {
  return Array.from(tracks, (track, index) => ({ track, index }))
    .filter(({ track }) => !subtitle || track.kind === "subtitles" || track.kind === "captions")
    .map(({ track, index }) => ({ index, label: trackLabel(track.language, track.label, index), active: subtitle ? track.mode === "showing" : !!track.enabled }));
}

export function nativeSubtitlesOff(tracks: ArrayLike<{ kind?: string; mode?: string }>): boolean {
  return !Array.from(tracks).some((track) => (track.kind === "subtitles" || track.kind === "captions") && track.mode === "showing");
}
