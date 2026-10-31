// One collection (#/collections/<id>): a poster grid of the full list in its order, library and IPTV tiles together, rank
// badges on ranked lists, "Not available" for titles that are nowhere (admins: Get it). Uncapped, played titles ticked.
import { useEffect, useState } from "react";
import type { Item } from "../api/jellyfin";
import { getSession } from "../api/jellyfin";
import { collectionRow } from "../api/collections";
import type { ServerRow } from "../lib/rows";
import { trendEntries } from "../lib/rows";
import { go } from "../lib/router";
import { useIsPhone } from "../lib/device";
import { itemsByIds } from "../components/ServerRow";
import { PosterCard } from "../components/PosterCard";
import { IptvPoster } from "../components/IptvTile";
import { GetItSheet, type GetItPick } from "../components/GetItSheet";
import "./Collections.css";

export function CollectionRow({ id }: { id: string }) {
  const [row, setRow] = useState<ServerRow | null | undefined>(undefined);
  const [items, setItems] = useState<Map<string, Item> | null>(null);
  const [getIt, setGetIt] = useState<GetItPick | null>(null);
  const phone = useIsPhone();
  useEffect(() => {
    let alive = true;
    setRow(undefined);
    setItems(null);
    void collectionRow(id).then((r) => {
      if (!alive) return;
      setRow(r);
      if (!r || !r.itemIds.length) return setItems(new Map());
      itemsByIds(r.itemIds)
        .then((its) => alive && setItems(new Map(its.map((i) => [i.Id.replace(/-/g, "").toLowerCase(), i]))))
        .catch(() => alive && setItems(new Map()));
    });
    return () => {
      alive = false;
    };
  }, [id]);
  if (row === undefined) return <div className="collections"><p className="empty">Loading…</p></div>;
  if (row === null) return <div className="collections"><p className="empty">This collection isn't available.</p></div>;
  const ranked = Object.keys(row.ranks).length > 0;
  const libIds = row.itemIds.filter((x) => items?.has(x.replace(/-/g, "").toLowerCase()));
  const entries = items ? trendEntries(row, libIds, 1000) : [];
  const admin = !!getSession()?.isAdmin;
  const w = phone ? 104 : 150;
  return (
    <div className="collections">
      <h1 className="page-title">{row.title}</h1>
      {!items && <p className="empty">Loading…</p>}
      <div className="coll-posters">
        {entries.map((e) => {
          const badge = ranked && e.rank < 1000 ? <span className="coll-rank">{e.rank}</span> : null;
          if (e.kind === "lib") {
            const it = items!.get(e.id.replace(/-/g, "").toLowerCase());
            return it ? (
              <span key={`l${it.Id}`} className="coll-poster">
                {badge}
                <PosterCard item={it} width={w} onOpen={() => go(`/item/${it.Id}`)} untitled={row.untitled.includes(it.Id)} />
              </span>
            ) : null;
          }
          if (e.kind === "iptv")
            return (
              <span key={`i${e.item.id}`} className="coll-poster">
                {badge}
                <IptvPoster item={e.item} width={w} />
              </span>
            );
          const m = e.title;
          return (
            <span key={`m${m.tmdbId ?? m.title}`} className="coll-poster">
              {badge}
              <PosterCard
                width={w}
                src={m.poster}
                name={m.title}
                faded
                tag="Not available"
                label={`${m.title}, not available`}
                onOpen={admin && m.tmdbId != null ? () => setGetIt({ title: m.title, year: m.year, tmdbId: m.tmdbId, mediaType: row.mediaType, posterUrl: m.poster }) : undefined}
              />
            </span>
          );
        })}
      </div>
      {getIt && <GetItSheet pick={getIt} onClose={() => setGetIt(null)} />}
    </div>
  );
}
