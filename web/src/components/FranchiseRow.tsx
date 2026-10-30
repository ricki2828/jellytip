// Franchise / collection row: taller, the series' own backdrop behind the whole row, name + blurb + progress, the
// films in series order. The header opens the collection page (#/collection/<tmdbId>).
import { useRef } from "react";
import type { Item } from "../api/jellyfin";
import type { Collection } from "../api/companion";
import { useIsPhone } from "../lib/device";
import { go } from "../lib/router";
import { Card } from "./Card";
import { useLazyItems } from "./Row";
import { IptvCard } from "./IptvTile";
import { iptvPart, type IptvPart, type VodItem } from "../lib/iptvVod";
import "./FranchiseRow.css";

const CARD_W = 320;
const PHONE_CARD_W = 240;

/** TMDB images come as /original/: rows and phones take a smaller rendition. */
export function tmdbSized(url: string | null | undefined, size: "w780" | "w1280" | "original"): string | null {
  if (!url) return null;
  return url.replace(/\/t\/p\/(original|w\d+)\//, `/t/p/${size}/`);
}

export function progressText(c: Pick<Collection, "watchedCount" | "total" | "inLibrary">): string {
  const watched = `${c.watchedCount} of ${c.total} watched`;
  return c.inLibrary < c.total ? `${watched} · ${c.inLibrary} in the library` : watched;
}

export function ProgressBar({ c }: { c: Pick<Collection, "watchedCount" | "total"> }) {
  const pct = c.total ? Math.min(100, Math.round((c.watchedCount / c.total) * 100)) : 0;
  return (
    <span className="franchise-bar" aria-hidden>
      <span style={{ width: `${pct}%` }} />
    </span>
  );
}

interface Props {
  title: string;
  collection: Collection;
  load: () => Promise<Item[]>;
  untitled?: Set<string>;
  onFocusItem?: (item: Item) => void;
  autoFocusFirst?: boolean;
  /** Row-level IPTV titles with `order` (library ids undashed, vod ids) in part order: the server's own slotting. */
  iptvItems?: VodItem[];
  order?: string[];
}

export function FranchiseRow({ title, collection: c, load, untitled, onFocusItem, autoFocusFirst, iptvItems, order }: Props) {
  const ref = useRef<HTMLElement>(null);
  const items = useLazyItems(ref, undefined, load);
  const phone = useIsPhone();
  const width = phone ? PHONE_CARD_W : CARD_W;
  const backdrop = tmdbSized(c.backdrop, phone ? "w780" : "w1280");
  // Part numbers follow the series, not the row (a missing first film still makes the next one "2")
  const partNo = new Map(c.partsOrder.filter((p) => p.itemId).map((p) => [p.itemId!, c.partsOrder.indexOf(p) + 1]));
  const open = () => go(`/collection/${c.tmdbId}`);

  // Parts the library lacks but the person's IPTV has slot in at their place in the series (dot = via IPTV)
  const iptv = c.partsOrder.map((p, i) => ({ part: iptvPart(p), n: i + 1 })).filter((x): x is { part: IptvPart; n: number } => !!x.part);
  const undash = (id: string) => id.replace(/-/g, "").toLowerCase();
  const tiles: ({ item: Item } | { part: IptvPart; n: number })[] | null = items
    ? iptvItems?.length && order?.length
      ? (() => {
          const lib = new Map(items.map((it) => [undash(it.Id), it]));
          const vod = new Map(iptvItems.map((v) => [undash(v.id), v]));
          const out: ({ item: Item } | { part: IptvPart; n: number })[] = [];
          order.forEach((id, i) => {
            const it = lib.get(undash(id));
            if (it) {
              out.push({ item: it });
              lib.delete(undash(id));
              return;
            }
            const v = vod.get(undash(id));
            if (v) {
              out.push({ part: v as IptvPart, n: i + 1 });
              vod.delete(undash(id));
            }
          });
          // Anything the order didn't name goes last
          return [...out, ...[...lib.values()].map((item) => ({ item })), ...[...vod.values()].map((v) => ({ part: v as IptvPart, n: 0 }))];
        })()
      : iptv.length
      ? (() => {
          const byId = new Map(items.map((it) => [it.Id, it]));
          const out: ({ item: Item } | { part: IptvPart; n: number })[] = [];
          c.partsOrder.forEach((p, i) => {
            const it = p.itemId ? byId.get(p.itemId) : undefined;
            if (it) {
              out.push({ item: it });
              byId.delete(it.Id);
            } else {
              const ip = iptvPart(p);
              if (ip) out.push({ part: ip, n: i + 1 });
            }
          });
          return [...out, ...[...byId.values()].map((item) => ({ item }))];
        })()
      : items.map((item) => ({ item }))
    : null;

  if (tiles && tiles.length === 0) return null;
  return (
    <section className="row franchise-row" ref={ref}>
      {backdrop && <img className="franchise-backdrop" src={backdrop} alt="" decoding="async" loading="lazy" />}
      <div className="franchise-scrim" />
      <div className="franchise-head">
        <span className="kicker">Franchise</span>
        <h2 className="row-title">
          <button className="row-title-link" data-nav onClick={open} aria-label={`${title}: the whole series`}>
            {title}
            <span className="row-title-more" aria-hidden>
              ›
            </span>
          </button>
        </h2>
        {c.overview && <p className="franchise-blurb">{c.overview}</p>}
        <span className="franchise-progress">
          <ProgressBar c={c} />
          {progressText(c)}
        </span>
      </div>
      <div className="row-scroll">
        {tiles
          ? tiles.map((t, i) =>
              "part" in t ? (
                <IptvCard key={t.part.id} item={{ ...t.part, quality: t.part.quality ?? [] }} width={width} tag={t.n ? String(t.n) : undefined} />
              ) : (
                <Card
                  key={t.item.Id}
                  item={t.item}
                  width={width}
                  onFocusItem={onFocusItem}
                  autoFocus={autoFocusFirst && i === 0}
                  untitled={untitled?.has(t.item.Id)}
                  tag={partNo.has(t.item.Id) ? String(partNo.get(t.item.Id)) : undefined}
                />
              ),
            )
          : Array.from({ length: 4 }, (_, i) => <div key={i} className="card card-skeleton" style={{ width, height: Math.round((width * 9) / 16) }} />)}
      </div>
    </section>
  );
}
