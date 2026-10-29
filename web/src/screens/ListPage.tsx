// A Letterboxd list (#/list/<slug>): the films of one list the companion tracks, in the list's own order (from the
// carousels manifest, the same data as the Home rows).
import { useEffect, useState } from "react";
import { manifest, type LetterboxdRow } from "../api/companion";
import type { Item } from "../api/jellyfin";
import { Card } from "../components/Card";
import { letterboxdLoader } from "./Home";

export function ListPage({ slug }: { slug: string }) {
  const [list, setList] = useState<LetterboxdRow | null | undefined>(undefined);
  const [items, setItems] = useState<Item[] | null>(null);
  useEffect(() => {
    let alive = true;
    manifest().then((m) => {
      if (!alive) return;
      const l = m?.letterboxd.find((x) => x.slug === slug) ?? null;
      setList(l);
      if (l) letterboxdLoader(l)().then((r) => alive && setItems(r), () => alive && setItems([]));
    });
    return () => {
      alive = false;
    };
  }, [slug]);
  if (list === null) return <p className="empty">This list isn't available.</p>;
  return (
    <div className="search">
      <h1 className="page-title">{list?.name ?? ""}</h1>
      {items && items.length === 0 && <p className="empty">None of this list's films are in the library.</p>}
      <div className="grid">{items ? items.map((it) => <Card key={it.Id} item={it} width={0} />) : null}</div>
    </div>
  );
}
