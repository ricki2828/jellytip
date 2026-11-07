// Movies / Shows: the companion carousels for this media type (Movies also gets the Letterboxd lists), then a sortable grid.
import { useEffect, useState } from "react";
import { getItems, uid } from "../api/jellyfin";
import { go } from "../lib/router";
import { useHubTick } from "../api/collections";
import { dropPersonalCaches, manifest, rowsBlocked, serverRows, specials as getSpecials, type Carousel, type LetterboxdRow, type ServerRow, type Special } from "../api/companion";
import { withSpecials } from "../components/specials/Specials";
import { Row } from "../components/Row";
import { serverRowElement } from "../components/ServerRow";
import { RowsError } from "../components/RowsError";
import { carouselLoader, LETTERBOXD_PREFIX, letterboxdLoader } from "./Home";

export function Library({ kind }: { kind: "movie" | "tv" }) {
  useHubTick();
  const [carousels, setCarousels] = useState<Carousel[]>([]);
  const [lists, setLists] = useState<LetterboxdRow[]>([]);
  // Just the count, for the "Browse all N" entry points (the grid itself lives at #/movies/all)
  const user = uid();
  const [total, setTotal] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    setTotal(null);
    getItems({ IncludeItemTypes: kind === "movie" ? "Movie" : "Series", Recursive: true, Limit: 0, EnableTotalRecordCount: true, Fields: "" })
      .then((r) => alive && setTotal(r.TotalRecordCount ?? null))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [kind, user]);
  const allPath = kind === "movie" ? "/movies/all" : "/shows/all";

  const [rowsReady, setRowsReady] = useState(false);
  // Rows v1 from the companion when it has them; null = the Kometa carousels (+ Letterboxd lists) as before
  const [rows, setRows] = useState<ServerRow[] | null>(null);
  const [specials, setSpecials] = useState<Special[]>([]);
  const [rowsFailed, setRowsFailed] = useState(false);
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let alive = true;
    setRows(null);
    setRowsFailed(false);
    setRowsReady(false);
    serverRows(kind === "movie" ? "movies" : "shows")
      .then((r) => {
        if (r?.length) return r;
        // A 503 (the server couldn't tell who is asking) must not fall back to the unpersonalised carousels
        if (rowsBlocked(kind === "movie" ? "movies" : "shows")) {
          if (alive) setRowsFailed(true);
          return null;
        }
        return manifest().then((m) => {
          if (alive && m) {
            setCarousels(m.carousels.filter((c) => c.mediaType === kind));
            setLists(kind === "movie" ? m.letterboxd : []);
          }
          return null;
        });
      })
      .then((r) => {
        if (!alive) return;
        setRows(r);
        setRowsReady(true);
      });
    getSpecials(kind === "movie" ? "movies" : "shows").then((s) => alive && setSpecials(s));
    return () => {
      alive = false;
    };
  }, [kind, reload]);

  return (
    <div className="library">
      <div className="lib-title-row">
        <h1 className="page-title">{kind === "movie" ? "Movies" : "Shows"}</h1>
        <button data-nav className="btn browse-all" onClick={() => go(allPath)}>
          Browse all{total != null ? ` ${total.toLocaleString()}` : ""} ›
        </button>
      </div>
      {rowsFailed && (
        <RowsError
          onRetry={() => {
            dropPersonalCaches();
            setReload((n) => n + 1);
          }}
        />
      )}
      {!rowsFailed && withSpecials(
        rows
          ? rows.map((r, i) => serverRowElement(r, { autoFocusFirst: i === 0 }))
          : [
              ...carousels.map((c, i) => <Row key={c.id} title={c.title} load={carouselLoader(c)} autoFocusFirst={i === 0} />),
              ...lists.map((l) => <Row key={l.slug} title={LETTERBOXD_PREFIX + l.name} load={letterboxdLoader(l)} />),
            ],
        rowsReady ? specials : [],
      )}
      <section className="grid-section">
        <button data-nav className="browse-all-card" onClick={() => go(allPath)}>
          <span className="browse-all-title">Browse all{total != null ? ` ${total.toLocaleString()}` : ""} {kind === "movie" ? "films" : "shows"}</span>
          <span className="browse-all-sub">Sort by rating, critic score, runtime or last watched. Filter by decade, studio, cast and more.</span>
        </button>
      </section>
    </div>
  );
}
