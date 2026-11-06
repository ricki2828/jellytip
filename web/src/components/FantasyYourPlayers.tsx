// "Your players in this game" for the live drawer: compact, hidden when there is no league or none of the user's starters play.
import type { FantasyLive } from "../lib/fantasy";
import { playersInGame } from "../lib/fantasyGame";
import type { Fixture } from "../lib/matchInfo";

const pts = (n: number) => (Math.round(n * 10) / 10).toFixed(1);

export function FantasyYourPlayers({ live, fixture }: { live: FantasyLive | null; fixture: Fixture | null }) {
  const groups = playersInGame(live, fixture);
  if (!groups.length) return null;
  return (
    <section className="ld-yours" aria-label="Your players in this game">
      <h4>Your players in this game</h4>
      {groups.map((g) => (
        <div key={g.id}>
          {groups.length > 1 && <p className="iptv-hint">{g.league}</p>}
          <ul>
            {g.players.map((p, i) => (
              <li key={p.name + i}>
                <span className="ld-yours-name">{p.name} <span className="iptv-hint">{[p.pos, p.nflTeam].filter(Boolean).join(" · ")}</span></span>
                <strong className="ld-pts">{pts(p.points)}</strong>
                {p.last && <span className="ld-yours-last iptv-hint">{p.last.delta >= 0 ? "+" : ""}{pts(p.last.delta)}{p.last.reason ? ` ${p.last.reason}` : ""}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}
