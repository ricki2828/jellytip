// Matchup-first summary at the top of the Fantasy page: my score vs the opponent's, the projection when the server sent one, and the source age.
import { ageLabel, matchupSummaries } from "../lib/fantasyGame";
import type { FantasyLive } from "../lib/fantasy";

export function FantasySummary({ live, age, stale }: { live: FantasyLive; age: number | null; stale: boolean }) {
  const rows = matchupSummaries(live);
  if (!rows.length) return null;
  const label = ageLabel(age);
  return (
    <section className="fantasy-summary" aria-label="Matchup summary">
      <ul>
        {rows.map((m) => (
          <li key={m.id} className={`fantasy-sum-${m.lead}`}>
            <span className="fantasy-sum-league">{m.name}</span>
            <span className="fantasy-sum-score"><strong className="fantasy-number">{m.me.points.toFixed(1)}</strong> – <strong className="fantasy-number">{m.opp.points.toFixed(1)}</strong></span>
            <span className="fantasy-sum-teams">{m.me.team} vs {m.opp.team}</span>
            <span className="fantasy-sum-state">{m.lead === "tie" ? "Tied" : `${m.lead === "me" ? "You lead" : "You trail"} by ${m.diff.toFixed(1)}`}{m.liveCount > 0 ? ` · ${m.liveCount} of your players live` : ""}</span>
            {m.projected != null && <span className="fantasy-sum-proj">Your projection {m.projected.toFixed(1)}</span>}
          </li>
        ))}
      </ul>
      <p className={stale ? "fantasy-sum-age stale" : "fantasy-sum-age"}>{label ?? "update time unavailable"}</p>
    </section>
  );
}
