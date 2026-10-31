// One row from the companion's rows v1 manifest (GET /v1/home/carousels?rows=1): items come from Jellyfin in one call
// by `itemIds` (order kept) once the row nears the screen. Layouts: standard 16:9, poster 2:3, franchise.
import { useState } from "react";
import { getItems, type Item } from "../api/jellyfin";
import type { ServerRow } from "../api/companion";
import { go } from "../lib/router";
import { hubIds } from "../api/collections";
import { TrendingRow } from "./TrendingRow";
import { FranchiseRow } from "./FranchiseRow";
import { Row, RowTitle } from "./Row";
import { IptvCard, IptvPoster } from "./IptvTile";
import { PosterCard } from "./PosterCard";
import { GetItSheet } from "./GetItSheet";
import { getSession } from "../api/jellyfin";
import { paddedOrder, type MissingTitle } from "../lib/rows";
import { useIsPhone } from "../lib/device";
import type { VodItem } from "../lib/iptvVod";

/** An IPTV title as a tile in a library row's scroller (My list: beside the library items). */
function IptvTile({ item, poster }: { item: VodItem; poster: boolean }) {
  const phone = useIsPhone();
  return poster ? <IptvPoster item={item} width={phone ? 112 : 150} /> : <IptvCard item={item} />;
}

/** A title from a padded list that is nowhere (library, IPTV): dimmed "Not available"; an admin can open Get it. */
function MissingTile({ m, mediaType, poster }: { m: MissingTitle; mediaType: "movie" | "tv"; poster: boolean }) {
  const [open, setOpen] = useState(false);
  const phone = useIsPhone();
  const admin = !!getSession()?.isAdmin;
  return (
    <>
      <PosterCard
        width={poster ? (phone ? 112 : 150) : phone ? 160 : 200}
        src={m.poster}
        name={m.title}
        faded
        tag="Not available"
        label={`${m.title}, not available`}
        onOpen={admin && m.tmdbId != null ? () => setOpen(true) : undefined}
      />
      {open && <GetItSheet pick={{ title: m.title, year: m.year, tmdbId: m.tmdbId, mediaType, posterUrl: m.poster }} onClose={() => setOpen(false)} />}
    </>
  );
}

/** A source "iptv" row: the person's IPTV titles, already in the manifest (no Jellyfin call). */
function IptvRow({ title, items, poster, autoFocusFirst }: { title: string; items: VodItem[]; poster: boolean; autoFocusFirst?: boolean }) {
  const phone = useIsPhone();
  return (
    <section className={poster ? "row poster-row" : "row"}>
      <RowTitle title={title} />
      <div className="row-scroll">
        {items.map((it, i) =>
          poster ? (
            <IptvPoster key={it.id} item={it} width={phone ? 112 : 150} autoFocus={autoFocusFirst && i === 0} />
          ) : (
            <IptvCard key={it.id} item={it} autoFocus={autoFocusFirst && i === 0} />
          ),
        )}
      </div>
    </section>
  );
}

// Rows that reach the screen together share one Jellyfin call: each /Items request costs the server ~1 s whatever its
// size, and a screen of rows fired in parallel took 4-5 s each. Items are kept per id for a couple of minutes (rows
// repeat titles across pages) but not longer: watched state and resume points change.
const BATCH_MS = 40;
const BATCH_MAX = 120;
const FRESH_MS = 120_000;
const known = new Map<string, { item: Item; at: number }>();
const fresh = (id: string) => {
  const k = known.get(id);
  return k && Date.now() - k.at < FRESH_MS ? k.item : undefined;
};
let pending: { ids: string[]; done: () => void; fail: (e: unknown) => void }[] = [];
let timer: number | null = null;

function flush() {
  timer = null;
  const batch = pending;
  pending = [];
  const ids = [...new Set(batch.flatMap((b) => b.ids))].filter((id) => !fresh(id));
  const chunks: string[][] = [];
  for (let i = 0; i < ids.length; i += BATCH_MAX) chunks.push(ids.slice(i, i + BATCH_MAX));
  Promise.all(chunks.map((c) => getItems({ Ids: c.join(",") }).then((r) => r.Items.forEach((it) => known.set(it.Id, { item: it, at: Date.now() })))))
    .then(() => batch.forEach((b) => b.done()))
    .catch((e) => batch.forEach((b) => b.fail(e)));
}

/** Items for these ids in this order (ids the caller can't see drop out). */
export function itemsByIds(ids: string[]): Promise<Item[]> {
  const pick = () => ids.map((id) => known.get(id)?.item).filter((x): x is Item => !!x);
  if (ids.every((id) => fresh(id))) return Promise.resolve(pick());
  return new Promise<Item[]>((resolve, reject) => {
    pending.push({ ids, done: () => resolve(pick()), fail: reject });
    if (timer == null) timer = window.setTimeout(flush, BATCH_MS);
  });
}
window.addEventListener("jellytip-session", () => known.clear());

export function serverRowElement(r: ServerRow, opts: { onFocusItem?: (item: Item) => void; autoFocusFirst?: boolean } = {}) {
  if (r.id.startsWith("trending-top10")) return <TrendingRow key={r.id} row={r} />;
  if (r.iptvItems.length && !r.itemIds.length && r.layout !== "franchise") return <IptvRow key={r.id} title={r.title} items={r.iptvItems} poster={r.layout === "poster"} autoFocusFirst={opts.autoFocusFirst} />;
  const load = () => itemsByIds(r.itemIds);
  const untitled = r.untitled.length ? new Set(r.untitled) : undefined;
  if (r.layout === "franchise" && r.collection)
    return <FranchiseRow key={r.id} title={r.title} collection={r.collection} load={load} untitled={untitled} iptvItems={r.iptvItems} order={r.order} {...opts} />;
  const person = r.person;
  // My list and other mixed rows: in the server's `order` when it sends one, else library items first, then the IPTV titles
  const iptvExtra = r.iptvItems.map((it) => ({ id: it.id, node: <IptvTile key={it.id} item={it} poster={r.layout === "poster"} /> }));
  // Padded lists also name titles that are nowhere: dimmed tiles at their place in the list (by rank when the list is ranked)
  const missingExtra = r.missing.map((m) => ({ id: `m:${m.tmdbId ?? m.title}`, node: <MissingTile key={`m${m.tmdbId ?? m.title}`} m={m} mediaType={r.mediaType} poster={r.layout === "poster"} /> }));
  const extra = iptvExtra.length || missingExtra.length ? [...iptvExtra, ...missingExtra] : undefined;
  const order = r.missing.length ? paddedOrder(r) : r.order;
  return (
    <Row
      key={r.id}
      title={r.title}
      load={load}
      layout={r.layout === "poster" ? "poster" : "standard"}
      untitled={untitled}
      onTitle={person ? () => go(`/person/${person.id}?name=${encodeURIComponent(person.name)}`) : hubIds()?.has(r.id) ? () => go(`/collections/${encodeURIComponent(r.id)}`) : undefined}
      person={person ? { ...person, count: r.itemIds.length } : null}
      extra={extra}
      order={order}
      {...opts}
    />
  );
}
