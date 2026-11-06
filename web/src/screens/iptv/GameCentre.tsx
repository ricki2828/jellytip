// #/games: Game Centre. Followed teams' games from today and recent days, each with its ways to watch. Spoiler-safe by
// default: no scores, results, art or programme descriptions; scores only behind a two-press reveal (or when "Show scores
// (spoilers)" is on in Settings). Logic in lib/gameCentre.ts.
import { useCallback, useEffect, useMemo, useState } from "react";
import { matchDetail } from "../../api/matchInfo";
import { usePrefs } from "../../api/me";
import { refreshRecs, useRecs } from "../../api/recStore";
import { refreshUpcoming, useUpcoming } from "../../api/upcomingStore";
import { BackArrow } from "../../components/BackArrow";
import { buildGames, gateArmed, gatePress, gateStart, scoreText, scoreVisible, sectionGames, spoilersOn, waysFor, type Game, type Gate } from "../../lib/gameCentre";
import { go } from "../../lib/router";
import "./iptv.css";
import "./gameCentre.css";

function GameCard({ g, now, pref, shown, scoreLine, gate, onPress }: { g: Game; now: number; pref: boolean; shown: boolean; scoreLine: string | null | undefined; gate: Gate; onPress: () => void }) {
  const w = waysFor(g, now);
  const armed = gateArmed(gate, Date.now());
  return (
    <li className={`gc-card ${w.phase}`}>
      <div className="gc-top">
        {w.phase === "live" && <span className="rec-chip rec">LIVE</span>}
        {g.league && <span className="gc-league">{g.league}</span>}
        {g.channel && <span className="gc-league">{g.channel}</span>}
      </div>
      <h3 className="gc-title">{g.title}</h3>
      <p className="gc-note">{w.note}</p>
      {shown && w.phase !== "upcoming" && <p className="gc-score">{scoreLine === undefined ? "Fetching the score…" : scoreLine ?? "No score available"}</p>}
      <div className="gc-actions">
        {w.ways.map((x, i) => (
          <button key={x.kind} className={`btn small${i === 0 ? " primary" : ""}`} data-nav onClick={() => go(x.path)}>
            {x.label}
          </button>
        ))}
        {!pref && w.phase !== "upcoming" && (
          <button className="btn small gc-reveal" data-nav aria-disabled={shown || undefined} onClick={shown ? undefined : onPress} aria-label={shown ? `Score shown for ${g.title}` : `Reveal score for ${g.title}`}>
            {shown ? "Score shown" : armed ? "Press again to reveal" : "Reveal score"}
          </button>
        )}
      </div>
    </li>
  );
}

export function GameCentre() {
  const { prefs } = usePrefs({ defer: true });
  const events = useUpcoming(false);
  const recs = useRecs(false);
  const [now, setNow] = useState(() => Date.now() / 1000);
  const [revealed, setRevealed] = useState<ReadonlySet<string>>(new Set());
  const [scores, setScores] = useState<Record<string, string | null>>({});
  const [gates, setGates] = useState<Record<string, Gate>>({});
  const pref = spoilersOn(prefs);

  useEffect(() => {
    const prev = document.title;
    document.title = "Game Centre";
    void refreshUpcoming(true);
    void refreshRecs(true);
    const t = setInterval(() => setNow(Date.now() / 1000), 30_000);
    // A new session (another person signing in) starts again with everything hidden
    const reset = () => {
      setRevealed(new Set());
      setScores({});
      setGates({});
    };
    window.addEventListener("jellytip-session", reset);
    return () => {
      document.title = prev;
      clearInterval(t);
      window.removeEventListener("jellytip-session", reset);
    };
  }, []);

  const teams = useMemo(() => [...(prefs?.sport.teams ?? []), ...(prefs?.sport.teamRefs.map((r) => r.name) ?? [])], [prefs]);
  const games = useMemo(() => buildGames(events, recs, teams, now), [events, recs, teams, now]);
  const sections = useMemo(() => sectionGames(games, now), [games, now]);

  const press = useCallback(
    (g: Game) => {
      const r = gatePress(gates[g.key] ?? gateStart, Date.now());
      setGates((x) => ({ ...x, [g.key]: r.gate }));
      if (!r.revealed) {
        // The armed label lapses by itself
        window.setTimeout(() => setNow(Date.now() / 1000), 4100);
        return;
      }
      setRevealed((s) => new Set(s).add(g.key));
      const fromFixture = scoreText(g.fixture);
      if (fromFixture || !g.fixture?.id) return setScores((s) => ({ ...s, [g.key]: fromFixture }));
      void matchDetail(g.fixture.id, true).then((d) => setScores((s) => ({ ...s, [g.key]: d && d !== "missing" ? scoreText(d.fixture) : null })));
    },
    [gates],
  );

  return (
    <div className="iptv-page gc-page">
      <div className="page-head">
        <BackArrow />
        <h1 className="page-title">Game Centre</h1>
      </div>
      <p className="iptv-hint">{pref ? "Scores are on (Settings → Live sport)." : "Spoiler-safe: no scores or results on this screen."}</p>
      {(events === null || recs === null) && games.length === 0 && <p className="iptv-hint">Loading…</p>}
      {events !== null && games.length === 0 && <p className="iptv-hint">{teams.length ? "No games for your teams in the last few days or coming up." : "Follow a team in Settings → Live sport and its games show up here."}</p>}
      {sections.map((s) => (
        <section key={s.day} className="row">
          <h2 className="row-title">{s.label}</h2>
          <ul className="gc-list">
            {s.games.map((g) => (
              <GameCard key={g.key} g={g} now={now} pref={pref} shown={scoreVisible(pref, revealed, g.key)} scoreLine={pref ? scoreText(g.fixture) : scores[g.key]} gate={gates[g.key] ?? gateStart} onPress={() => press(g)} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
