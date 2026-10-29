// Movies / Shows: the companion carousels for this media type (Movies also gets the Letterboxd lists), then a sortable grid.
import { useEffect, useRef, useState } from "react";
import { getItems, type Item } from "../api/jellyfin";
import { manifest, type Carousel, type LetterboxdRow } from "../api/companion";
import { Card } from "../components/Card";
import { Row } from "../components/Row";
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
  const sentinel = useRef<HTMLDivElement>(null);
  const gen = useRef(0);

  useEffect(() => {
    manifest().then((m) => {
      if (!m) return;
      setCarousels(m.carousels.filter((c) => c.mediaType === kind));
      setLists(kind === "movie" ? m.letterboxd : []);
    });
  }, [kind]);

  const loadPage = async (start: number, g: number) => {
    setLoading(true);
    try {
      const [, by, order] = SORTS[sort];
      const r = await getItems({
        IncludeItemTypes: kind === "movie" ? "Movie" : "Series",
        Recursive: true,
        SortBy: by,
        SortOrder: order,
        StartIndex: start,
        Limit: PAGE,
        Filters: unwatched ? "IsUnplayed" : undefined,
        EnableTotalRecordCount: true,
      });
      if (g !== gen.current) return;
      setItems((prev) => (start === 0 ? r.Items : [...prev, ...r.Items]));
      setTotal(r.TotalRecordCount ?? null);
    } finally {
      if (g === gen.current) setLoading(false);
    }
  };

  useEffect(() => {
    const g = ++gen.current;
    setItems([]);
    setTotal(null);
    loadPage(0, g);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, sort, unwatched]);

  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((e) => e.isIntersecting) && !loading && total != null && items.length < total) loadPage(items.length, gen.current);
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length, total, loading]);

  return (
    <div className="library">
      <h1 className="page-title">{kind === "movie" ? "Movies" : "Shows"}</h1>
      {carousels.map((c, i) => (
        <Row key={c.id} title={c.title} load={carouselLoader(c)} autoFocusFirst={i === 0} />
      ))}
      {lists.map((l) => (
        <Row key={l.slug} title={LETTERBOXD_PREFIX + l.name} load={letterboxdLoader(l)} />
      ))}
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
        </div>
      </section>
    </div>
  );
}
