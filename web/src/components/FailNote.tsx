import { useEffect, useState } from "react";
import { failNote, failOf, onFailChange, type LastFail } from "../lib/streamFail";

/** Re-render when this device reports or clears a failure (so a ding shows at once). */
export function useFailTick(): number {
  const [n, setN] = useState(0);
  useEffect(() => onFailChange(() => setN((v) => v + 1)), []);
  return n;
}

/** "Failed 2 h ago" / "Failed 3× this week" for a channel, VOD copy or episode; renders nothing when it has not failed lately. */
export function FailNote({ id, lastFail, className = "fail-note" }: { id: string; lastFail?: LastFail | null; className?: string }) {
  useFailTick();
  const text = failNote(failOf(id, lastFail), Date.now() / 1000);
  return text ? <span className={className} data-fail-note>{text}</span> : null;
}
