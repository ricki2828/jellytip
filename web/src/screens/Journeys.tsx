// #/journeys (Journeys v1, server-built, per person; kids filtering is the server's): the hero is tonight's step of the active
// journey, else the top of Yours, Start or Finish; the path strip under it; then the rows Yours / Finish / Start in that order.
// Back paints the session memo (api/journeysV1.ts) with no request and no re-rank; the router puts scroll and row offsets back.
import { useJourney, useJourneysHome } from "../api/journeysV1";
import { isHomeEmpty, rowOf, type JvHome } from "../lib/journeysV1";
import { JourneyCard, JourneyHero, PathStrip } from "../components/JourneyHero";
import "./Journeys.css";

const ROWS: { key: "yours" | "finish" | "start"; title: string }[] = [
  { key: "yours", title: "Your journeys" },
  { key: "finish", title: "Finish what you started" },
  { key: "start", title: "Start something new" },
];

const heroIdOf = (h: JvHome | null): string | null => (h ? h.active ?? h.yours[0] ?? h.start[0] ?? h.finish[0] ?? null : null);

export function Journeys() {
  const L = useJourneysHome();
  const home = L.value;
  const heroId = heroIdOf(home);
  const hj = useJourney(heroId);
  if (!home) {
    return (
      <div className="journeys jv-page">
        <h1 className="page-title">Journeys</h1>
        {L.missing ? (
          <p className="empty">Journeys aren't available.</p>
        ) : L.failures > 0 ? (
          <>
            <p className="empty">Couldn't load your journeys. Trying again…</p>
            <button className="btn jv-btn" data-nav data-autofocus onClick={L.retryNow}>
              Retry now
            </button>
          </>
        ) : (
          <p className="empty">Loading…</p>
        )}
      </div>
    );
  }
  if (isHomeEmpty(home))
    return (
      <div className="journeys jv-page">
        <h1 className="page-title">Journeys</h1>
        <p className="empty">No journeys yet. They're built overnight from your lists and films.</p>
      </div>
    );
  const heroS = heroId ? home.journeys[heroId] : null;
  const j = hj.value && heroId && hj.value.summary.id === heroId ? hj.value : null;
  return (
    <div className="jv-home">
      {heroS && <JourneyHero s={j?.summary ?? heroS} j={j} />}
      <div className="rows jv-rows">
        {j && <PathStrip j={j} />}
        {ROWS.map((r) => {
          const cards = rowOf(home, home[r.key]);
          if (!cards.length) return null;
          return (
            <section key={r.key} className="row jv-row">
              <h2 className="row-title">{r.title}</h2>
              <div className="row-scroll">
                {cards.map((s) => (
                  <JourneyCard key={s.id} s={s} />
                ))}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
