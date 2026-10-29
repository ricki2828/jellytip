// Your taste (#/taste): what the person watches most (GET /v1/me/taste) — genres, directors, decades, countries as
// share bars, their top rated titles, and how much they've watched.
import { useEffect, useState } from "react";
import { taste as getTaste } from "../api/me";
import type { Item } from "../api/jellyfin";
import type { Share, Taste as TasteData } from "../lib/prefs";
import { itemsByIds } from "../components/ServerRow";
import { Row } from "../components/Row";

function Bars({ title, list }: { title: string; list: Share[] }) {
  if (!list.length) return null;
  const top = list.slice(0, 8);
  const max = top[0].share;
  return (
    <section className="taste-card">
      <h2>{title}</h2>
      <ul>
        {top.map((s) => (
          <li key={s.name}>
            <span className="taste-name">{s.name}</span>
            <span className="taste-bar" aria-hidden>
              <span style={{ width: `${Math.max(4, (s.share / max) * 100)}%` }} />
            </span>
            <span className="taste-pct">{Math.round(s.share * 100)}%</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function Taste() {
  const [t, setT] = useState<TasteData | null | undefined>(undefined);
  const [status, setStatus] = useState(0);
  const [top, setTop] = useState<Item[] | null>(null);
  useEffect(() => {
    let alive = true;
    getTaste().then((r) => {
      if (!alive) return;
      setStatus(r.status);
      setT(r.taste);
      if (r.taste?.topRated.length) itemsByIds(r.taste.topRated).then((x) => alive && setTop(x), () => alive && setTop([]));
    });
    return () => {
      alive = false;
    };
  }, []);

  if (t === undefined) return <div className="taste-page" />;
  if (!t)
    return (
      <div className="taste-page">
        <h1 className="page-title">Your taste</h1>
        <p className="empty">{status === 404 ? "Not available on this server yet." : "Couldn't load your taste right now."}</p>
      </div>
    );
  const empty = !t.watchedCount && !t.genres.length;
  return (
    <div className="taste-page">
      <h1 className="page-title">Your taste</h1>
      <p className="taste-stats">
        <strong>{t.watchedCount.toLocaleString()}</strong> watched · <strong>{Math.round(t.hours).toLocaleString()}</strong> hours
      </p>
      {empty && <p className="empty">Watch a few things and this fills in.</p>}
      <div className="taste-grid">
        <Bars title="Genres" list={t.genres} />
        <Bars title="Directors" list={t.directors} />
        <Bars title="Decades" list={t.decades} />
        <Bars title="Countries" list={t.countries} />
      </div>
      {top && top.length > 0 && <Row title="Your top rated" items={top} layout="poster" />}
    </div>
  );
}
