// One row from the companion's rows v1 manifest (GET /v1/home/carousels?rows=1): items come from Jellyfin in one call
// by `itemIds` (order kept) once the row nears the screen. Layouts: standard 16:9, poster 2:3, franchise.
import { getItems, type Item } from "../api/jellyfin";
import type { ServerRow } from "../api/companion";
import { go } from "../lib/router";
import { FranchiseRow } from "./FranchiseRow";
import { Row } from "./Row";

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
  const load = () => itemsByIds(r.itemIds);
  const untitled = r.untitled.length ? new Set(r.untitled) : undefined;
  if (r.layout === "franchise" && r.collection)
    return <FranchiseRow key={r.id} title={r.title} collection={r.collection} load={load} untitled={untitled} {...opts} />;
  const person = r.person;
  return (
    <Row
      key={r.id}
      title={r.title}
      load={load}
      layout={r.layout === "poster" ? "poster" : "standard"}
      untitled={untitled}
      onTitle={person ? () => go(`/person/${person.id}?name=${encodeURIComponent(person.name)}`) : undefined}
      person={person ? { ...person, count: r.itemIds.length } : null}
      {...opts}
    />
  );
}
