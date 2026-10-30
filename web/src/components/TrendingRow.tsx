// "Top 10 trending" (trending-top10-series / -movies): the Top 10 look (big rank numerals behind posters) over library,
// IPTV and not-available tiles in rank order. A missing tile is dimmed "Not available": admins open Get it, anyone else
// can't focus it.
import { useEffect, useState } from "react";
import { getSession, type Item } from "../api/jellyfin";
import type { ServerRow } from "../api/companion";
import { go } from "../lib/router";
import { trendEntries } from "../lib/rows";
import { useIsPhone } from "../lib/device";
import { itemsByIds } from "./ServerRow";
import { RowTitle } from "./Row";
import { PosterCard } from "./PosterCard";
import { IptvPoster } from "./IptvTile";
import { GetItSheet, type GetItPick } from "./GetItSheet";
import "./specials/specials.css";

export function TrendingRow({ row }: { row: ServerRow }) {
  const phone = useIsPhone();
  const [items, setItems] = useState<Item[] | null>(row.itemIds.length ? null : []);
  const [getIt, setGetIt] = useState<GetItPick | null>(null);
  useEffect(() => {
    let alive = true;
    if (row.itemIds.length) itemsByIds(row.itemIds).then((r) => alive && setItems(r), () => alive && setItems([]));
    return () => {
      alive = false;
    };
  }, [row.itemIds]);
  if (!items) return null;
  const byId = new Map(items.map((i) => [i.Id.replace(/-/g, "").toLowerCase(), i]));
  const entries = trendEntries(row, items.map((i) => i.Id));
  if (!entries.length) return null;
  const admin = !!getSession()?.isAdmin;
  const width = phone ? 112 : 140;
  return (
    <section className="special row top10">
      <RowTitle title={row.title} />
      <div className="row-scroll">
        {entries.map((e) => (
          <div key={e.kind === "lib" ? e.id : e.kind === "iptv" ? e.item.id : `m${e.title.tmdbId}`} className="top10-entry">
            <span className="top10-num" aria-hidden>
              {e.rank}
            </span>
            {e.kind === "lib" ? (
              (() => {
                const i = byId.get(e.id.replace(/-/g, "").toLowerCase());
                return i ? <PosterCard item={i} width={width} onOpen={() => go(`/item/${i.Id}`)} label={`${e.rank}. ${i.Name}`} untitled={row.untitled.includes(i.Id)} /> : null;
              })()
            ) : e.kind === "iptv" ? (
              <IptvPoster item={e.item} width={width} />
            ) : (
              <PosterCard
                width={width}
                src={e.title.poster}
                name={e.title.title}
                faded
                tag="Not available"
                label={`${e.rank}. ${e.title.title}, not available`}
                onOpen={admin ? () => setGetIt({ title: e.title.title, year: e.title.year, tmdbId: e.title.tmdbId, mediaType: row.mediaType, posterUrl: e.title.poster }) : undefined}
              />
            )}
          </div>
        ))}
      </div>
      {getIt && <GetItSheet pick={getIt} onClose={() => setGetIt(null)} />}
    </section>
  );
}
