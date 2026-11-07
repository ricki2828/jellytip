// Live lower-third alerts: while a live key match plays in the web player, /live is polled every 30 s with the drawer closed
// (the open drawer reports its own 15 s answers). Key moments (goal / try / TD / wicket / red card / late lead change / a
// favourite's score) and Polymarket favourite flips (each `at` once, with sport.flipAlerts) become candidates; the gate in
// lib/alertPolicy.ts shows at most one per 30 s as a 4 s lower-third, and only once `reveal(event time)` says the spoiler delay
// has passed. Nothing shows in replay, and sport.alerts "off" silences everything; a blowout keeps only favourite scores.
import { useEffect, useRef, useState } from "react";
import { liveStats, matchDetail } from "../api/matchInfo";
import { usePrefs } from "../api/me";
import { isBlowout, isLateGame, parseAlertMode, pickAlert, SHOW_MS, sharedGate, type Candidate } from "../lib/alertPolicy";
import { newMoments, pct, type LiveStats } from "../lib/liveStats";
import { GOLD, teamColors } from "../lib/broadcast";
import { BroadcastToast, type ToastData } from "./BroadcastToast";
import type { LiveSource } from "./Companion/useLiveFeed";
import "../screens/iptv/iptv.css";

const EVENT = "jellytip-flip";
const POLL_MS = 30_000;
const TICK_MS = 1000;
const SCORING = new Set(["goal", "try", "touchdown", "field_goal", "score"]);
const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/** The Live drawer calls this with each answer it gets, so a flip seen there also toasts. */
export const announceFlips = (matchId: string, d: LiveStats) => {
  // A thin market (little money behind it) never makes a toast
  if (d.market?.flip && !d.market.thin) window.dispatchEvent(new CustomEvent(EVENT, { detail: { matchId, at: d.market.flip.at, to: d.market.flip.to, prob: d.market.flip.prob } }));
};

export function FlipAlerts({ matchId, active, drawerOpen, reveal, replay = false, blowout, source }: {
  matchId: string | null | undefined;
  active: boolean;
  drawerOpen: boolean;
  /** From useRevealClock: whether an event at this epoch second may be shown yet. Omitted = no delay (older callers). */
  reveal?: (atSec?: number) => boolean;
  replay?: boolean;
  /** Overrides the blowout the component works out from the score. */
  blowout?: boolean;
  /** The player's feed: when given, its answers are used and this component asks /live for nothing. */
  source?: LiveSource;
}) {
  const { prefs } = usePrefs({ defer: true });
  const mode = parseAlertMode(prefs?.sport.alerts);
  const modeRef = useRef(mode);
  modeRef.current = mode;
  const revealRef = useRef(reveal);
  revealRef.current = reveal;
  const blowRef = useRef(false);
  const favs = useRef<string[]>([]);
  favs.current = (prefs?.sport.teams ?? []).map(norm);
  const pending = useRef<Candidate<ToastData>[]>([]);
  const live = active && !replay && mode !== "off";
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
  const offer = (c: Candidate<ToastData>) => {
    if (!pending.current.some((x) => x.id === c.id)) pending.current = [...pending.current, c];
  };
  const show = (to: string, prob: number, at: number) => {
    if (seen.current.has(at)) return;
    seen.current.add(at);
    // Head to head: a side flipped. A winner market (F1 race, golf tournament): `to` is the name of the new favourite.
    const payload: ToastData =
      to === "home" || to === "away"
        ? { color: colors.current[to], kicker: "POLYMARKET FLIP", text: `${names.current[to]} now favourites`, sub: `${pct(prob)} · Polymarket`, icon: "flip" }
        : { color: colors.current.home, kicker: "NEW FAVOURITE", text: to, sub: `${pct(prob)} · Polymarket`, icon: "flip" };
    offer({ id: `flip:${at}`, kind: "flip", at, payload });
  };

  // The gate: once a second, show the best revealed candidate if the 30 s gap allows
  useEffect(() => {
    if (!live) {
      pending.current = [];
      return;
    }
    const t = window.setInterval(() => {
      const now = Date.now() / 1000;
      const r = pickAlert(pending.current, sharedGate, now, { reveal: (at) => (revealRef.current ? revealRef.current(at) : true), mode: modeRef.current, blowout: blowRef.current });
      pending.current = r.pending;
      if (r.show) {
        sharedGate.lastShownAt = now;
        showToast(r.show.payload);
      }
    }, TICK_MS);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live]);
  useEffect(() => {
    if (blowout != null) blowRef.current = blowout;
  }, [blowout]);

  useEffect(() => {
    if (!matchId || !live) return;
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
  }, [matchId, live]);

  // Odds are spoilers: flips toast only with Show scores (spoilers) on
  const spoilersRef = useRef(false);
  spoilersRef.current = !!prefs?.sport.spoilers;
  const blowoutGiven = useRef(blowout != null);
  blowoutGiven.current = blowout != null;
  /** One /live answer: a flip, the blowout state and any new moments become candidates. */
  const ingest = (d: LiveStats) => {
    if (spoilersRef.current && d.market?.flip && !d.market.thin) show(d.market.flip.to, d.market.flip.prob, d.market.flip.at);
    const late = isLateGame(d.layout, d.period, d.clock);
    if (!blowoutGiven.current) blowRef.current = isBlowout(d.layout, d.score?.home, d.score?.away, late);
    const latest = d.moments.reduce((m, x) => Math.max(m, x.at), 0);
    if (seenMoment.current == null) seenMoment.current = latest; // the first answer is the baseline
    else {
      const fresh = newMoments(d.moments, seenMoment.current);
      seenMoment.current = Math.max(seenMoment.current, latest);
      for (const m of fresh) {
        const who = m.team ? names.current[m.team] : null;
        offer({
          id: `m:${m.at}:${m.kind}:${m.team ?? ""}`,
          kind: m.kind,
          at: m.at,
          favourite: SCORING.has(m.kind) && !!who && favs.current.includes(norm(who)),
          late,
          payload: { color: m.team ? colors.current[m.team] : GOLD, kicker: "MOMENT", text: m.label.toUpperCase(), sub: who, icon: "moment" },
        });
      }
    }
  };

  // The player's feed (one /live poll for the whole player): every new answer goes through ingest
  useEffect(() => {
    if (!source || !matchId || !live) return;
    let last: LiveStats | null = null;
    const run = () => {
      const snap = source.get();
      if (snap.fixture) {
        names.current = { home: snap.fixture.home.name, away: snap.fixture.away.name };
        const c = teamColors(snap.fixture);
        colors.current = c.known ? { home: c.home, away: c.away } : { home: GOLD, away: GOLD };
      }
      if (snap.raw && snap.raw !== last) {
        last = snap.raw;
        ingest(snap.raw);
      }
    };
    run();
    return source.subscribe(run);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, matchId, live]);

  // Poll while the drawer is closed (standalone: no feed)
  useEffect(() => {
    if (!matchId || !live || drawerOpen || source) return;
    let alive = true;
    let t: number | undefined;
    const poll = async () => {
      // The light call: only the market (flip), the moments and the score, with the panel closed
      const d = await liveStats(matchId, "market,moments,score");
      if (!alive) return;
      if (d === "missing") return; // no live route: stop asking
      if (d) ingest(d);
      t = window.setTimeout(poll, POLL_MS);
    };
    t = window.setTimeout(poll, 1500);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matchId, live, drawerOpen, source]);

  return toast ? <BroadcastToast t={toast.t} id={toast.n} /> : null;
}
