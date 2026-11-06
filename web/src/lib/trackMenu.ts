// Pure logic behind the shared audio / subtitle menu (keyboard navigation, labels, track-claim guard).
export type TrackOption = { id: string; label: string; active: boolean; disabled?: boolean };

/** Provider tracks without metadata fall back to "Track N": an audio track must never imply a language we were not given. */
export function audioOptionLabel(label: string | undefined | null): string {
  const t = (label ?? "").trim();
  return !t || /^Track \d+$/i.test(t) || /^und(etermined)?$/i.test(t) ? "Unknown" : t;
}

/** Jellyfin audio stream: its own display title, else its language, else Unknown. */
export function jellyfinAudioLabel(s: { DisplayTitle?: string | null; Language?: string | null }): string {
  const lang = s.Language && s.Language !== "und" ? s.Language : "";
  return audioOptionLabel(s.DisplayTitle || lang);
}

/** Audio can only be chosen when the engine exposes more than one track; a single track is shown, not offered. */
export const audioSelectable = (audio: readonly TrackOption[]) => audio.length > 1;

export type NavKey = "ArrowDown" | "ArrowUp" | "Home" | "End";

/** Next focusable index in a menu (wraps; skips disabled). -1 when nothing is focusable. */
export function nextFocus(disabled: readonly boolean[], current: number, key: NavKey): number {
  const n = disabled.length;
  const ok = (i: number) => !disabled[i];
  if (!disabled.some((d) => !d)) return -1;
  if (key === "Home") return disabled.findIndex((_, i) => ok(i));
  if (key === "End") { for (let i = n - 1; i >= 0; i--) if (ok(i)) return i; }
  const step = key === "ArrowUp" ? -1 : 1;
  let i = current;
  for (let k = 0; k < n; k++) {
    i = (i + step + n) % n;
    if (ok(i)) return i;
  }
  return current;
}

/** Index to focus when the menu opens: the active choice, else the first enabled one. */
export function initialFocus(options: readonly Pick<TrackOption, "active" | "disabled">[]): number {
  const a = options.findIndex((o) => o.active && !o.disabled);
  return a >= 0 ? a : options.findIndex((o) => !o.disabled);
}
