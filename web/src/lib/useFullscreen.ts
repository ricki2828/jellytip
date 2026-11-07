import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { isNative, NativeDownloads } from "./native";
import { fullscreenStep } from "./fullscreenPlan";

type WebkitVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void };

/**
 * Fullscreen for a player. `boxRef` is the CONTAINER (video + companion + overlays), so the companion keeps working;
 * only the iPhone Safari fallback fullscreens the bare <video>. Capacitor uses the native window mode instead.
 * `exitIfFullscreen()` returns true when it left fullscreen (callers then swallow Back/Escape).
 */
export function useFullscreen(boxRef: RefObject<HTMLElement | null>, videoRef: RefObject<HTMLVideoElement | null>) {
  const nativeFs = useRef(false);
  const [active, setActive] = useState(false);

  useEffect(() => {
    const sync = () => setActive(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", sync);
    return () => document.removeEventListener("fullscreenchange", sync);
  }, []);

  // Leaving the player never leaves the page or window stuck fullscreen
  useEffect(() => () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined);
    if (nativeFs.current) {
      nativeFs.current = false;
      NativeDownloads.setPlayerMode({ fullscreen: false }).catch(() => undefined);
    }
  }, []);

  const toggle = useCallback(() => {
    const box = boxRef.current;
    const step = fullscreenStep({
      native: isNative,
      active: !!document.fullscreenElement,
      canElement: !!box?.requestFullscreen,
      canVideo: !!(videoRef.current as WebkitVideo | null)?.webkitEnterFullscreen,
    });
    if (step === "native") {
      nativeFs.current = !nativeFs.current;
      setActive(nativeFs.current);
      NativeDownloads.setPlayerMode({ fullscreen: nativeFs.current }).catch(() => undefined);
    } else if (step === "exit") document.exitFullscreen().catch(() => undefined);
    else if (step === "element") box?.requestFullscreen().catch(() => undefined);
    else if (step === "video") (videoRef.current as WebkitVideo | null)?.webkitEnterFullscreen?.();
  }, [boxRef, videoRef]);

  const exitIfFullscreen = useCallback((): boolean => {
    if (isNative && nativeFs.current) { toggle(); return true; }
    if (document.fullscreenElement) { document.exitFullscreen().catch(() => undefined); return true; }
    return false;
  }, [toggle]);

  return { active, toggle, exitIfFullscreen };
}
