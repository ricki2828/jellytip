// Search (#/search?q=): the companion's unified search when it's there (films, shows, people, collections, IPTV,
// Discover for admins; plain-English filters as removable chips; recent + suggested before typing), else plain
// Jellyfin search as before.
import { useEffect, useRef, useState } from "react";
import { search, type Item } from "../api/jellyfin";
import { clearRecent, recordRecent, searchAll, suggestions, type Suggestions } from "../api/search";
import { filterChips, isEmpty, withoutFilter, type DiscoverHit, type LibHit, type SearchResult } from "../lib/search";
import { go } from "../lib/router";
import { useIsPhone } from "../lib/device";
import { Card } from "../components/Card";
import { PersonTile, filmCount } from "../components/PersonTile";
import { PosterCard } from "../components/PosterCard";
import { IptvCard } from "../components/IptvTile";
import { itemsByIds } from "../components/ServerRow";
import { GetItSheet, stateText } from "../components/GetItSheet";

const DEBOUNCE_MS = 200;

function LibGrid({ label, hits, untitled }: { label: string; hits: LibHit[]; untitled: Set<string> }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const ids = hits.map((h) => h.id).join(",");
  useEffect(() => {
    let alive = true;
    setItems(null);
    itemsByIds(ids.split(","))
      .then((r) => alive && setItems(r))
      .catch(() => alive && setItems([]));
    return () => {
      alive = false;
    };
  }, [ids]);
  if (items && items.length === 0) return null;
  return (
    <section className="grid-section">
      <h2 className="row-title">{label}</h2>
      <div className="grid">
        {items ? items.map((it) => <Card key={it.Id} item={it} width={0} untitled={untitled.has(it.Id)} />) : hits.map((h) => <div key={h.id} className="card fluid card-skeleton" />)}
      </div>
    </section>
  );
}

function Unified({ r, onChip }: { r: SearchResult; onChip: (q: string) => void }) {
  const phone = useIsPhone();
  const [getIt, setGetIt] = useState<DiscoverHit | null>(null);
  const untitled = new Set(r.untitled);
  const chips = filterChips(r.filters);
  const posterW = phone ? 112 : 150;
  return (
    <>
      {chips.length > 0 && (
        <div className="chips search-chips" aria-label="Filters read from your search">
          {chips.map((c) => (
            <button key={c.key} className="chip on" data-nav onClick={() => onChip(withoutFilter(r.text, r.filters, c.key))} aria-label={`Remove ${c.label}`}>
              {c.label}
              <span aria-hidden> ×</span>
            </button>
          ))}
        </div>
      )}
      {isEmpty(r) && <p className="empty">Nothing found for “{r.q}”.</p>}
      {r.films.length > 0 && <LibGrid label="Films" hits={r.films} untitled={untitled} />}
      {r.shows.length > 0 && <LibGrid label="Shows" hits={r.shows} untitled={untitled} />}
      {r.people.length > 0 && (
        <section className="row">
          <h2 className="row-title">People</h2>
          <div className="row-scroll">
            {r.people.map((p) => (
              <PersonTile key={p.personId} id={p.personId} name={p.name} imageTag={p.imageTag} width={posterW} sub={p.filmCount ? filmCount(p.filmCount) : p.role} />
            ))}
          </div>
        </section>
      )}
      {r.collections.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Collections</h2>
          <div className="search-links">
            {r.collections.map((c) => (
              <button key={c.tmdbCollectionId} className="search-link" data-nav onClick={() => go(`/collection/${c.tmdbCollectionId}`)}>
                <strong>{c.name}</strong>
                {c.total ? <span>{c.inLibrary ?? 0} of {c.total} in the library</span> : null}
              </button>
            ))}
          </div>
        </section>
      )}
      {r.iptv.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">On your IPTV</h2>
          <div className="grid">
            {r.iptv.map((h) => (
              <IptvCard key={h.iptvId} item={{ id: h.iptvId, title: h.name, year: h.year, quality: [], tech: h.tech, poster: null, backdrop: null }} width={0} />
            ))}
          </div>
        </section>
      )}
      {r.discover.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Not in the library</h2>
          <div className="poster-grid">
            {r.discover.map((d) => {
              const state = stateText(d.requested);
              return (
                <PosterCard
                  key={`${d.mediaType}${d.tmdbId}`}
                  width={0}
                  src={d.posterUrl}
                  name={d.title}
                  label={`${d.title}${d.year ? ` (${d.year})` : ""}${state ? ` · ${state}` : " · Get it"}`}
                  tag={state ?? undefined}
                  faded={!!state}
                  onOpen={state ? undefined : () => setGetIt(d)}
                />
              );
            })}
          </div>
        </section>
      )}
      {getIt && <GetItSheet pick={{ title: getIt.title, year: getIt.year, tmdbId: getIt.tmdbId, mediaType: getIt.mediaType, posterUrl: getIt.posterUrl }} onClose={() => setGetIt(null)} />}
    </>
  );
}

function Fallback({ results, q }: { results: Item[]; q: string }) {
  const groups = ([
    ["Movies", results.filter((r) => r.Type === "Movie")],
    ["Shows", results.filter((r) => r.Type === "Series")],
    ["Episodes", results.filter((r) => r.Type === "Episode")],
  ] as [string, Item[]][]).filter(([, l]) => l.length > 0);
  return (
    <>
      {groups.length === 0 && <p className="empty">Nothing found for “{q}”.</p>}
      {groups.map(([label, list]) => (
        <section key={label} className="grid-section">
          <h2 className="row-title">{label}</h2>
          <div className="grid">
            {list.map((it) => (
              <Card key={it.Id} item={it} width={0} />
            ))}
          </div>
        </section>
      ))}
    </>
  );
}

function Suggest({ s, onPick, onClear }: { s: Suggestions; onPick: (q: string) => void; onClear: () => void }) {
  return (
    <div className="search-suggest">
      {s.recent.length > 0 && (
        <section>
          <h2 className="row-title">
            Recent
            <button className="link search-clear" data-nav onClick={onClear}>
              Clear
            </button>
          </h2>
          <div className="chips">
            {s.recent.map((q) => (
              <button key={q} className="chip" data-nav onClick={() => onPick(q)}>
                {q}
              </button>
            ))}
          </div>
        </section>
      )}
      {s.suggested.length > 0 && (
        <section>
          <h2 className="row-title">Try</h2>
          <div className="chips">
            {s.suggested.map((q) => (
              <button key={q} className="chip" data-nav onClick={() => onPick(q)}>
                {q}
              </button>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

export function Search({ initial }: { initial: string }) {
  const [q, setQ] = useState(initial);
  const [unified, setUnified] = useState<SearchResult | null>(null);
  const [fallback, setFallback] = useState<Item[] | null>(null);
  const [sugg, setSugg] = useState<Suggestions | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const term = q.trim();

  useEffect(() => {
    suggestions().then(setSugg);
  }, []);

  useEffect(() => {
    go(term ? `/search?q=${encodeURIComponent(term)}` : "/search", true);
    if (term.length < 2) {
      setUnified(null);
      setFallback(null);
      return;
    }
    const ctl = new AbortController();
    const t = setTimeout(async () => {
      const r = await searchAll(term, ctl.signal);
      if (ctl.signal.aborted) return;
      if (r) {
        setUnified(r);
        setFallback(null);
        return;
      }
      // No unified search on this server (or it failed): Jellyfin's own search
      const items = await search(term).catch(() => [] as Item[]);
      if (ctl.signal.aborted) return;
      setUnified(null);
      setFallback(items);
    }, DEBOUNCE_MS);
    return () => {
      ctl.abort();
      clearTimeout(t);
    };
  }, [term]);

  const pick = (v: string) => {
    setQ(v);
    input.current?.focus();
  };

  return (
    <div className="search">
      <input
        ref={input}
        className="search-input"
        data-nav
        autoFocus
        type="search"
        placeholder="Films, shows, people… or “90s french crime”"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            (document.querySelector(".search-results [data-nav]") as HTMLElement | null)?.focus();
          }
        }}
      />
      {/* Opening any result remembers the query (recent searches) */}
      <div className="search-results" onClickCapture={(e) => (e.target as HTMLElement).closest(".card, .poster, .person-tile, .search-link") && recordRecent(term)}>
        {term.length < 2 && sugg && <Suggest s={sugg} onPick={pick} onClear={() => void clearRecent().then(() => setSugg((s) => s && { ...s, recent: [] }))} />}
        {term.length >= 2 && unified && <Unified r={unified} onChip={pick} />}
        {term.length >= 2 && fallback && <Fallback results={fallback} q={term} />}
      </div>
    </div>
  );
}
