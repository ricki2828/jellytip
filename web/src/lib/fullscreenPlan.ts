/** Which fullscreen route a toggle takes. Pure so it can be tested without a DOM. */
export type FsStep = "native" | "exit" | "element" | "video" | "none";

export function fullscreenStep(o: { native: boolean; active: boolean; canElement: boolean; canVideo: boolean }): FsStep {
  if (o.native) return "native";
  if (o.active) return "exit";
  if (o.canElement) return "element";
  // iPhone Safari: no element fullscreen, only the video itself (native controls, no companion)
  return o.canVideo ? "video" : "none";
}

/** A channel hop remounts the player inside the same fullscreen host: stay fullscreen. Only leaving the host (or no host) ends it. */
export function keepFullscreenOnUnmount(hostStillConnected: boolean | undefined): boolean {
  return hostStillConnected === true;
}

/** Escape can reach us after the browser already left fullscreen: that press was the exit, not "leave the player". */
export function exitAlreadyConsumed(now: number, lastExitAt: number, windowMs = 300): boolean {
  return now - lastExitAt >= 0 && now - lastExitAt < windowMs;
}

/** Keys that must not toggle fullscreen: typing in the companion's ask box, or a modified shortcut. */
export function isFullscreenKey(e: { key: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean }, typing: boolean): boolean {
  return !typing && !e.ctrlKey && !e.metaKey && !e.altKey && (e.key === "f" || e.key === "F");
}
