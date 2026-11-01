import type { FantasyLive } from "../lib/fantasy";
export function FantasyMatchups({ live }: { live: FantasyLive }) {
  return <>{live.leagues.map((league) => <section className="fantasy-league" key={league.id}>
    <h2>{league.name}</h2><p>{league.source === "yahoo" ? "Yahoo" : league.source === "sleeper" ? "Sleeper" : "Fantasy"}</p>
    <div className="fantasy-sides">{(["me", "opp"] as const).map(side => <section key={side}>
      <h3>{side === "me" ? "Your team" : "Opponent"}: {league[side].team} <strong>{league[side].points.toFixed(1)} pts</strong></h3>
      <ul>{league.starters.filter(player => player.side === side).map((player, index) => <li key={`${player.name}-${index}`}>
        <div><strong>{player.name}</strong><span>{player.pos ?? "Position unknown"} · {player.nflTeam ?? "Team unknown"}</span></div>
        <div><strong>{player.points.toFixed(1)} pts</strong><span>{player.gameState === "in" ? "Live" : player.gameState === "pre" ? "Pregame" : player.gameState === "post" ? "Final" : "Game status unavailable"}</span></div>
      </li>)}</ul>
      {!league.starters.some(player => player.side === side) && <p>No starters available yet.</p>}
    </section>)}</div>
  </section>)}</>;
}
