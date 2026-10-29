// Discover (admins only; the server refuses anyone else): what isn't in the library yet. Movies or TV: Trending /
// Popular / Upcoming / New releases rows, or one genre as a grid; a search box (films and series together). Tiles show
// "In library" or the request state; a tile opens the details + Get it sheet (series pick their seasons there).
import { useEffect, useState } from "react";
import { discoverGenres, discoverList, discoverSearch, DISCOVER_PATHS, type DiscoverPage } from "../api/companion";
import { titleKey, type DiscoverSection, type DiscoverTitle, type Genre, type MediaType } from "../lib/discover";
import { PosterCard } from "../components/PosterCard";
import { GetItSheet, stateText } from "../components/GetItSheet";
import { useIsPhone } from "../lib/device";
import "./Discover.css";

const SECTIONS: [DiscoverSection, string][] = [
  ["trending", "Trending"],
  ["popular", "Popular"],
  ["upcoming", "Upcoming"],
  ["new", "New releases"],
];

function DiscoverTile({ t, width, onOpen }: { t: DiscoverTitle; width: number; onOpen: (t: DiscoverTitle) => void }) {
  const state = stateText(t.state, t.progress);
  const label = `${t.title}${t.year ? ` (${t.year})` : ""}${t.mediaType === "tv" ? " · series" : ""}${state ? ` · ${state}` : ""}`;
  const tag = t.state === "downloading" && t.progress != null ? `${t.progress}%` : state ?? undefined;
  return <PosterCard width={width} src={t.posterUrl} name={t.title} label={label} tag={tag} faded={t.state === "in_library"} onOpen={() => onOpen(t)} />;
}

/** One section as a row (loads when the page opens; the server caches TMDB). */
function SectionRow({ title, path, media, width, onOpen }: { title: string; path: string; media: MediaType; width: number; onOpen: (t: DiscoverTitle) => void }) {
  const [items, setItems] = useState<DiscoverTitle[] | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    setItems(undefined);
    discoverList(path, media).then((p) => alive && setItems(p?.items ?? null));
    return () => {
      alive = false;
    };
  }, [path, media]);
  if (items === null || (items && items.length === 0)) return null;
  return (
    <section className="row discover-row">
      <h2 className="row-title">{title}</h2>
      <div className="row-scroll">
        {items
          ? items.map((t) => <DiscoverTile key={titleKey(t)} t={t} width={width} onOpen={onOpen} />)
          : Array.from({ length: 7 }, (_, i) => <div key={i} className="poster card-skeleton" style={{ width }} />)}
      </div>
    </section>
  );
}

/** A paged grid (one genre, or search results) with "More" for the next page. */
function PagedGrid({ title, load, empty, onOpen }: { title: string; load: (page: number) => Promise<DiscoverPage | null>; empty: string; onOpen: (t: DiscoverTitle) => void }) {
  const [pages, setPages] = useState<DiscoverPage[] | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    setPages(null);
    load(1).then((p) => alive && setPages(p ? [p] : []));
    return () => {
      alive = false;
    };
  }, [load]);
  const items = pages ? [...new Map(pages.flatMap((p) => p.items).map((t) => [titleKey(t), t])).values()] : null;
  const last = pages?.[pages.length - 1];
  return (
    <section className="grid-section">
      <h2 className="row-title">{pages === null ? "Loading…" : items?.length ? title : empty}</h2>
      <div className="poster-grid">
        {items?.map((t) => (
          <DiscoverTile key={titleKey(t)} t={t} width={0} onOpen={onOpen} />
        ))}
      </div>
      {last && last.page < last.totalPages && (
        <div className="discover-more">
          <button
            className="btn"
            data-nav
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              const next = await load(last.page + 1);
              if (next) setPages((ps) => [...(ps ?? []), next]);
              setBusy(false);
            }}
          >
            {busy ? "Loading…" : "More"}
          </button>
        </div>
      )}
    </section>
  );
}

export function Discover() {
  const phone = useIsPhone();
  const width = phone ? 112 : 150;
  const [media, setMedia] = useState<MediaType>("movie");
  const [genres, setGenres] = useState<Genre[]>([]);
  const [genre, setGenre] = useState<number | null>(null);
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState<DiscoverTitle | null>(null);
  const [gen, setGen] = useState(0); // bumped after a request so the badges refresh

  useEffect(() => {
    setGenre(null);
    setGenres([]);
    discoverGenres(media).then(setGenres);
  }, [media]);

  useEffect(() => {
    const t = setTimeout(() => setTerm(q.trim().length >= 2 ? q.trim() : ""), 300);
    return () => clearTimeout(t);
  }, [q]);

  const genreName = genres.find((g) => g.id === genre)?.name;
  // Stable per inputs so the grids reload only when they change
  const [searchLoad, setSearchLoad] = useState<(p: number) => Promise<DiscoverPage | null>>(() => () => Promise.resolve(null));
  const [genreLoad, setGenreLoad] = useState<(p: number) => Promise<DiscoverPage | null>>(() => () => Promise.resolve(null));
  useEffect(() => setSearchLoad(() => (p: number) => discoverSearch(term, p)), [term, gen]);
  useEffect(() => setGenreLoad(() => (p: number) => discoverList(DISCOVER_PATHS.section("genre", media, genre, p), media)), [media, genre, gen]);

  return (
    <div className="discover">
      <div className="discover-head">
        <h1 className="page-title">Discover</h1>
        <div className="chips" role="radiogroup" aria-label="Movies or TV">
          {(["movie", "tv"] as MediaType[]).map((m) => (
            <button key={m} className={`chip${media === m ? " on" : ""}`} data-nav role="radio" aria-checked={media === m} onClick={() => setMedia(m)}>
              {m === "movie" ? "Movies" : "TV"}
            </button>
          ))}
        </div>
      </div>
      <input className="search-input" data-nav type="search" placeholder="Search all films and series" value={q} onChange={(e) => setQ(e.target.value)} />
      {term ? (
        <PagedGrid title={`Results for “${term}”`} empty={`Nothing found for “${term}”`} load={searchLoad} onOpen={setOpen} />
      ) : (
        <>
          {genres.length > 0 && (
            <div className="chips discover-genres" role="radiogroup" aria-label="Genre">
              <button className={`chip${genre == null ? " on" : ""}`} data-nav role="radio" aria-checked={genre == null} onClick={() => setGenre(null)}>
                All
              </button>
              {genres.map((g) => (
                <button key={g.id} className={`chip${genre === g.id ? " on" : ""}`} data-nav role="radio" aria-checked={genre === g.id} onClick={() => setGenre(g.id)}>
                  {g.name}
                </button>
              ))}
            </div>
          )}
          {genre != null ? (
            <PagedGrid title={`${genreName ?? "Genre"} · ${media === "movie" ? "films" : "series"}`} empty="Nothing in this genre right now." load={genreLoad} onOpen={setOpen} />
          ) : (
            <>
              {SECTIONS.map(([s, label]) => (
                <SectionRow key={`${s}:${media}:${gen}`} title={label} path={DISCOVER_PATHS.section(s, media)} media={media} width={width} onOpen={setOpen} />
              ))}
              <DiscoverUnavailable media={media} />
            </>
          )}
        </>
      )}
      {open && <GetItSheet pick={open} onClose={() => setOpen(null)} onRequested={() => setGen((n) => n + 1)} />}
    </div>
  );
}

/** Every section unavailable (server without Discover, or not allowed): say so instead of an empty page. */
function DiscoverUnavailable({ media }: { media: MediaType }) {
  const [none, setNone] = useState(false);
  useEffect(() => {
    let alive = true;
    setNone(false);
    discoverList(DISCOVER_PATHS.section("trending", media), media).then((p) => alive && setNone(!p?.items.length));
    return () => {
      alive = false;
    };
  }, [media]);
  return none ? <p className="empty discover-empty">Discover isn't available from the server right now.</p> : null;
}
