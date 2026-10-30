// Search (#/search?q=): the companion's unified search when it's there (films, shows, people, collections, IPTV,
// Discover for admins; plain-English filters as removable chips; recent + suggested before typing), else plain
// Jellyfin search as before.
import { useEffect, useRef, useState } from "react";
import { search, type Item } from "../api/jellyfin";
import { clearRecent, recordClick, recordRecent, searchAll, suggestions, type Suggestions } from "../api/search";
import { filterChips, isEmpty, seasonHint, withoutFilter, type DiscoverHit, type LibHit, type SearchEntry, type SearchResult } from "../lib/search";
import { go } from "../lib/router";
import { useIsPhone } from "../lib/device";
import { Card } from "../components/Card";
import { PersonTile, filmCount } from "../components/PersonTile";
import { PosterCard } from "../components/PosterCard";
import { IptvCard, IptvPoster, safeArt } from "../components/IptvTile";
import { Backdrop } from "../components/Backdrop";
import { imageUrl } from "../api/jellyfin";
import { targetPath } from "../lib/iptvPlay";
import { vodPath } from "../lib/iptvVod";
import { itemsByIds } from "../components/ServerRow";
import { GetItSheet, stateText } from "../components/GetItSheet";

const DEBOUNCE_MS = 150;

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
            <button key={c.id} className="chip on" data-nav onClick={() => onChip(withoutFilter(r.text, r.filters, c.id))} aria-label={`Remove ${c.label}`}>
              {c.label}
              <span aria-hidden> ×</span>
            </button>
          ))}
        </div>
      )}
      {isEmpty(r) && <p className="empty">Nothing found for “{r.q}”.</p>}
      {r.results ? <Ranked r={r} untitled={untitled} posterW={posterW} onGetIt={setGetIt} /> : <Groups r={r} untitled={untitled} posterW={posterW} setGetIt={setGetIt} />}
      {getIt && <GetItSheet pick={{ title: getIt.title, year: getIt.year, tmdbId: getIt.tmdbId, mediaType: getIt.mediaType, posterUrl: getIt.posterUrl }} onClose={() => setGetIt(null)} />}
    </>
  );
}

const SOURCE_NAME = { library: "In your library", iptv: "Via IPTV", discover: "Not available" } as const;

const toDiscover = (e: SearchEntry): DiscoverHit => ({ tmdbId: e.tmdbId!, mediaType: e.kind === "series" ? "tv" : "movie", title: e.title, year: e.year, posterUrl: e.posterUrl, requested: e.requested });

const openPath = (e: SearchEntry): string | null => {
  if (e.kind === "person") return `/person/${e.personId}?name=${encodeURIComponent(e.title)}`;
  if (e.kind === "collection") return `/collection/${e.tmdbCollectionId}`;
  if (e.source === "library") return `/item/${e.id}`;
  if (e.source === "iptv") return vodPath(e.iptvId!);
  return null;
};

/** The top hit: backdrop, title, year, one line of facts, source badge and a Play / Open / Get it button. */
function TopHit({ e, item, onGetIt }: { e: SearchEntry; item: Item | undefined; onGetIt: (d: DiscoverHit) => void }) {
  const tag = item?.BackdropImageTags?.[0];
  const art =
    e.source === "library" && item && tag
      ? imageUrl(item.Id, "Backdrop", { tag, fillWidth: 1280, quality: 80 })
      : e.source === "iptv"
        ? safeArt(e.backdrop ?? e.poster, 960)
        : e.source === "discover"
          ? e.posterUrl ?? null
          : e.kind === "person" && e.imageTag
            ? imageUrl(e.personId!, "Primary", { tag: e.imageTag, fillWidth: 600, quality: 80 })
            : null;
  const facts = [
    e.year,
    e.kind === "series" ? seasonHint(e) : null,
    e.kind === "person" ? [e.role, e.filmCount ? filmCount(e.filmCount) : null].filter(Boolean).join(" · ") || null : null,
    e.kind === "collection" && e.total ? (e.inLibrary != null ? `${e.inLibrary} of ${e.total} in the library` : `${e.total} films`) : null,
    e.rating ? `★ ${e.rating.toFixed(1)}` : null,
    ...e.quality.slice(0, 1),
    ...e.tech.slice(0, 2),
  ]
    .filter(Boolean)
    .join("  ·  ");
  const playable = e.kind === "film" && (e.source === "library" || e.source === "iptv");
  const go_ = () => {
    if (playable) go(e.source === "library" ? `/play/${e.id}` : `${targetPath({ vod: e.iptvId })}&t=${encodeURIComponent(e.title)}`);
    else {
      const p = openPath(e);
      if (p) go(p);
      else if (e.tmdbId != null) onGetIt(toDiscover(e));
    }
  };
  const label = playable ? "Play" : e.source === "discover" ? (e.requested ? stateText(e.requested) ?? "Requested" : "Get it") : "Open";
  return (
    <section className="search-hero" aria-label="Top result" data-key={e.key}>
      {art ? <Backdrop url={art} focal={null} /> : null}
      <div className="search-hero-scrim" />
      <div className="search-hero-text">
        <span className="search-hero-badge">{e.kind === "person" || e.kind === "collection" ? (e.kind === "person" ? "Person" : "Collection") : SOURCE_NAME[e.source]}</span>
        <h2>{e.title}</h2>
        {facts && <p className="search-hero-facts">{facts}</p>}
        <div className="buttons">
          <button className="btn primary" data-nav onClick={go_} disabled={e.source === "discover" && !!e.requested}>
            {label}
          </button>
          {playable && openPath(e) && (
            <button className="btn" data-nav onClick={() => go(openPath(e)!)}>
              Details
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

/** Server `results`: top hit, one ranked row of films and shows, then people, collections, lists and "Not available". */
function Ranked({ r, untitled, posterW, onGetIt }: { r: SearchResult; untitled: Set<string>; posterW: number; onGetIt: (d: DiscoverHit) => void }) {
  const list = r.results ?? [];
  const top = r.top;
  const libIds = list.filter((e) => e.source === "library" && (e.kind === "film" || e.kind === "series")).map((e) => e.id!);
  const idKey = libIds.join(",");
  const [items, setItems] = useState<Map<string, Item> | null>(null);
  useEffect(() => {
    let alive = true;
    if (!idKey) {
      setItems(new Map());
      return;
    }
    itemsByIds(idKey.split(","))
      .then((its) => alive && setItems(new Map(its.map((i) => [i.Id.toLowerCase().replace(/-/g, ""), i]))))
      .catch(() => alive && setItems(new Map()));
    return () => {
      alive = false;
    };
  }, [idKey]);
  const rest = list.filter((e) => e !== top);
  const ranked = rest.filter((e) => (e.kind === "film" || e.kind === "series") && e.source !== "discover");
  const people = rest.filter((e) => e.kind === "person");
  const colls = rest.filter((e) => e.kind === "collection");
  const seen = new Set(list.filter((e) => e.source === "discover").map((e) => e.tmdbId));
  const missing = [...rest.filter((e) => e.source === "discover" && e.kind !== "person" && e.kind !== "collection").map(toDiscover), ...r.discover.filter((d) => !seen.has(d.tmdbId))];
  const phone = useIsPhone();
  return (
    <>
      {top && <TopHit e={top} item={top.id ? items?.get(top.id) : undefined} onGetIt={onGetIt} />}
      {ranked.length > 0 && (
        <section className="row poster-row">
          <h2 className="row-title">Results</h2>
          <div className="row-scroll">
            {ranked.map((e) => {
              if (e.source === "iptv") {
                return (
                  <span key={e.key} className="search-pos" data-key={e.key}>
                    <IptvPoster width={posterW} item={{ id: e.iptvId!, title: e.title, year: e.year, quality: e.quality, tech: e.tech, poster: e.poster, backdrop: e.backdrop }} tag={seasonHint(e)?.split(" · ")[0]} />
                  </span>
                );
              }
              const it = items?.get(e.id!);
              if (!it) return items ? null : <div key={e.key} className="poster card-skeleton" style={{ width: posterW }} />;
              return (
                <span key={e.key} className="search-pos" data-key={e.key}>
                  <PosterCard item={it} width={posterW} onOpen={() => go(`/item/${it.Id}`)} untitled={untitled.has(it.Id)} tag={e.season != null ? seasonHint(e) ?? undefined : undefined} />
                </span>
              );
            })}
          </div>
        </section>
      )}
      {people.length > 0 && (
        <section className="row">
          <h2 className="row-title">People</h2>
          <div className="row-scroll">
            {people.map((p) => (
              <span key={p.key} className="search-pos" data-key={p.key}>
                <PersonTile id={p.personId!} name={p.title} imageTag={p.imageTag} width={phone ? 112 : 150} sub={p.filmCount ? filmCount(p.filmCount) : p.role} />
              </span>
            ))}
          </div>
        </section>
      )}
      {colls.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Collections</h2>
          <div className="search-links">
            {colls.map((c) => (
              <button key={c.key} className="search-link" data-key={c.key} data-nav onClick={() => go(`/collection/${c.tmdbCollectionId}`)}>
                <strong>{c.title}</strong>
                {c.total ? <span>{c.inLibrary != null ? `${c.inLibrary} of ${c.total} in the library` : `${c.total} films`}</span> : null}
              </button>
            ))}
          </div>
        </section>
      )}
      {r.lists.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Lists</h2>
          <div className="search-links">
            {r.lists.map((l) => (
              <button key={l.slug} className="search-link" data-nav onClick={() => go(`/list/${l.slug}`)}>
                <strong>{l.name}</strong>
                <span>{[l.source === "letterboxd" ? "Letterboxd" : l.source, l.count ? `${l.count} films` : null].filter(Boolean).join(" · ")}</span>
              </button>
            ))}
          </div>
        </section>
      )}
      {missing.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Not available</h2>
          <div className="poster-grid">
            {missing.map((d) => {
              const state = stateText(d.requested);
              return (
                <span key={`${d.mediaType}${d.tmdbId}`} className="search-pos" data-key={`d:${d.mediaType === "tv" ? "series" : "film"}:${d.tmdbId}`}>
                <PosterCard
                  width={0}
                  src={d.posterUrl}
                  name={d.title}
                  label={`${d.title}${d.year ? ` (${d.year})` : ""}${state ? ` · ${state}` : " · Get it"}`}
                  tag={state ?? undefined}
                  faded={!!state}
                  onOpen={state ? undefined : () => onGetIt(d)}
                />
                </span>
              );
            })}
          </div>
        </section>
      )}
    </>
  );
}

/** The old grouped layout (a server without `results`). */
function Groups({ r, untitled, posterW, setGetIt }: { r: SearchResult; untitled: Set<string>; posterW: number; setGetIt: (d: DiscoverHit) => void }) {
  return (
    <>
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
                {c.total ? <span>{c.inLibrary != null ? `${c.inLibrary} of ${c.total} in the library` : `${c.total} films`}</span> : null}
              </button>
            ))}
          </div>
        </section>
      )}
      {r.lists.length > 0 && (
        <section className="grid-section">
          <h2 className="row-title">Lists</h2>
          <div className="search-links">
            {r.lists.map((l) => (
              <button key={l.slug} className="search-link" data-nav onClick={() => go(`/list/${l.slug}`)}>
                <strong>{l.name}</strong>
                <span>{[l.source === "letterboxd" ? "Letterboxd" : l.source, l.count ? `${l.count} films` : null].filter(Boolean).join(" · ")}</span>
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
              <IptvCard key={h.iptvId} item={{ id: h.iptvId, title: h.name, year: h.year, quality: h.quality, tech: h.tech, poster: h.poster, backdrop: h.backdrop }} width={0} />
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
  const [blocked, setBlocked] = useState(false);
  const [retry, setRetry] = useState(0);
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
      setBlocked(false);
      // 503 (the server can't tell who is asking): no Jellyfin fallback, an error with Try again
      if (r === "blocked") {
        setUnified(null);
        setFallback(null);
        setBlocked(true);
        return;
      }
      if (r) {
        setUnified(r);
        setFallback(null);
        // Discover answered late (TMDB slower than 40 ms): it's cached server-side by now, so ask for just that group
        if (r.pending.includes("discover")) {
          await new Promise((ok) => setTimeout(ok, 300));
          if (ctl.signal.aborted) return;
          const d = await searchAll(term, ctl.signal, "discover");
          if (!ctl.signal.aborted && d && d !== "blocked" && d.discover.length) setUnified((cur) => (cur === r ? { ...r, discover: d.discover, pending: [] } : cur));
        }
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
  }, [term, retry]);

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
      <div className="search-results" onClickCapture={(e) => {
          const el = e.target as HTMLElement;
          if (!el.closest(".card, .poster, .person-tile, .search-link, .search-hero .btn")) return;
          recordRecent(term);
          // Ranked layout: log which position was opened (1-based, the top hit is 1)
          const key = el.closest<HTMLElement>("[data-key]")?.dataset.key;
          const at = key && unified?.results ? unified.results.findIndex((x) => x.key === key) : -1;
          if (at >= 0) recordClick(term, unified!.results![at], at + 1);
        }}>
        {term.length < 2 && sugg && <Suggest s={sugg} onPick={pick} onClear={() => void clearRecent().then(() => setSugg((s) => s && { ...s, recent: [] }))} />}
        {term.length >= 2 && unified && <Unified r={unified} onChip={pick} />}
        {term.length >= 2 && fallback && <Fallback results={fallback} q={term} />}
        {term.length >= 2 && blocked && (
          <div className="rows-error" role="alert">
            <p>Search isn't available right now.</p>
            <button className="btn" data-nav onClick={() => setRetry((n) => n + 1)}>
              Try again
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
