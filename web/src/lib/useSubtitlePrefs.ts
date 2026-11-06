import { useCallback, useState } from "react";
import { loadSubtitlePrefs, parseSubtitlePrefs, saveSubtitlePrefs, subtitleStyleVars, type SubtitlePrefs } from "./subtitlePrefs.ts";

/** Subtitle appearance, persisted; `vars` goes on the player root as inline style. */
export function useSubtitlePrefs() {
  const [prefs, setState] = useState<SubtitlePrefs>(() => loadSubtitlePrefs());
  const setPrefs = useCallback((patch: Partial<SubtitlePrefs>) => {
    setState((p) => {
      const next = parseSubtitlePrefs({ ...p, ...patch });
      saveSubtitlePrefs(next);
      return next;
    });
  }, []);
  return { prefs, setPrefs, vars: subtitleStyleVars(prefs) };
}
