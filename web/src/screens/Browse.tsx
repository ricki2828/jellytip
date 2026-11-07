// #/movies/all and #/shows/all: the full filterable, sortable grid. Filters live in the URL (shareable) and in sessionStorage.
import { useEffect, useRef, useState } from "react";
import { getItems, uid, type Item } from "../api/jellyfin";
import { Card } from "../components/Card";
import { LibraryFilterBar } from "../components/LibraryFilterBar";
import { go } from "../lib/router";
import { filtersFromParams, filtersToParams, isFiltered, loadFilters, needsClientFilter, runtimeOk, saveFilters, sortFor, toQuery, type Kind, type LibraryFilters } from "../lib/libraryFilters";

const store = (): Storage | null => {
  try {
    return typeof sessionStorage === "undefined" ? null : sessionStorage;
  } catch {
    return null;
  }
};

const base = (kind: Kind) => (kind === "movie" ? "/movies/all" : "/shows/all");
const MIN_PAGE_FILL = 24; // client-side filters keep fetching until a page has this many
const MAX_FETCHES = 10;
const SCAN_STEP = 600; // runtime bands scan at most this many titles before asking "Load more"

export function Browse({ kind, query }: { kind: Kind; query: URLSearchParams }) {
  const user = uid();
  const noun = kind === "movie" ? "movies" : "shows";
  const [filters, setFiltersState] = useState<LibraryFilters>(() => filtersFromParams(query) ?? loadFilters(store(), user, kind));
  const setFilters = (f: LibraryFilters) => {
    saveFilters(store(), user, kind, f);
    setFiltersState(f);
    const q = filtersToParams(f).toString();
    go(`${base(kind)}${q ? `?${q}` : ""}`, true);
  };
  // Opened without filters in the link but with saved ones: put them in the URL so it can be shared as it looks
  useEffect(() => {
    if (!filtersFromParams(query)) {
      const q = filtersToParams(filters).toString();
      if (q) go(`${base(kind)}?${q}`, true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // A different filtered link opened while this screen is up (pasted, or Back/Forward)
  const qKey = query.toString();
  useEffect(() => {
    const f = filtersFromParams(query);
    if (f && JSON.stringify(f) !== JSON.stringify(filters)) {
      saveFilters(store(), user, kind, f);
      setFiltersState(f);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qKey]);

  const filterKey = JSON.stringify([user, filters]);
  const [items, setItems] = useState<Item[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  // A failed page stops the infinite scroll (else the sentinel re-fires in a loop) until Retry or a sort/filter change
  const [failed, setFailed] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  const gen = useRef(0);
  const scanLimit = useRef(SCAN_STEP);
  const [capped, setCapped] = useState(false);
  const next = useRef(0); // server index of the next page (items.length differs once a client-side filter drops rows)
  const client = needsClientFilter(kind, filters);

  const loadPage = async (g: number) => {
    setLoading(true);
    setFailed(false);
    try {
      // Random re-shuffles on every request, so pages would overlap: toQuery takes the whole (small) library in one go
      const random = sortFor(filters.sort).by === "Random";
      let kept: Item[] = [];
      let serverTotal = 0;
      let emptyPage = false;
      for (let n = 0; n < MAX_FETCHES; n++) {
        const r = await getItems(toQuery(kind, filters, next.current));
        if (g !== gen.current) return;
        next.current += r.Items.length;
        serverTotal = random ? next.current : r.TotalRecordCount ?? next.current;
        kept = kept.concat(client ? r.Items.filter((i) => runtimeOk(filters.runtime, i.RunTimeTicks)) : r.Items);
        emptyPage = r.Items.length === 0;
        if (!client || kept.length >= MIN_PAGE_FILL || next.current >= serverTotal || emptyPage || next.current >= scanLimit.current) break;
      }
      const more = next.current < serverTotal && !random && !emptyPage;
      setHasMore(more);
      setCapped(client && more && next.current >= scanLimit.current);
      setItems((prev) => {
        const merged = [...prev, ...kept];
        const seen = new Set<string>();
        return merged.filter((i) => !seen.has(i.Id) && !!seen.add(i.Id));
      });
      setTotal(client ? null : serverTotal);
    } catch {
      if (g === gen.current) setFailed(true);
    } finally {
      if (g === gen.current) setLoading(false);
    }
  };

  useEffect(() => {
    const g = ++gen.current;
    next.current = 0;
    scanLimit.current = SCAN_STEP;
    setCapped(false);
    setItems([]);
    setTotal(null);
    setHasMore(false);
    setFailed(false);
    loadPage(g);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, filterKey]);

  useEffect(() => {
    const el = sentinel.current;
    if (!el || failed || !hasMore || capped) return;
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((e) => e.isIntersecting) && !loading) loadPage(gen.current);
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, hasMore, loading, failed, capped]);

  const count = total != null ? String(total) : items.length ? `${items.length}${hasMore ? "+" : ""}` : "";
  return (
    <div className="library browse">
      <h1 className="page-title">
        All {noun}
        {count && <span className="count"> {count}</span>}
      </h1>
      <div className="browse-bar">
        <LibraryFilterBar kind={kind} value={filters} onChange={setFilters} />
      </div>
      <section className="grid-section">
        {client && <p className="dim browse-note">Runtime filters the {noun} as they load, so the count grows while you scroll.</p>}
        {!loading && !failed && items.length === 0 && !hasMore && <p className="empty">{isFiltered(filters) ? `Nothing in your Jellyfin library matches these filters.` : "Nothing here yet."}</p>}
        <div className="grid">
          {items.map((it) => (
            <Card key={it.Id} item={it} width={0} />
          ))}
        </div>
        <div ref={sentinel} className="sentinel">
          {loading && "Loading…"}
          {capped && !loading && (
            <span className="load-failed">
              Showing matches from the first {scanLimit.current} titles
              <button
                data-nav
                className="chip"
                onClick={() => {
                  scanLimit.current += SCAN_STEP;
                  setCapped(false);
                  loadPage(gen.current);
                }}
              >
                Load more
              </button>
            </span>
          )}
          {failed && (
            <span className="load-failed">
              {items.length === 0 ? `Couldn't load ${noun}.` : "Couldn't load more."}
              <button data-nav className="chip" onClick={() => loadPage(gen.current)}>
                Retry
              </button>
            </span>
          )}
        </div>
      </section>
    </div>
  );
}
