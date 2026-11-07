// Fantasy (Sleeper) in the player: the matchup chip, scoring toasts and the drawer's Fantasy tab. All of it stays hidden
// without a linked account or without the server routes. Contract: ~/ai/plans/jellytip-live-sport.md "Fantasy live scoring for NFL".
import { useEffect, useRef, useState } from "react";
import { fantasyFirstEvents, fantasyGame, fantasySeen, subscribeFantasyRefresh, useFantasy } from "../api/fantasy";
import { matchDetailCached } from "../api/matchInfo";
import { usePrefs } from "../api/me";
import { parseAlertMode, pickAlert, SHOW_MS, sharedGate, type Candidate } from "../lib/alertPolicy";
import { chipText, eventParts, isNfl, shortLeague, type FantasyLive, type Starter } from "../lib/fantasy";
import type { Fixture } from "../lib/matchInfo";
import { BroadcastToast, type ToastData } from "./BroadcastToast";
import "../screens/iptv/iptv.css";

/** The match behind a match id (null until known, or when the server has none). */
export function useFixture(matchId: string | null | undefined): Fixture | null {
  const [f, setF] = useState<Fixture | null>(null);
  useEffect(() => {
    if (!matchId) return setF(null);
    let alive = true;
    void matchDetailCached(matchId).then((m) => alive && m && m !== "missing" && setF(m.fixture));
    return () => {
      alive = false;
    };
  }, [matchId]);
  return f;
}

/** "🏈 CCP 87.4 – 79.2": my matchups while an NFL game plays. */
export function FantasyChip({ matchId, active, reveal, replay = false }: { matchId: string | null | undefined; active: boolean; reveal?: (atSec?: number) => boolean; replay?: boolean }) {
  const fixture = useFixture(active ? matchId : null);
  const nfl = !!fixture && isNfl(fixture.leagueKey);
  const live = useFantasy(active && nfl);
  const text = live ? chipText(live) : null;
  // Scores are a spoiler: hidden in replay, and until the spoiler delay allows live information at all
  if (!text || replay || (reveal && !reveal())) return null;
  return (
    <span className="fantasy-chip" title="Your fantasy matchups">
      {text}
    </span>
  );
}

/**
 * Fantasy swings as a lower-third (key mode: a starter up or down 5+; sport.alerts "all": whatever the server sends past
 * fantasy.alertMin), through the same gate as the moment alerts: shown after `reveal(event time)`, at most one per 30 s across
 * both, 4 s each, nothing in replay or in a blowout. Off with fantasy.alerts or sport.alerts "off".
 */
export function FantasyAlerts({ matchId, active, reveal, replay = false, blowout = false }: { matchId: string | null | undefined; active: boolean; reveal?: (atSec?: number) => boolean; replay?: boolean; blowout?: boolean }) {
  const fixture = useFixture(active ? matchId : null);
  const nfl = !!fixture && isNfl(fixture.leagueKey);
  const { prefs } = usePrefs({ defer: true });
  const mode = parseAlertMode(prefs?.sport.alerts);
  const on = active && !replay && nfl && !!prefs?.fantasy.alerts && mode !== "off";
  const live = useRef({ reveal, mode, blowout });
  live.current = { reveal, mode, blowout };
  const [shown, setShown] = useState<{ t: ToastData; key: number } | null>(null);
  const queue = useRef<Candidate<ToastData>[]>([]);
  const busy = useRef(false);
  const timer = useRef<number | undefined>(undefined);
  const keyN = useRef(0);

  // The gate ticks once a second: the best revealed swing goes up for 4 s when the shared 30 s gap allows
  const push = (c: Candidate<ToastData>) => {
    queue.current = [...queue.current, c];
  };
  useEffect(() => {
    if (!on) return;
    const tick = window.setInterval(() => {
      const now = Date.now() / 1000;
      const r = pickAlert(queue.current, sharedGate, now, { reveal: (at) => (live.current.reveal ? live.current.reveal(at) : true), mode: live.current.mode, blowout: live.current.blowout });
      queue.current = r.pending;
      if (!r.show) return;
      sharedGate.lastShownAt = now;
      busy.current = true;
      setShown({ t: r.show.payload, key: ++keyN.current });
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => {
        busy.current = false;
        setShown(null);
      }, SHOW_MS);
    }, 1000);
    return () => window.clearInterval(tick);
  }, [on]);

  useEffect(() => {
    if (!on) return;
    const unsubscribe = subscribeFantasyRefresh((d) => {
      if (!d || !d.leagues.length) return;
      const first = fantasyFirstEvents();
      const now = Date.now() / 1000;
      for (const key of fantasySeen) if (Number(key.split("|", 1)[0]) < now - 900) fantasySeen.delete(key);
      for (const e of d.events) {
        const k = `${e.at}|${e.league}|${e.name}`;
        if (fantasySeen.has(k)) continue;
        fantasySeen.add(k);
        // The first snapshot establishes the baseline, including recent events.
        // A remounted alert consumer must never replay that baseline as a toast.
        if (first) continue;
        const p = eventParts(e, d);
        push({ id: k, kind: "fantasy", at: e.at, swing: Math.abs(e.delta), payload: { color: e.side === "opp" ? "#D9473D" : "#22C55E", kicker: p.kicker, text: p.text, sub: p.sub, icon: "fantasy" } });
      }
    });
    const resetForSession = () => {
      window.clearTimeout(timer.current);
      queue.current = [];
      busy.current = false;
      setShown(null);
    };
    window.addEventListener("jellytip-session", resetForSession);
    return () => {
      unsubscribe();
      window.removeEventListener("jellytip-session", resetForSession);
      window.clearTimeout(timer.current);
      queue.current = [];
      busy.current = false;
      setShown(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [on]);
  if (!shown) return null;
  return <BroadcastToast t={shown.t} id={shown.key} level={1} />;
}

const pts = (n: number) => (Math.round(n * 10) / 10).toFixed(1);

function StarterRow({ s }: { s: Starter }) {
  return (
    <li>
      <span className="mi-num">{s.pos ?? ""}</span>
      {s.name} <span className="iptv-hint">{s.nflTeam ?? ""}</span>
      <strong className="ld-pts">{pts(s.points)}</strong>
    </li>
  );
}

/** The drawer's Fantasy tab: each league's matchup score, then my and my opponents' players in the game being watched. */
export function FantasyTab({ live, fixtureId, multi = false }: { live: FantasyLive; fixtureId: string; multi?: boolean }) {
  const [game, setGame] = useState<{ id: string; league: string; starters: Starter[] }[] | null>(null);
  useEffect(() => {
    let alive = true;
    // A whip-around (RedZone) has no single game: every live player of yours across games is shown, no per-game route
    if (!multi) void fantasyGame(fixtureId).then((g) => alive && setGame(g));
    return () => {
      alive = false;
    };
  }, [fixtureId, live.updatedAt, multi]);
  return (
    <>
      {live.leagues.map((l) => {
        // multi: every starter in a game that is on now (mine first), from every game; otherwise this game's players only
        const here = multi ? [...l.starters.filter((s) => s.gameState === "in")].sort((a, b) => (a.side === b.side ? b.points - a.points : a.side === "me" ? -1 : 1)) : (game?.find((g) => g.id === l.id)?.starters ?? l.starters.filter((s) => s.matchId === fixtureId));
        return (
          <section key={l.id}>
            <h4>{l.name}</h4>
            <p className="ld-odds">
              {l.me.team} {pts(l.me.points)} – {pts(l.opp.points)} {l.opp.team}
            </p>
            {here.length > 0 && (
              <>
                <p className="iptv-hint">{multi ? "Your players on now" : "In this game"}</p>
                <ul className="mi-list">
                  {here.filter((s) => s.side === "me").map((s, i) => <StarterRow key={`m${i}`} s={s} />)}
                </ul>
                {here.some((s) => s.side === "opp") && (
                  <ul className="mi-list opp">
                    {here.filter((s) => s.side === "opp").map((s, i) => <StarterRow key={`o${i}`} s={s} />)}
                  </ul>
                )}
              </>
            )}
            {here.length === 0 && <p className="iptv-hint">{multi ? `None of your ${shortLeague(l.name)} players are playing right now.` : `None of your ${shortLeague(l.name)} players are in this game.`}</p>}
          </section>
        );
      })}
    </>
  );
}
