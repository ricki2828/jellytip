// One journey (#/journeys/<id>): the films in order with watched ticks, a big Play next / Resume, missing films greyed and skipped.
import { useEffect, useRef, useState } from "react";
import { journeyEntries, journeyResult } from "../api/journeys";
import { go } from "../lib/router";
import { journeyTitle, nextUp, progress, type BuildResult, type Entry, type JourneyDef } from "../lib/journeys";
import "./Journeys.css";

export function Journey({ id }: { id: string }) {
  const [res, setRes] = useState<BuildResult | undefined>(undefined);
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [pAttempt, setPAttempt] = useState(0);
  const def = res?.kind === "ok" ? res.def : null;
  useEffect(() => {
    let alive = true;
    setRes(undefined);
    setEntries(null);
    void journeyResult(id).then((r) => alive && setRes(r));
    return () => {
      alive = false;
    };
  }, [id, attempt]);
  useEffect(() => {
    if (!def) return;
    let alive = true;
    setEntries(null);
    setFailed(false);
    journeyEntries(def).then((e) => alive && setEntries(e), () => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [def, pAttempt]);
  const next = entries ? nextUp(entries) : null;
  const playRef = useRef<HTMLButtonElement>(null);
  const hasNext = !!next;
  useEffect(() => {
    if (hasNext) playRef.current?.focus({ preventScroll: true });
  }, [hasNext]);
  if (res === undefined) return <div className="journeys"><p className="empty">Loading…</p></div>;
  if (res.kind === "error")
    return (
      <div className="journeys">
        <p className="empty">Couldn't load this journey.</p>
        <button className="btn" data-nav data-autofocus onClick={() => setAttempt((n) => n + 1)}>Retry</button>
      </div>
    );
  if (res.kind === "short")
    return (
      <div className="journeys">
        <p className="empty">Not enough of their films in your library yet.</p>
        <button className="btn" data-nav data-autofocus onClick={() => go(`/person/${res.personId}`)}>See their films</button>
      </div>
    );
  if (res.kind === "none") return <div className="journeys"><p className="empty">This journey isn't available.</p></div>;
  return view(res.def);

  function view(d: JourneyDef) {
  const next = entries ? nextUp(entries) : null;
  const p = entries ? progress(entries) : null;
  const pct = p && p.playable ? Math.round((p.watched / p.playable) * 100) : 0;
  return (
    <div className="journeys">
      <div className="journey-head">
        <h1 className="page-title">{journeyTitle(d)}</h1>
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
            <button ref={playRef} className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${next.entry.itemId}`)}>
              {next.resume ? "Resume" : "Play next"}: {next.entry.title}
            </button>
          </div>
        )}
        {entries && !next && p && p.playable > 0 && <p className="line dim">All watched.</p>}
        {!entries && !failed && <p className="line dim">Loading your progress…</p>}
        {failed && (
          <p className="empty">
            Couldn't load your progress.{" "}
            <button className="btn small" data-nav onClick={() => setPAttempt((n) => n + 1)}>Retry</button>
          </p>
        )}
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
}
