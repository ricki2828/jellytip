// The Live drawer hosted as a side panel. Squeeze-back (spike, 7-Nov): one layout change per open/close (the stage grid goes 100/0 to
// 72/28 via a class), the panel fades in on opacity only (150 ms); no blur, no scrim, `contain: layout paint`. The overlay fallback draws
// the same panel opaque over 28% of the frame with the picture untouched; on a portrait phone it is a bottom sheet. A frame-drop watch
// (getVideoPlaybackQuality, first 30 s after an open) switches a device to the overlay for good.
import { useEffect, useRef, useState } from "react";
import { LiveDrawer } from "../LiveDrawer";
import { SheetClose } from "../BackArrow";
import type { LiveSource } from "./useLiveFeed";
import { DROP_KEY, DROP_WINDOW_MS, dropsTooHigh, type PanelMode } from "../../lib/companion";
import "./Companion.css";

/** Frame-drop watch while a squeezed panel is open. Calls `onBad` once if the drop rate over the window is too high. */
export function useDropWatch(video: HTMLVideoElement | null, squeezed: boolean, onBad: () => void) {
  const cb = useRef(onBad);
  cb.current = onBad;
  useEffect(() => {
    if (!video || !squeezed || typeof video.getVideoPlaybackQuality !== "function") return;
    let q0 = video.getVideoPlaybackQuality();
    const t0 = Date.now();
    // A hidden tab drops frames by design: those never count, so the baseline is re-taken when it hides and when it comes back
    const rebase = () => { q0 = video.getVideoPlaybackQuality(); };
    document.addEventListener("visibilitychange", rebase);
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      const q = video.getVideoPlaybackQuality();
      const total = q.totalVideoFrames - q0.totalVideoFrames;
      const dropped = q.droppedVideoFrames - q0.droppedVideoFrames;
      if (dropsTooHigh(total, dropped)) {
        try {
          localStorage.setItem(DROP_KEY, "overlay");
        } catch {
          /* the switch still holds for this session */
        }
        window.clearInterval(timer);
        cb.current();
      } else if (Date.now() - t0 >= DROP_WINDOW_MS) window.clearInterval(timer);
    }, 2000);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", rebase);
    };
  }, [video, squeezed]);
}

export function Panel(props: {
  open: boolean;
  mode: PanelMode;
  matchId: string;
  liveNow: boolean;
  spoilers: boolean;
  reveal: (atSec?: number) => boolean;
  source?: LiveSource;
  onClose: () => void;
  onTouch: () => void;
}) {
  const { open, mode, onTouch } = props;
  // Stay mounted for the 150 ms fade-out, then drop the drawer (and its polling)
  const [mounted, setMounted] = useState(open);
  useEffect(() => {
    if (open) return setMounted(true);
    const t = window.setTimeout(() => setMounted(false), 170);
    return () => window.clearTimeout(t);
  }, [open]);
  // The squeezed panel starts below the player's top bar, whatever height that has (it overlays the panel's top edge)
  const [barH, setBarH] = useState(72);
  useEffect(() => {
    if (!mounted) return;
    const bar = document.querySelector<HTMLElement>(".player-top");
    if (bar) setBarH(Math.ceil(bar.getBoundingClientRect().height));
  }, [mounted, mode]);
  if (!mounted) return null;
  return (
    <div className={`cp-panel ${mode}${open ? " on" : ""}`} style={{ "--cp-top": `${barH}px` } as React.CSSProperties} aria-hidden={!open} onPointerDown={onTouch} onFocusCapture={onTouch}>
      {!props.liveNow ? (
        <aside className="live-drawer bc cp-replay" role="dialog" aria-label="Live stats">
          <SheetClose onClose={props.onClose} />
          <p className="iptv-hint">Replay · live updates off</p>
        </aside>
      ) : (
      <LiveDrawer embedded matchId={props.matchId} liveNow={props.liveNow} spoilers={props.spoilers} reveal={props.reveal} source={props.source} onClose={props.onClose} />
      )}
    </div>
  );
}

/** Portrait phone: the panel is a bottom sheet over the picture (the squeeze would leave a postage stamp). */
export function usePhonePortrait(): boolean {
  const q = "(max-width: 700px) and (orientation: portrait)";
  const [m, setM] = useState(() => typeof matchMedia === "function" && matchMedia(q).matches);
  useEffect(() => {
    if (typeof matchMedia !== "function") return;
    const mq = matchMedia(q);
    const on = () => setM(mq.matches);
    mq.addEventListener("change", on);
    on();
    return () => mq.removeEventListener("change", on);
  }, []);
  return m;
}
