// "More like this" row: the companion's mix of library and IPTV titles, drawn by the same code as the Home rows.
// `fallback` (library Details) loads the Jellyfin list when the route errors or comes back empty; without it the row hides.
import { useEffect, useState } from "react";
import { similarRow } from "../api/similar";
import type { SimilarQuery } from "../lib/similar";
import type { Item } from "../api/jellyfin";
import type { ServerRow } from "../lib/rows";
import { serverRowElement } from "./ServerRow";
import { Row } from "./Row";

export function SimilarRow({ query, fallback, wrapClass }: { query: SimilarQuery; fallback?: () => Promise<Item[]>; wrapClass?: string }) {
  const { kind, tmdb, iptvId, jfId } = query;
  const [row, setRow] = useState<ServerRow | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    setRow(undefined);
    similarRow({ kind, tmdb, iptvId, jfId }).then((r) => alive && setRow(r));
    return () => {
      alive = false;
    };
  }, [kind, tmdb, iptvId, jfId]);
  if (row === undefined) return null;
  const inner = row ? serverRowElement(row) : fallback ? <Row title="More like this" load={fallback} /> : null;
  return inner && wrapClass ? <div className={wrapClass}>{inner}</div> : inner;
}
