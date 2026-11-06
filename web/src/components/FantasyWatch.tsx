// "Watch this game": one look at what is on now (no polling), then open the channel in the IPTV player.
import { useEffect, useRef, useState } from "react";
import type { Starter } from "../lib/fantasy";
import { findGameChannel, NOT_ON_CHANNELS } from "../lib/fantasyGame";
import { livePath } from "../lib/iptvPlay";

/** The on-now endpoint has no paging, so ask for plenty: the list holds every channel with something airing, sport first. */
const ON_NOW_LIMIT = 300;

/** Tries each starter's game in turn (a matchup offers all its live players) until one is on a channel. */
export function WatchGameButton({ starters, label = "Watch this game" }: { starters: Pick<Starter, "matchId" | "nflTeam">[]; label?: string }) {
  const [state, setState] = useState<"idle" | "busy" | "none">("idle");
  const alive = useRef(true);
  const session = useRef(0);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => {
    const bump = () => { session.current++; };
    window.addEventListener("jellytip-session", bump);
    return () => { alive.current = false; window.clearTimeout(timer.current); window.removeEventListener("jellytip-session", bump); };
  }, []);
  const click = async () => {
    if (state === "busy") return;
    const mine = session.current;
    setState("busy");
    let hit = null;
    try {
      const { onNow } = await import("../api/iptv");
      const list = await onNow(ON_NOW_LIMIT, true);
      const now = Math.floor(Date.now() / 1000);
      for (const s of starters) { hit = findGameChannel(s, list, now); if (hit) break; }
    } catch { hit = null; }
    // Gone, or the account changed while we were looking: don't open another person's channel
    if (!alive.current || session.current !== mine) return;
    if (hit) {
      const { go } = await import("../lib/router");
      if (!alive.current || session.current !== mine) return;
      setState("idle");
      go(livePath(hit.channelId, hit.channel, hit.extras));
      return;
    }
    setState("none");
    timer.current = window.setTimeout(() => alive.current && setState("idle"), 6000);
  };
  return <span className="fantasy-watch">
    <button type="button" data-nav className="fantasy-watch-btn" aria-disabled={state === "busy"} onClick={() => void click()}>{state === "busy" ? "Finding…" : label}</button>
    {state === "none" && <span role="status" className="fantasy-watch-none">{NOT_ON_CHANNELS}</span>}
  </span>;
}
