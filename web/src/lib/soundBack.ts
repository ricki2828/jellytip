// "Back mutes" for inline videos with sound on (home hero trailer, trailer breaks, closet clips), as on the TV:
// turning sound on pushes one same-URL history entry, so phone/browser/mouse Back pops it and mutes instead of
// navigating; Esc / Backspace are consumed (document runs before the app's window-level Esc-goes-back). The next
// press behaves normally. Only one video has sound at a time: turning it on elsewhere mutes the previous one and
// takes over the same history entry (two entries, or a back() racing a push, would make Back mute the wrong video).
import { useEffect, useRef } from "react";

const STATE_KEY = "jellytipInlineSound";
let owner: { id: string; mute: () => void } | null = null;
let entryOnTop = false;

window.addEventListener("popstate", (e) => {
  if (entryOnTop) {
    entryOnTop = false;
    const o = owner;
    owner = null;
    o?.mute();
  } else if ((e.state as Record<string, unknown> | null)?.[STATE_KEY]) {
    history.back(); // left behind when the page changed with sound on: skip it
  }
});

/** Call soundOn() when the user unmutes; Back / Esc then call setMuted. Returns mute() for a visible Mute button. */
export function useSoundBack(id: string, sound: boolean, setMuted: () => void) {
  const muteRef = useRef(setMuted);
  muteRef.current = setMuted;

  const soundOn = () => {
    if (owner && owner.id !== id) owner.mute();
    owner = { id, mute: () => muteRef.current() };
    if (!entryOnTop) {
      history.pushState({ ...(history.state ?? {}), [STATE_KEY]: true }, "");
      entryOnTop = true;
    }
  };
  const mute = () => {
    muteRef.current();
    if (owner?.id !== id) return; // already handed over (or never had it)
    owner = null;
    if (entryOnTop) {
      entryOnTop = false;
      history.back(); // drop our entry; the popstate finds it gone and a plain state, so nothing else happens
    }
  };

  useEffect(() => {
    if (!sound) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" && e.key !== "Backspace") return;
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
      e.preventDefault();
      e.stopPropagation();
      mute();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sound]);

  // Leaving the page with sound on: the entry stays behind (skipped on the way back), the owner goes
  useEffect(
    () => () => {
      if (owner?.id === id) {
        owner = null;
        entryOnTop = false;
      }
    },
    [id],
  );
  return { soundOn, mute };
}
