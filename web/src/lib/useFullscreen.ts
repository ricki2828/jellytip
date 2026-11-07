import { useCallback, useEffect, useState, type RefObject } from "react";
import { isNative, NativeDownloads } from "./native";
import { exitAlreadyConsumed, fullscreenStep, keepFullscreenOnUnmount } from "./fullscreenPlan";

type WebkitVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void };

// Module state: survives the player remounting on a channel hop (App keys the player by its query string)
let nativeOn = false;
let lastExitAt = -Infinity;
/** The rotate action sends fullscreen:false to the native window: keep the flag in step. */
export function noteNativeFullscreen(on: boolean) { nativeOn = on; }

/**
 * Fullscreen for a player. The element fullscreened is the nearest `[data-fs-host]` ancestor (a stable, un-keyed wrapper that
 * survives a channel hop), else `boxRef` itself; either holds the video AND the companion so it keeps working. Only the iPhone
 * Safari fallback fullscreens the bare <video>. Capacitor uses the native window mode instead.
 * `exitIfFullscreen()` returns true when it left (or just left) fullscreen: callers then swallow Back/Escape.
 */
export function useFullscreen(boxRef: RefObject<HTMLElement | null>, videoRef: RefObject<HTMLVideoElement | null>) {
  const [active, setActive] = useState(() => (isNative ? nativeOn : !!document.fullscreenElement));
  const hostOf = () => boxRef.current?.closest<HTMLElement>("[data-fs-host]") ?? boxRef.current;

  useEffect(() => {
    const sync = () => {
      if (!document.fullscreenElement) lastExitAt = Date.now();
      setActive(!!document.fullscreenElement);
    };
    document.addEventListener("fullscreenchange", sync);
    return () => document.removeEventListener("fullscreenchange", sync);
  }, []);

  // Leaving playback never leaves the page or window stuck fullscreen; a hop (host survives) keeps it
  useEffect(() => {
    const host = hostOf();
    return () => {
      window.setTimeout(() => {
        if (keepFullscreenOnUnmount(host?.isConnected)) return;
        if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
        if (nativeOn) {
          nativeOn = false;
          NativeDownloads.setPlayerMode({ fullscreen: false }).catch(() => undefined);
        }
      }, 0);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = useCallback(() => {
    const host = hostOf();
    const step = fullscreenStep({
      native: isNative,
      active: !!document.fullscreenElement,
      canElement: !!host?.requestFullscreen,
      canVideo: !!(videoRef.current as WebkitVideo | null)?.webkitEnterFullscreen,
    });
    if (step === "native") {
      nativeOn = !nativeOn;
      setActive(nativeOn);
      NativeDownloads.setPlayerMode({ fullscreen: nativeOn }).catch(() => undefined);
    } else if (step === "exit") document.exitFullscreen().catch(() => undefined);
    else if (step === "element") host?.requestFullscreen().catch(() => undefined);
    else if (step === "video") (videoRef.current as WebkitVideo | null)?.webkitEnterFullscreen?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boxRef, videoRef]);

  const exitIfFullscreen = useCallback((): boolean => {
    if (isNative && nativeOn) { toggle(); return true; }
    if (document.fullscreenElement) { document.exitFullscreen().catch(() => undefined); return true; }
    return exitAlreadyConsumed(Date.now(), lastExitAt);
  }, [toggle]);

  return { active, toggle, exitIfFullscreen };
}
