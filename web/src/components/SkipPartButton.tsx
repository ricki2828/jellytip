// "Skip intro" / "Skip recap" / "Skip credits" over a player (TV parity §5 and the TV library player's skip-segment button):
// shown for 10 s of play from where the part was reached, above the control bar so a tap that brings the controls up never
// hides it. Click / Enter skips, Escape (Back) dismisses it for this part. Keys are taken on window capture, before the
// players' own document handlers (Enter = play/pause, Escape = leave). Only this component re-renders on timeupdate.
import { useEffect, useRef, useState, type RefObject } from "react";
import { useVideoTime } from "./TimeBar";
import { activeSeg, skipAction, skipLabel, skipVisible, type SkipSeg } from "../lib/skipParts";

export function SkipPartButton({
  segs,
  videoRef,
  offset = 0,
  duration,
  hasNext = false,
  onNext,
  onSeek,
  blocked = false,
}: {
  segs: SkipSeg[];
  videoRef: RefObject<HTMLVideoElement | null>;
  /** A remux's start offset: the button works on the title's timeline. */
  offset?: number;
  /** The title's length (seconds), for the stop-short-of-the-end rule; 0 = unknown. */
  duration: number;
  /** Credits with a next episode play it (when [onNext] is given). */
  hasNext?: boolean;
  onNext?: () => void;
  /** The player's own seek (remux-aware on IPTV). */
  onSeek: (to: number) => void;
  /** A menu or card owns the screen: no button, no keys. */
  blocked?: boolean;
}) {
  const pos = useVideoTime(videoRef, false, offset);
  const seg = segs.length ? activeSeg(segs, pos) : null;
  // Per part (kind + start), not per kind: a second ad / preview / recap gets its own 10 s and its own dismiss
  const partKey = (x: SkipSeg) => `${x.kind}@${x.s}`;
  const firstSeen = useRef(new Map<string, number>());
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(() => new Set());
  // A new list (another title): start over
  useEffect(() => {
    firstSeen.current = new Map();
    setDismissed(new Set());
  }, [segs]);
  useEffect(() => {
    if (seg && !firstSeen.current.has(partKey(seg))) firstSeen.current.set(partKey(seg), pos);
  }, [seg, pos]);
  const shown = !blocked && skipVisible(seg, pos, seg ? firstSeen.current.get(partKey(seg)) ?? null : null, !!seg && dismissed.has(partKey(seg)));

  const act = useRef<{ skip: () => void; dismiss: () => void }>({ skip: () => undefined, dismiss: () => undefined });
  act.current = {
    skip: () => {
      if (!seg) return;
      setDismissed((d) => new Set(d).add(partKey(seg)));
      const a = skipAction(seg, hasNext && !!onNext, duration);
      if (a.kind === "next") onNext?.();
      else onSeek(a.to);
    },
    dismiss: () => {
      if (seg) setDismissed((d) => new Set(d).add(partKey(seg)));
    },
  };

  useEffect(() => {
    if (!shown) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const ae = document.activeElement as HTMLElement | null;
      // On another control (or typing): that control's key
      const elsewhere = !!ae && !ae.classList.contains("skip-part") && (/^(BUTTON|A|INPUT|SELECT|TEXTAREA)$/.test(ae.tagName) || ae.isContentEditable);
      if (elsewhere) return;
      if (e.key === "Enter") {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        if (!e.repeat) act.current.skip();
      } else if (e.key === "Escape" || e.key === "Backspace") {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        act.current.dismiss();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [shown]);

  if (!shown || !seg) return null;
  return (
    <button
      className="btn skip-part"
      data-nav
      onClick={(e) => {
        e.stopPropagation();
        act.current.skip();
      }}
    >
      {skipLabel(seg.kind)}
    </button>
  );
}
