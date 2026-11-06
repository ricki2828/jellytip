import { useCallback, useEffect, useRef, useState } from "react";
import { loadSubtitlePrefs, parseSubtitlePrefs, saveSubtitlePrefs, subtitleStyleVars, type SubtitlePrefs } from "./subtitlePrefs.ts";

/** Subtitle appearance, persisted; `vars` goes on the player root as inline style. */
export function useSubtitlePrefs() {
  const [prefs, setState] = useState<SubtitlePrefs>(() => loadSubtitlePrefs());
  const first = useRef(true);
  const setPrefs = useCallback((patch: Partial<SubtitlePrefs>) => setState((p) => parseSubtitlePrefs({ ...p, ...patch })), []);
  // Persist from an effect (updaters must stay pure); skip the initial load
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    saveSubtitlePrefs(prefs);
  }, [prefs]);
  return { prefs, setPrefs, vars: subtitleStyleVars(prefs) };
}
