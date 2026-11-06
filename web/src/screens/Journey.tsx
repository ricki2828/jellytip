// One journey (#/journeys/<id>): the films in order with watched ticks, a big Play next / Resume, missing films greyed and skipped.
import { useEffect, useState } from "react";
import { journeyDef, journeyEntries } from "../api/journeys";
import { go } from "../lib/router";
import { journeyTitle, nextUp, progress, type Entry, type JourneyDef } from "../lib/journeys";
import "./Journeys.css";

export function Journey({ id }: { id: string }) {
  const [def, setDef] = useState<JourneyDef | null | undefined>(undefined);
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let alive = true;
    setDef(undefined);
    setEntries(null);
    setFailed(false);
    void journeyDef(id).then((d) => {
      if (!alive) return;
      setDef(d);
      if (d) journeyEntries(d).then((e) => alive && setEntries(e), () => alive && setFailed(true));
    });
    return () => {
      alive = false;
    };
  }, [id]);
  if (def === undefined) return <div className="journeys"><p className="empty">Loading…</p></div>;
  if (def === null) return <div className="journeys"><p className="empty">This journey isn't available.</p></div>;
  const next = entries ? nextUp(entries) : null;
  const p = entries ? progress(entries) : null;
  const pct = p && p.playable ? Math.round((p.watched / p.playable) * 100) : 0;
  return (
    <div className="journeys">
      <div className="journey-head">
        <h1 className="page-title">{journeyTitle(def)}</h1>
        {p && (
          <>
            <span className="journey-bar" aria-hidden>
              <span style={{ width: `${pct}%` }} />
            </span>
            <p className="line dim">
              {p.watched} of {p.playable} watched{p.playable < p.total ? ` · ${p.total - p.playable} not in your library` : ""}
            </p>
          </>
        )}
        {next && (
          <div className="buttons">
            <button className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${next.entry.itemId}`)}>
              {next.resume ? "Resume" : "Play next"}: {next.entry.title}
            </button>
          </div>
        )}
        {entries && !next && p && p.playable > 0 && <p className="line dim">All watched.</p>}
        {failed && <p className="empty">Couldn't load your progress.</p>}
      </div>
      <ol className="journey-films">
        {(entries ?? []).map((e) => (
          <li key={e.n}>
            {e.playable ? (
              <button className={`journey-film${next?.entry.n === e.n ? " next" : ""}`} data-nav onClick={() => go(`/item/${e.itemId}`)}>
                <span className="journey-n">{e.n}</span>
                <span className="journey-t">
                  {e.title}
                  {e.year ? ` (${e.year})` : ""}
                </span>
                <span className="journey-state">{e.watched ? "✓ Watched" : e.inProgress ? "In progress" : next?.entry.n === e.n ? "Next up" : ""}</span>
              </button>
            ) : (
              <div className="journey-film missing" aria-disabled="true">
                <span className="journey-n">{e.n}</span>
                <span className="journey-t">
                  {e.title}
                  {e.year ? ` (${e.year})` : ""}
                </span>
                <span className="journey-state">Not in your library</span>
              </div>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
