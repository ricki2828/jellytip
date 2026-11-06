// Guided film journeys (#/journeys): short ordered paths grouped by type. Counts come from the collections hub and the
// carousels manifest (no per-journey calls here); the progress lives on each journey's page.
import { useEffect, useMemo, useState } from "react";
import { hubNow, loadHub, onHubChange } from "../api/collections";
import { manifest, type Manifest } from "../api/companion";
import { go } from "../lib/router";
import { summaries, type JourneySummary } from "../lib/journeys";
import "./Journeys.css";

const GROUPS: { key: "franchise" | "director" | "list"; title: string }[] = [
  { key: "franchise", title: "Finish a series" },
  { key: "director", title: "Start with a director" },
  { key: "list", title: "From a list" },
];

export function Journeys() {
  const [, tick] = useState(0);
  const [m, setM] = useState<Manifest | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    void loadHub();
    void manifest().then((x) => alive && setM(x));
    const off = onHubChange(() => tick((n) => n + 1));
    return () => {
      alive = false;
      off();
    };
  }, []);
  const hub = hubNow();
  const groups = useMemo(() => summaries(hub, m?.letterboxd ?? null), [hub, m]);
  const loading = !hub || m === undefined;
  const any = GROUPS.some((g) => groups[g.key].length);
  return (
    <div className="journeys">
      <h1 className="page-title">Journeys</h1>
      {loading && !any && <p className="empty">Loading…</p>}
      {!loading && !any && <p className="empty">No journeys yet.</p>}
      {GROUPS.map((g) =>
        groups[g.key].length ? (
          <section key={g.key} className="journey-group">
            <h2 className="row-title">{g.title}</h2>
            <div className="journey-cards">
              {groups[g.key].map((s: JourneySummary) => (
                <button key={s.id} className="journey-card" data-nav onClick={() => go(`/journeys/${encodeURIComponent(s.id)}`)}>
                  {s.kicker && <span className="journey-kicker">{s.kicker}</span>}
                  {s.title}
                </button>
              ))}
            </div>
          </section>
        ) : null,
      )}
    </div>
  );
}
