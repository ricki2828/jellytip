// "Watch this game": one look at what is on now (no polling), then open the channel in the IPTV player.
import { useEffect, useRef, useState } from "react";
import type { Starter } from "../lib/fantasy";
import { findGameChannel, NOT_ON_CHANNELS } from "../lib/fantasyGame";
import { livePath } from "../lib/iptvPlay";

export function WatchGameButton({ starter, label = "Watch this game" }: { starter: Pick<Starter, "matchId" | "nflTeam">; label?: string }) {
  const [state, setState] = useState<"idle" | "busy" | "none">("idle");
  const alive = useRef(true);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => { alive.current = false; window.clearTimeout(timer.current); }, []);
  const click = async () => {
    if (state === "busy") return;
    setState("busy");
    let hit = null;
    try { const { onNow } = await import("../api/iptv");
      hit = findGameChannel(starter, await onNow(60, true), Math.floor(Date.now() / 1000)); } catch { hit = null; }
    if (!alive.current) return;
    if (hit) { setState("idle"); const { go } = await import("../lib/router"); go(livePath(hit.channelId, hit.channel, hit.extras)); return; }
    setState("none");
    timer.current = window.setTimeout(() => alive.current && setState("idle"), 6000);
  };
  return <span className="fantasy-watch">
    <button type="button" data-nav className="fantasy-watch-btn" disabled={state === "busy"} onClick={() => void click()}>{state === "busy" ? "Finding…" : label}</button>
    {state === "none" && <span role="status" className="fantasy-watch-none">{NOT_ON_CHANNELS}</span>}
  </span>;
}
