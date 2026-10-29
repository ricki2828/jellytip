// Movies / Shows: the companion carousels for this media type (Movies also gets the Letterboxd lists), then a sortable grid.
import { useEffect, useRef, useState } from "react";
import { getItems, type Item } from "../api/jellyfin";
import { dropPersonalCaches, manifest, rowsBlocked, serverRows, specials as getSpecials, type Carousel, type LetterboxdRow, type ServerRow, type Special } from "../api/companion";
import { withSpecials } from "../components/specials/Specials";
import { Card } from "../components/Card";
import { Row } from "../components/Row";
import { serverRowElement } from "../components/ServerRow";
import { RowsError } from "../components/RowsError";
import { prefsNow } from "../api/me";
import { carouselLoader, LETTERBOXD_PREFIX, letterboxdLoader } from "./Home";

const PAGE = 60;
const SORTS: [string, string, string][] = [
  ["Recently added", "DateCreated,SortName", "Descending,Ascending"],
  ["A–Z", "SortName", "Ascending"],
  ["Release", "PremiereDate,SortName", "Descending,Ascending"],
  ["Rating", "CommunityRating,SortName", "Descending,Ascending"],
  ["Random", "Random", "Ascending"],
];

export function Library({ kind }: { kind: "movie" | "tv" }) {
  const [carousels, setCarousels] = useState<Carousel[]>([]);
  const [lists, setLists] = useState<LetterboxdRow[]>([]);
  const [sort, setSort] = useState(0);
  const [unwatched, setUnwatched] = useState(false);
  const [items, setItems] = useState<Item[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  // A failed page stops the infinite scroll (else the sentinel re-fires in a loop) until Retry or a sort/filter change
  const [failed, setFailed] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  const gen = useRef(0);

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
        // A 503 (or any failure in a kids profile) must not fall back to the unfiltered carousels
        if (rowsBlocked(kind === "movie" ? "movies" : "shows") || prefsNow()?.kids.enabled) {
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

  const loadPage = async (start: number, g: number) => {
    setLoading(true);
    setFailed(false);
    try {
      const [, by, order] = SORTS[sort];
      // Random re-shuffles on every request, so pages would overlap: take the whole (small) library in one go
      const random = by === "Random";
      const r = await getItems({
        IncludeItemTypes: kind === "movie" ? "Movie" : "Series",
        Recursive: true,
        SortBy: by,
        SortOrder: order,
        StartIndex: random ? 0 : start,
        Limit: random ? 2000 : PAGE,
        Filters: unwatched ? "IsUnplayed" : undefined,
        EnableTotalRecordCount: true,
      });
      if (g !== gen.current) return;
      setItems((prev) => {
        const merged = start === 0 ? r.Items : [...prev, ...r.Items];
        const seen = new Set<string>();
        return merged.filter((i) => !seen.has(i.Id) && !!seen.add(i.Id));
      });
      setTotal(random ? r.Items.length : r.TotalRecordCount ?? null);
    } catch {
      if (g === gen.current) setFailed(true);
    } finally {
      if (g === gen.current) setLoading(false);
    }
  };

  useEffect(() => {
    const g = ++gen.current;
    setItems([]);
    setTotal(null);
    setFailed(false);
    loadPage(0, g);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, sort, unwatched]);

  useEffect(() => {
    const el = sentinel.current;
    if (!el || failed) return;
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((e) => e.isIntersecting) && !loading && total != null && items.length < total) loadPage(items.length, gen.current);
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, total, loading, failed]);

  return (
    <div className="library">
      <h1 className="page-title">{kind === "movie" ? "Movies" : "Shows"}</h1>
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
        <div className="grid-head">
          <h2 className="row-title">
            All {kind === "movie" ? "movies" : "shows"}
            {total != null && <span className="count"> {total}</span>}
          </h2>
          <div className="chips">
            {SORTS.map(([label], i) => (
              <button key={label} data-nav className={`chip${i === sort ? " on" : ""}`} onClick={() => setSort(i)}>
                {label}
              </button>
            ))}
            <button data-nav className={`chip${unwatched ? " on" : ""}`} onClick={() => setUnwatched((u) => !u)}>
              Unwatched
            </button>
          </div>
        </div>
        <div className="grid">
          {items.map((it) => (
            <Card key={it.Id} item={it} width={0} />
          ))}
        </div>
        <div ref={sentinel} className="sentinel">
          {loading && "Loading…"}
          {failed && (
            <span className="load-failed">
              {items.length === 0 ? `Couldn't load ${kind === "movie" ? "movies" : "shows"}.` : "Couldn't load more."}
              <button data-nav className="chip" onClick={() => loadPage(items.length, gen.current)}>
                Retry
              </button>
            </span>
          )}
        </div>
      </section>
    </div>
  );
}
