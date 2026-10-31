// Favourite-flip toast: while a live key match plays in the web player, /live is polled every 30 s with the drawer closed
// (the open drawer reports its own 15 s answers). A new `market.flip.at` shows a top-right toast for 6 s that slides in from
// the right; each `at` shows once. Off on replays and recordings, and with sport.flipAlerts off.
import { useEffect, useRef, useState } from "react";
import { liveStats, matchDetail } from "../api/matchInfo";
import { newMoments, pct, type LiveStats } from "../lib/liveStats";
import { GOLD, teamColors } from "../lib/broadcast";
import { BroadcastToast, type ToastData } from "./BroadcastToast";
import "../screens/iptv/iptv.css";

const EVENT = "jellytip-flip";
const POLL_MS = 30_000;
const SHOW_MS = 6000;

/** The Live drawer calls this with each answer it gets, so a flip seen there also toasts. */
export const announceFlips = (matchId: string, d: LiveStats) => {
  // A thin market (little money behind it) never makes a toast
  if (d.market?.flip && !d.market.thin) window.dispatchEvent(new CustomEvent(EVENT, { detail: { matchId, at: d.market.flip.at, to: d.market.flip.to, prob: d.market.flip.prob } }));
};

export function FlipAlerts({ matchId, active, drawerOpen }: { matchId: string | null | undefined; active: boolean; drawerOpen: boolean }) {
  const [toast, setToast] = useState<{ t: ToastData; n: number } | null>(null);
  const nRef = useRef(0);
  const seen = useRef(new Set<number>());
  const names = useRef({ home: "Home", away: "Away" });
  const colors = useRef({ home: GOLD, away: GOLD });
  const timer = useRef<number | undefined>(undefined);
  const seenMoment = useRef<number | null>(null);

  const showToast = (t: ToastData) => {
    setToast({ t, n: ++nRef.current });
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), SHOW_MS);
  };
  const show = (to: string, prob: number, at: number) => {
    if (seen.current.has(at)) return;
    seen.current.add(at);
    // Head to head: a side flipped. A winner market (F1 race, golf tournament): `to` is the name of the new favourite.
    if (to === "home" || to === "away") showToast({ color: colors.current[to], kicker: "POLYMARKET FLIP", text: `${names.current[to]} now favourites`, sub: `${pct(prob)} · Polymarket`, icon: "flip" });
    else showToast({ color: colors.current.home, kicker: "NEW FAVOURITE", text: to, sub: `${pct(prob)} · Polymarket`, icon: "flip" });
  };

  useEffect(() => {
    if (!matchId || !active) return;
    let alive = true;
    void matchDetail(matchId).then((m) => {
      if (alive && m && m !== "missing") {
        names.current = { home: m.fixture.home.name, away: m.fixture.away.name };
        const c = teamColors(m.fixture);
        colors.current = c.known ? { home: c.home, away: c.away } : { home: GOLD, away: GOLD };
      }
    });
    const on = (e: Event) => {
      const d = (e as CustomEvent<{ matchId: string; at: number; to: string; prob: number }>).detail;
      if (d.matchId === matchId) show(d.to, d.prob, d.at);
    };
    window.addEventListener(EVENT, on);
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
      window.clearTimeout(timer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matchId, active]);

  // Poll while the drawer is closed
  useEffect(() => {
    if (!matchId || !active || drawerOpen) return;
    let alive = true;
    let t: number | undefined;
    const poll = async () => {
      // The light call: only the market (flip), the moments and the score, with the panel closed
      const d = await liveStats(matchId, "market,moments,score");
      if (!alive) return;
      if (d === "missing") return; // no live route: stop asking
      if (d?.market?.flip && !d.market.thin) show(d.market.flip.to, d.market.flip.prob, d.market.flip.at);
      if (d) {
        const latest = d.moments.reduce((m, x) => Math.max(m, x.at), 0);
        if (seenMoment.current == null) seenMoment.current = latest; // the first answer is the baseline
        else {
          const fresh = newMoments(d.moments, seenMoment.current);
          seenMoment.current = Math.max(seenMoment.current, latest);
          const m = fresh[fresh.length - 1];
          if (m) {
            showToast({ color: m.team ? colors.current[m.team] : GOLD, kicker: "MOMENT", text: m.label.toUpperCase(), sub: m.team ? names.current[m.team] : null, icon: "moment" });
          }
        }
      }
      t = window.setTimeout(poll, POLL_MS);
    };
    t = window.setTimeout(poll, 1500);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matchId, active, drawerOpen]);

  return toast ? <BroadcastToast t={toast.t} id={toast.n} /> : null;
}
