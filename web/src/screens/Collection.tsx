// A franchise / collection as a real thing (#/collection/<tmdbId>): the series' backdrop, blurb, progress, every
// film in series order. Parts not in the library are faded posters; admins can "Get it" (POST /v1/request).
import { useEffect, useState } from "react";
import { getItems, type Item } from "../api/jellyfin";
import { collectionPage, type CollectionPage } from "../api/companion";
import { Backdrop } from "../components/Backdrop";
import { PosterCard } from "../components/PosterCard";
import { IptvPoster } from "../components/IptvTile";
import { iptvPart } from "../lib/iptvVod";
import { ProgressBar, progressText, tmdbSized } from "../components/FranchiseRow";
import { GetItSheet } from "../components/GetItSheet";
import { useIsPhone } from "../lib/device";
import { go } from "../lib/router";
import "../components/FranchiseRow.css";

type Missing = CollectionPage["missing"][number];

export function Collection({ tmdbId }: { tmdbId: number }) {
  const [c, setC] = useState<CollectionPage | null | undefined>(undefined);
  const [items, setItems] = useState<Map<string, Item>>(new Map());
  const [getIt, setGetIt] = useState<Missing | null>(null);
  const phone = useIsPhone();

  useEffect(() => {
    let alive = true;
    collectionPage(tmdbId).then((page) => {
      if (!alive) return;
      setC(page);
      if (!page?.itemIds.length) return;
      getItems({ Ids: page.itemIds.join(",") })
        .then((r) => alive && setItems(new Map(r.Items.map((i) => [i.Id, i]))))
        .catch(() => undefined);
    });
    return () => {
      alive = false;
    };
  }, [tmdbId]);

  if (c === undefined) return <div className="details loading" />;
  if (c === null)
    return (
      <div className="details collection-page">
        <p className="empty" style={{ paddingTop: 120 }}>
          This collection isn't available.
        </p>
      </div>
    );

  // Untitled art per the server's check (16:9 thumbs aren't drawn here, but a textless poster still needs a title)
  const textless = new Set(c.art.filter((a) => a.thumb === "textless").map((a) => a.itemId));
  const missing = new Map(c.missing.map((m) => [m.tmdbId, m]));
  const parts = c.partsOrder.map((p, n) => ({ p, n: n + 1, item: p.itemId ? items.get(p.itemId) : undefined, miss: missing.get(p.tmdbId) }));
  const next = parts.find((x) => x.item && !x.item.UserData?.Played)?.item ?? parts.find((x) => x.item)?.item;
  const started = parts.some((x) => x.item?.UserData?.Played);

  return (
    <div className="details collection-page">
      <div className="details-hero">
        <Backdrop url={tmdbSized(c.backdrop, phone ? "w1280" : "original")} focal={null} />
        <div className="scrim-left" />
        <div className="scrim-bottom" />
        <div className="details-text">
          <span className="kicker franchise-kicker">Collection</span>
          <h1 className="page-title collection-title">{c.name}</h1>
          {c.overview && <p className="overview">{c.overview}</p>}
          <span className="franchise-progress">
            <ProgressBar c={c} />
            {progressText(c)}
          </span>
          {next && (
            <div className="buttons">
              <button className="btn primary" data-nav data-autofocus onClick={() => go(`/play/${next.Id}`)}>
                {started ? "Continue with" : "Start with"} {next.Name}
              </button>
            </div>
          )}
        </div>
      </div>
      <section className="details-below collection-parts">
        <h2 className="row-title">In order</h2>
        <div className="poster-grid">
          {parts.map(({ p, n, item, miss }) => {
            if (item)
              return (
                <PosterCard key={p.tmdbId} item={item} width={0} tag={String(n)} untitled={textless.has(item.Id)} onOpen={() => go(`/item/${item.Id}`)} />
              );
            const ip = iptvPart(p);
            if (ip) return <IptvPoster key={p.tmdbId} item={{ ...ip, title: ip.title || p.title, year: ip.year ?? p.year }} width={0} tag={String(n)} />;
            const unreleased = miss?.released === false;
            const canGet = c.canRequest && !unreleased;
            const label = `${p.title}${p.year ? ` (${p.year})` : ""} · ${unreleased ? "not out yet" : "not in the library"}`;
            return (
              <PosterCard
                key={p.tmdbId}
                width={0}
                src={miss?.posterUrl ?? null}
                name={p.title}
                label={label}
                tag={String(n)}
                faded
                onOpen={canGet ? () => setGetIt(miss ?? { tmdbId: p.tmdbId, title: p.title, year: p.year }) : undefined}
              />
            );
          })}
        </div>
      </section>
      {getIt && <GetItSheet pick={getIt} onClose={() => setGetIt(null)} />}
    </div>
  );
}
