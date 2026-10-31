// Collections hub (#/collections): section chips across the top, a grid of landscape cards under them. A card opens the
// collection (#/collections/<id>). Contract: ~/ai/plans/jellytip-search-10x.md "C. Collections hub".
import { useEffect, useState } from "react";
import { hubNow, hubUnavailable, loadHub, onHubChange } from "../api/collections";
import { logoSrc } from "../api/iptv";
import { go } from "../lib/router";
import { cardLine, type CollectionCard } from "../lib/collections";
import { safeArt } from "../components/IptvTile";
import "./Collections.css";

function Card({ c }: { c: CollectionCard }) {
  const art = safeArt(c.backdrop ?? c.poster, 480) ?? logoSrc(c.backdrop ?? c.poster, 480);
  const [failed, setFailed] = useState(false);
  return (
    <button className="coll-card" data-nav onClick={() => go(`/collections/${encodeURIComponent(c.id)}`)} aria-label={`${c.title}, ${cardLine(c)}`}>
      {art && !failed && <img src={art} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />}
      <span className="coll-scrim" />
      <span className="coll-text">
        <strong>{c.title}</strong>
        <span>{cardLine(c)}</span>
      </span>
    </button>
  );
}

export function Collections() {
  const [, tick] = useState(0);
  const [key, setKey] = useState<string | null>(null);
  useEffect(() => {
    void loadHub();
    return onHubChange(() => tick((n) => n + 1));
  }, []);
  const sections = hubNow();
  const current = sections?.find((s) => s.key === key) ?? sections?.[0];
  return (
    <div className="collections">
      <h1 className="page-title">Collections</h1>
      {!sections && !hubUnavailable() && <p className="empty">Loading…</p>}
      {(hubUnavailable() || (sections && sections.length === 0)) && <p className="empty">Nothing here yet.</p>}
      {sections && sections.length > 0 && (
        <>
          <div className="chips coll-chips" role="tablist" aria-label="Sections">
            {sections.map((s) => (
              <button key={s.key} role="tab" aria-selected={s.key === current?.key} className={`chip${s.key === current?.key ? " on" : ""}`} data-nav onClick={() => setKey(s.key)}>
                {s.title}
                <span className="livetv-count">{s.count}</span>
              </button>
            ))}
          </div>
          <div className="coll-grid">{current?.items.map((c) => <Card key={c.id} c={c} />)}</div>
        </>
      )}
    </div>
  );
}
