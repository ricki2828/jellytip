// Fantasy (Sleeper) in the player: the matchup chip, scoring toasts and the drawer's Fantasy tab. All of it stays hidden
// without a linked account or without the server routes. Contract: ~/ai/plans/jellytip-live-sport.md "Fantasy live scoring for NFL".
import { useEffect, useRef, useState } from "react";
import { fantasyGame, fantasyLive, useFantasy } from "../api/fantasy";
import { matchDetailCached } from "../api/matchInfo";
import { usePrefs } from "../api/me";
import { chipText, enqueue, eventText, isNfl, shortLeague, type FantasyLive, type Starter } from "../lib/fantasy";
import type { Fixture } from "../lib/matchInfo";
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
export function FantasyChip({ matchId, active }: { matchId: string | null | undefined; active: boolean }) {
  const fixture = useFixture(active ? matchId : null);
  const nfl = !!fixture && isNfl(fixture.leagueKey);
  const live = useFantasy(active && nfl);
  const text = live ? chipText(live) : null;
  // Keep it fresh while it is on screen
  useEffect(() => {
    if (!active || !nfl || !live) return;
    const t = setInterval(() => void fantasyLive(true), 30_000);
    return () => clearInterval(t);
  }, [active, nfl, !!live]);
  if (!text) return null;
  return (
    <span className="fantasy-chip" title="Your fantasy matchups">
      {text}
    </span>
  );
}

/** Scoring events (a starter up by 4+) as a top-right toast, like the flip one; off with fantasy.alerts. */
export function FantasyAlerts({ matchId, active }: { matchId: string | null | undefined; active: boolean }) {
  const fixture = useFixture(active ? matchId : null);
  const nfl = !!fixture && isNfl(fixture.leagueKey);
  const { prefs } = usePrefs({ defer: true });
  const on = active && nfl && !!prefs?.fantasy.sleeperUsername && prefs.fantasy.alerts;
  const [shown, setShown] = useState<{ text: string; side: "me" | "opp"; key: number } | null>(null);
  const queue = useRef<{ text: string; side: "me" | "opp" }[]>([]);
  const busy = useRef(false);
  const seen = useRef<Set<string> | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const keyN = useRef(0);

  // One toast at a time, 6 s each; up to 3 wait (the oldest are dropped)
  const next = () => {
    const n = queue.current.shift();
    if (!n) {
      busy.current = false;
      return setShown(null);
    }
    busy.current = true;
    setShown({ ...n, key: ++keyN.current });
    timer.current = window.setTimeout(next, 6000);
  };
  const push = (n: { text: string; side: "me" | "opp" }) => {
    queue.current = enqueue(queue.current, n);
    if (!busy.current) next();
  };

  useEffect(() => {
    if (!on) return;
    let alive = true;
    let t: number | undefined;
    const poll = async () => {
      const d = await fantasyLive(true);
      if (!alive) return;
      if (d) {
        const first = seen.current == null;
        seen.current ??= new Set();
        const now = Date.now() / 1000;
        for (const e of d.events) {
          const k = `${e.at}|${e.name}|${e.side}`;
          if (seen.current.has(k)) continue;
          seen.current.add(k);
          // The first answer may carry earlier events: only a recent one is news
          if (first && now - e.at > 90) continue;
          push({ text: eventText(e, d), side: e.side });
        }
        t = window.setTimeout(poll, 30_000);
      }
    };
    void poll();
    return () => {
      alive = false;
      window.clearTimeout(t);
      window.clearTimeout(timer.current);
      queue.current = [];
      busy.current = false;
      setShown(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [on]);
  if (!shown) return null;
  return (
    <div key={shown.key} className={`flip-toast fantasy ${shown.side === "opp" ? "opp" : "mine"}`} role="status">
      {shown.text}
    </div>
  );
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
export function FantasyTab({ live, fixtureId }: { live: FantasyLive; fixtureId: string }) {
  const [game, setGame] = useState<{ id: string; league: string; starters: Starter[] }[] | null>(null);
  useEffect(() => {
    let alive = true;
    void fantasyGame(fixtureId).then((g) => alive && setGame(g));
    return () => {
      alive = false;
    };
  }, [fixtureId, live.updatedAt]);
  return (
    <>
      {live.leagues.map((l) => {
        const here = game?.find((g) => g.id === l.id)?.starters ?? l.starters.filter((s) => s.matchId === fixtureId);
        return (
          <section key={l.id}>
            <h4>{l.name}</h4>
            <p className="ld-odds">
              {l.me.team} {pts(l.me.points)} – {pts(l.opp.points)} {l.opp.team}
            </p>
            {here.length > 0 && (
              <>
                <p className="iptv-hint">In this game</p>
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
            {here.length === 0 && <p className="iptv-hint">None of your {shortLeague(l.name)} players are in this game.</p>}
          </section>
        );
      })}
    </>
  );
}
