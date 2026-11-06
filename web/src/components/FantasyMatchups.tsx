import { useEffect, useRef, useState } from "react";
import type { FantasyLeague, FantasyLive } from "../lib/fantasy";
import { eventParts } from "../lib/fantasy";
import { leagueEvents, scoreState } from "../lib/fantasyPage";
import { watchable } from "../lib/fantasyGame";
import { WatchGameButton } from "./FantasyWatch";

function Score({ value, suffix = "" }: { value: number; suffix?: string }) {
  const previous = useRef(value);
  const [changed, setChanged] = useState(false);
  useEffect(() => {
    if (previous.current === value) return;
    previous.current = value;
    setChanged(true);
    const timer = setTimeout(() => setChanged(false), 650);
    return () => clearTimeout(timer);
  }, [value]);
  return <strong className={changed ? "fantasy-number fantasy-score-changed" : "fantasy-number"}>{value.toFixed(1)}{suffix}</strong>;
}

function Matchup({ league, live }: { league: FantasyLeague; live: FantasyLive }) {
  const [side, setSide] = useState<"me" | "opp">("me");
  const events = leagueEvents(live.events, league);
  return <section className="fantasy-league">
    <header><h2>{league.name}</h2><span>{league.source === "yahoo" ? "Yahoo" : league.source === "sleeper" ? "Sleeper" : "Fantasy"}</span></header>
    <div className="fantasy-matchup"><div><span>Yours · {league.me.team}</span><Score value={league.me.points} /></div><div><span>Opponent · {league.opp.team}</span><Score value={league.opp.points} /></div></div>
    <p className="fantasy-lead">{scoreState(league)}</p>
    {(() => { const mine = league.starters.filter(p => p.side === "me" && watchable(p)).sort((a, b) => Number(b.gameState === "in") - Number(a.gameState === "in")); return mine.length ? <WatchGameButton starters={mine} label={mine.length > 1 ? "Watch a live game" : "Watch " + mine[0].name + "'s game"} /> : null; })()}
    {league.me.projected != null && <p>Your projection <strong className="fantasy-number">{league.me.projected.toFixed(1)}</strong></p>}
    {events.length > 0 && <div className="fantasy-events"><h3>Recent scoring</h3><ol>{events.map((event, index) => { const parts = eventParts(event, live); return <li key={event.at + ":" + event.name + ":" + index}><strong>{parts.text}</strong>{event.reason && <span>{event.reason}</span>}</li>; })}</ol></div>}
    <div className="fantasy-selector" role="group" aria-label={league.name + " starters"}><button type="button" aria-pressed={side === "me"} onClick={() => setSide("me")}>Yours</button><button type="button" aria-pressed={side === "opp"} onClick={() => setSide("opp")}>Opponent</button></div>
    <div className="fantasy-sides">{(["me", "opp"] as const).map(which => <section className={side === which ? "fantasy-side active" : "fantasy-side"} key={which}>
      <h3>{which === "me" ? "Your team" : "Opponent"}: {league[which].team}</h3>
      <ul>{league.starters.filter(player => player.side === which).map((player, index) => <li key={player.name + ":" + index}>
        <div><strong>{player.name}</strong><span>{player.pos ?? "Position unknown"} · {player.nflTeam ?? "Team unknown"}</span></div>
        <div><Score value={player.points} suffix=" pts" /><span>{player.gameState === "in" ? "Live" : player.gameState === "pre" ? "Pregame" : player.gameState === "post" ? "Final" : "Game status unavailable"}</span>{watchable(player) && <WatchGameButton starters={[player]} />}</div>
      </li>)}</ul>
      {!league.starters.some(player => player.side === which) && <p>No starters available yet.</p>}
    </section>)}</div>
  </section>;
}
export function FantasyMatchups({ live }: { live: FantasyLive }) {
  return <>{live.leagues.map(league => <Matchup key={league.id} league={league} live={live} />)}</>;
}
