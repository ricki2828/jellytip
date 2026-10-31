// Collections hub (#/collections): section chips across the top, a grid of landscape cards under them. A card opens the
// collection (#/collections/<id>). Contract: ~/ai/plans/jellytip-search-10x.md "C. Collections hub".
import { useEffect, useRef, useState } from "react";
import { imageUrl } from "../api/jellyfin";
import { hubNow, hubUnavailable, loadHub, onHubChange } from "../api/collections";
import { logoSrc } from "../api/iptv";
import { go } from "../lib/router";
import { cardLine, type CollectionCard } from "../lib/collections";
import { safeArt } from "../components/IptvTile";
import "./Collections.css";

/** Art for a card: the companion's own (TMDB / proxied) first, else the first library title's backdrop, else the person's portrait. */
function cardArt(c: CollectionCard): { src: string | null; portrait: boolean } {
  const own = safeArt(c.backdrop ?? c.poster, 480) ?? logoSrc(c.backdrop ?? c.poster, 480);
  if (own) return { src: own, portrait: false };
  if (c.itemId) return { src: imageUrl(c.itemId, "Backdrop", { fillWidth: 480, quality: 80 }), portrait: false };
  if (c.person?.imageTag) return { src: imageUrl(c.person.id, "Primary", { tag: c.person.imageTag, fillWidth: 480, quality: 80 }), portrait: true };
  return { src: null, portrait: false };
}

function Card({ c }: { c: CollectionCard }) {
  const { src: art, portrait } = cardArt(c);
  const [failed, setFailed] = useState(false);
  return (
    <button className="coll-card" data-nav onClick={() => go(`/collections/${encodeURIComponent(c.id)}`)} aria-label={`${c.title}, ${cardLine(c)}`}>
      {art && !failed && <img className={portrait ? "portrait" : undefined} src={art} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />}
      <span className="coll-scrim" />
      <span className="coll-text">
        <strong>{c.title}</strong>
        <span>{cardLine(c)}</span>
      </span>
    </button>
  );
}

const STEP = 36;

/** Render a long grid in steps: more cards as the end comes into view (franchises alone are ~300 cards). */
function useProgressive(total: number, reset: string) {
  const [shown, setShown] = useState(STEP);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => setShown(STEP), [reset]);
  useEffect(() => {
    const el = end.current;
    if (!el || shown >= total) return;
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && setShown((n) => n + STEP), { rootMargin: "800px 0px" });
    io.observe(el);
    return () => io.disconnect();
  }, [shown, total]);
  return { shown, end };
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
  const { shown, end } = useProgressive(current?.items.length ?? 0, current?.key ?? "");
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
          <div className="coll-grid">{current?.items.slice(0, shown).map((c) => <Card key={c.id} c={c} />)}</div>
          <div ref={end} aria-hidden />
        </>
      )}
    </div>
  );
}
