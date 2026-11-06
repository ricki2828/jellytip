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

export type MenuKeyAction =
  | { kind: "close" }
  | { kind: "move"; key: NavKey }
  | { kind: "row"; dir: -1 | 1 }
  | { kind: "tab"; shift: boolean }
  | { kind: "swallow" }
  | { kind: "pass" };

/** Key handling INSIDE the open menu. Left/Right step along a radio row (S/M/L/XL) and never reach seek/spatial nav. */
export function menuKeyAction(key: string, shift = false): MenuKeyAction {
  switch (key) {
    case "Escape":
    case "Backspace":
      return { kind: "close" };
    case "ArrowDown":
    case "ArrowUp":
    case "Home":
    case "End":
      return { kind: "move", key };
    case "ArrowLeft":
      return { kind: "row", dir: -1 };
    case "ArrowRight":
      return { kind: "row", dir: 1 };
    case "Tab":
      return { kind: "tab", shift };
    case " ":
    case "Enter":
      return { kind: "swallow" }; // the focused button's own click runs; playback must not toggle
    default:
      return { kind: "pass" };
  }
}

/** Window/document-level handler while the menu is open: Back/Escape close it even if focus left; all else is the menu's (and spatial nav's) business. */
export function playerKeyWhileMenu(menuOpen: boolean, key: string): "close" | "defer" | "normal" {
  if (!menuOpen) return "normal";
  return key === "Escape" || key === "Backspace" ? "close" : "defer";
}

/** Focus trap: next index on Tab within `count` enabled-or-not items (wraps). */
export function tabTarget(count: number, current: number, shift: boolean): number {
  if (count <= 0) return -1;
  if (current < 0) return shift ? count - 1 : 0;
  return (current + (shift ? -1 : 1) + count) % count;
}

/** Neighbour within a row of items (no wrap, skips disabled); returns current if none. */
export function rowTarget(disabled: readonly boolean[], current: number, dir: -1 | 1): number {
  for (let i = current + dir; i >= 0 && i < disabled.length; i += dir) if (!disabled[i]) return i;
  return current;
}
