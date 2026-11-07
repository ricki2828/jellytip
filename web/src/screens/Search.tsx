// Search (#/search?q=): the companion's unified search when it's there (films, shows, people, collections, IPTV,
// Discover for admins; plain-English filters as removable chips; recent + suggested before typing), else plain
// Jellyfin search as before.
import { useEffect, useMemo, useRef, useState } from "react";
import { uid, type Item } from "../api/jellyfin";
import { clearRecent as clearServerRecent, recordClick, recordRecent, searchAll, suggestions, type Suggestions } from "../api/search";
import { resolveSeasonEpisode, searchJellyfin } from "../api/searchTask";
import { parseIntent, serverQuery, type SearchIntent } from "../lib/searchIntent";
import { availability, groupEntries, iptvLanguageNote, latestGuard } from "../lib/searchRank";
import { addRecent, clearRecent as clearLocalRecent, loadRecent } from "../lib/searchRecent";
import { filterChips, isEmpty, mergeLate, seasonHint, withoutFilter, type DiscoverHit, type LibHit, type SearchEntry, type SearchResult } from "../lib/search";
import { go } from "../lib/router";
import { useIsPhone } from "../lib/device";
import { Card } from "../components/Card";
import { PersonTile, filmCount } from "../components/PersonTile";
import { PosterCard } from "../components/PosterCard";
import { ChannelsRow, LiveRow } from "../components/SearchLive";
import { IptvCard, IptvPoster, safeArt } from "../components/IptvTile";
import { Backdrop } from "../components/Backdrop";
import { imageUrl } from "../api/jellyfin";
import { targetPath } from "../lib/iptvPlay";
import { vodPath } from "../lib/iptvVod";
import { itemsByIds } from "../components/ServerRow";
import { CollTabs, useCollTab } from "../components/CollTabs";
import { hitInTab } from "../lib/collections";
import { GetItSheet, stateText } from "../components/GetItSheet";

const DEBOUNCE_MS = 150;

// Library items fetched for results, per signed-in user (a different user starts empty)
let cacheUser = "";
let cacheMap = new Map<string, Item>();
function sessionItems(userId: string): Map<string, Item> {
  if (userId !== cacheUser) {
    cacheUser = userId;
    cacheMap = new Map();
  }
  return cacheMap;
}

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

function Unified({ r, intent, onChip }: { r: SearchResult; intent: SearchIntent; onChip: (q: string) => void }) {
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
      {r.results ? <Ranked r={r} intent={intent} untitled={untitled} posterW={posterW} onGetIt={setGetIt} /> : <Groups r={r} untitled={untitled} posterW={posterW} setGetIt={setGetIt} />}
      {getIt && <GetItSheet pick={{ title: getIt.title, year: getIt.year, tmdbId: getIt.tmdbId, mediaType: getIt.mediaType, posterUrl: getIt.posterUrl }} onClose={() => setGetIt(null)} />}
    </>
  );
}

const SOURCE_NAME = { Library: "In your library", IPTV: "Via IPTV", "Not available": "Not available" } as const;

const toDiscover = (e: SearchEntry): DiscoverHit => ({ tmdbId: e.tmdbId!, mediaType: e.kind === "series" ? "tv" : "movie", title: e.title, year: e.year, posterUrl: e.posterUrl, requested: e.requested });

const openPath = (e: SearchEntry): string | null => {
  if (e.kind === "person") return `/person/${e.personId}?name=${encodeURIComponent(e.title)}`;
  if (e.kind === "collection") return `/collection/${e.tmdbCollectionId}`;
  if (e.source === "library") return `/item/${e.id}`;
  if (e.source === "iptv") return vodPath(e.iptvId!);
  return null;
};

/** The top hit: backdrop, title, year, one line of facts, source badge and a Play / Open / Get it button. */
function TopHit({ e, item, intent, onGetIt }: { e: SearchEntry; item: Item | undefined; intent: SearchIntent; onGetIt: (d: DiscoverHit) => void }) {
  const [note, setNote] = useState<string | null>(null);
  const jumpTo = e.kind === "series" && e.source === "library" && intent.season != null;
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
    if (jumpTo) {
      setNote("Looking it up…");
      resolveSeasonEpisode(e.id!, intent.season!, intent.episode)
        .then((it) => (it ? go(intent.play && it.Type === "Episode" ? `/play/${it.Id}` : `/item/${it.Id}`) : setNote(intent.episode != null ? `S${intent.season} E${intent.episode} isn't in your library` : `Season ${intent.season} isn't in your library`)))
        .catch(() => setNote("Couldn't look that up. Try again."));
      return;
    }
    if (playable) go(e.source === "library" ? `/play/${e.id}` : `${targetPath({ vod: e.iptvId })}&t=${encodeURIComponent(e.title)}`);
    else {
      const p = openPath(e);
      if (p) go(p);
      else if (e.tmdbId != null) onGetIt(toDiscover(e));
    }
  };
  const label = jumpTo ? (intent.episode != null ? (intent.play ? `Play S${intent.season} E${intent.episode}` : `Go to S${intent.season} E${intent.episode}`) : `Go to season ${intent.season}`) : playable ? "Play" : e.source === "discover" ? (e.requested ? stateText(e.requested) ?? "Requested" : "Get it") : "Open";
  return (
    <section className="search-hero" aria-label="Top result" data-key={e.key}>
      {art ? <Backdrop url={art} focal={null} /> : null}
      <div className="search-hero-scrim" />
      <div className="search-hero-text">
        <span className="search-hero-badge">{e.kind === "person" || e.kind === "collection" ? (e.kind === "person" ? "Person" : "Collection") : SOURCE_NAME[availability(e)]}</span>
        <h2>{e.title}</h2>
        {facts && <p className="search-hero-facts">{facts}</p>}
        {note && <p className="search-hero-facts" role="status">{note}</p>}
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

/** Server `results` as: Top match, then Movies, Shows, People, Collections, Live / IPTV, Not available, in the order the intent asks for. Each item says where it plays. */
function Ranked({ r, intent: given, untitled, posterW, onGetIt }: { r: SearchResult; intent: SearchIntent; untitled: Set<string>; posterW: number; onGetIt: (d: DiscoverHit) => void }) {
  const list = r.results ?? [];
  const hasLive = !!(r.live && r.live.length) || !!(r.channels && r.channels.length);
  const g = useMemo(() => groupEntries(list, given, hasLive || r.sportQuery), [list, given, hasLive, r.sportQuery]);
  const intent = g.intent;
  const top = g.top;
  const [collTab, setCollTab] = useCollTab();
  const colls = g.collections.filter((c) => hitInTab({ collMediaType: c.collMediaType }, collTab));
  const libIds = list.filter((e) => e.source === "library" && (e.kind === "film" || e.kind === "series")).map((e) => e.id!);
  const idKey = libIds.join(",");
  // Per-user session cache by id: tiles stay mounted when the id list changes or a late group merges in
  const cache = sessionItems(uid());
  const [fetched, setFetched] = useState("");
  useEffect(() => {
    let alive = true;
    const missing = idKey ? idKey.split(",").filter((id) => !cache.has(id)) : [];
    if (!missing.length) {
      setFetched(idKey);
      return;
    }
    itemsByIds(missing)
      .then((its) => {
        for (const i of its) cache.set(i.Id.toLowerCase().replace(/-/g, ""), i);
        if (alive) setFetched(idKey);
      })
      .catch(() => alive && setFetched(idKey));
    return () => {
      alive = false;
    };
  }, [idKey, cache]);
  const loading = fetched !== idKey;
  const items = { get: (id: string) => cache.get(id) };
  const seen = new Set(list.filter((e) => e.source === "discover").map((e) => e.tmdbId));
  const missing = [...g.missing.map(toDiscover), ...r.discover.filter((d) => !seen.has(d.tmdbId))];
  const phone = useIsPhone();
  const tile = (e: SearchEntry) => {
    const avail = availability(e);
    const note = iptvLanguageNote(e, r);
    const hint = e.season != null ? seasonHint(e) : null;
    const tag = [avail, hint, note].filter(Boolean).join(" · ");
    if (e.source === "library") {
      const it = items.get(e.id!);
      if (!it) return loading ? <div key={e.key} className="poster card-skeleton" style={{ width: posterW }} /> : null;
      return (
        <span key={e.key} className="search-pos" data-key={e.key}>
          <PosterCard item={it} width={posterW} onOpen={() => go(`/item/${it.Id}`)} untitled={untitled.has(it.Id)} tag={tag} />
        </span>
      );
    }
    return (
      <span key={e.key} className="search-pos" data-key={e.key}>
        <IptvPoster width={posterW} item={{ id: e.iptvId!, title: e.title, year: e.year, quality: e.quality, tech: e.tech, poster: e.poster, backdrop: e.backdrop }} tag={tag} />
      </span>
    );
  };
  const sections: Record<string, React.ReactNode> = {
    live: (
      <>
        {r.live && r.live.length > 0 && <LiveRow hits={r.live} />}
        {r.channels && r.channels.length > 0 && <ChannelsRow hits={r.channels} />}
      </>
    ),
    movies: g.movies.length > 0 && (
      <section className="row poster-row">
        <h2 className="row-title">Movies</h2>
        <div className="row-scroll">{g.movies.map(tile)}</div>
      </section>
    ),
    shows: g.shows.length > 0 && (
      <section className="row poster-row">
        <h2 className="row-title">Shows</h2>
        <div className="row-scroll">{g.shows.map(tile)}</div>
      </section>
    ),
    people: g.people.length > 0 && (
      <section className="row">
        <h2 className="row-title">People</h2>
        <div className="row-scroll">
          {g.people.map((p) => (
            <span key={p.key} className="search-pos" data-key={p.key}>
              <PersonTile id={p.personId!} name={p.title} imageTag={p.imageTag} width={phone ? 112 : 150} sub={p.filmCount ? filmCount(p.filmCount) : p.role} />
            </span>
          ))}
        </div>
      </section>
    ),
    collections: g.collections.length > 0 && (
      <section className="grid-section">
        <h2 className="row-title">Collections</h2>
        <CollTabs tab={collTab} onChange={setCollTab} />
        {colls.length === 0 && <p className="empty">No {collTab === "tv" ? "TV" : "movie"} collections for this search.</p>}
        <div className="search-links">
          {colls.map((c) => (
            <button key={c.key} className="search-link" data-key={c.key} data-nav onClick={() => go(`/collection/${c.tmdbCollectionId}`)}>
              <strong>{c.title}</strong>
              {c.total ? <span>{c.inLibrary != null ? `${c.inLibrary} of ${c.total} in the library` : `${c.total} films`}</span> : null}
            </button>
          ))}
        </div>
      </section>
    ),
    missing: (
      <>
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
                      tag={state ?? "Not available"}
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
    ),
  };
  return (
    <>
      {g.order[0] === "live" && sections.live}
      {top && <TopHit e={top} item={top.id ? items.get(top.id) : undefined} intent={intent} onGetIt={onGetIt} />}
      {g.order.filter((k) => !(k === "live" && g.order[0] === "live")).map((k) => <span key={k} style={{ display: "contents" }}>{sections[k]}</span>)}
    </>
  );
}

/** The old grouped layout (a server without `results`). */
function Groups({ r, untitled, posterW, setGetIt }: { r: SearchResult; untitled: Set<string>; posterW: number; setGetIt: (d: DiscoverHit) => void }) {
  const liveRow = r.live && r.live.length > 0 ? <LiveRow hits={r.live} /> : null;
  const [collTab, setCollTab] = useCollTab();
  const colls = r.collections.filter((c) => hitInTab(c, collTab));
  return (
    <>
      {r.sportQuery && liveRow}
      {r.films.length > 0 && <LibGrid label="Films" hits={r.films} untitled={untitled} />}
      {r.shows.length > 0 && <LibGrid label="Shows" hits={r.shows} untitled={untitled} />}
      {!r.sportQuery && liveRow}
      {r.channels && r.channels.length > 0 && <ChannelsRow hits={r.channels} />}
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
          <CollTabs tab={collTab} onChange={setCollTab} />
          {colls.length === 0 && <p className="empty">No {collTab === "tv" ? "TV" : "movie"} collections for this search.</p>}
          <div className="search-links">
            {colls.map((c) => (
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
  const userId = uid();
  const [recent, setRecent] = useState<string[]>(() => loadRecent(localStorage, userId));
  const mergedRecent = useMemo(() => {
    const seen = new Set<string>();
    return [...recent, ...(sugg?.recent ?? [])].filter((x) => !seen.has(x.toLowerCase()) && !!seen.add(x.toLowerCase())).slice(0, 10);
  }, [recent, sugg]);
  const input = useRef<HTMLInputElement>(null);
  const guard = useRef(latestGuard());
  const term = q.trim();
  const intent = useMemo(() => parseIntent(term), [term]);

  useEffect(() => {
    suggestions().then(setSugg);
  }, []);
  // A different signed-in user sees their own recents
  useEffect(() => setRecent(loadRecent(localStorage, userId)), [userId]);

  useEffect(() => {
    go(term ? `/search?q=${encodeURIComponent(term)}` : "/search", true);
    // Any change of query supersedes whatever is in flight, even when the new one is too short to search
    const ticket = guard.current.next();
    if (term.length < 2) {
      setUnified(null);
      setFallback(null);
      return;
    }
    const ctl = new AbortController();
    const live = () => !ctl.signal.aborted && guard.current.isCurrent(ticket);
    const t = setTimeout(async () => {
      const r = await searchAll(serverQuery(intent), ctl.signal);
      if (!live()) return;
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
        // A group answered late (Discover: TMDB slower than 40 ms; IPTV: copies still being verified): ask again for just that
        // group and merge it into what is on screen. The server sends each at most once per query per 20 s, so this cannot loop.
        // IPTV: once after about 1 s and, if it is still pending, once more about 1.5 s later.
        for (const [what, waits] of [["discover", [300]], ["iptv", [1000, 1500]]] as const) {
          if (!r.pending.includes(what)) continue;
          for (const wait of waits) {
            await new Promise((ok) => setTimeout(ok, wait));
            if (!live()) return;
            const d = await searchAll(serverQuery(intent), ctl.signal, what);
            if (!live() || !d || d === "blocked") break;
            setUnified((cur) => (cur && cur.q === r.q ? mergeLate(cur, d, what) : cur));
            if (!d.pending.includes(what)) break;
          }
        }
        return;
      }
      // No unified search on this server (or it failed): Jellyfin's own search, with year and genre sent as server-side filters
      const items = await searchJellyfin(intent, term).catch(() => [] as Item[]);
      if (!live()) return;
      setUnified(null);
      setFallback(items);
    }, DEBOUNCE_MS);
    return () => {
      ctl.abort();
      clearTimeout(t);
    };
  }, [term, retry, intent]);

  const pick = (v: string) => {
    setQ(v);
    input.current?.focus();
  };
  const remember = () => {
    recordRecent(term);
    setRecent(addRecent(localStorage, userId, term));
  };

  return (
    <div className="search">
      <input
        ref={input}
        className="search-input"
        data-nav
        autoFocus
        type="search"
        placeholder="Films, shows, people… or “90s thrillers”, “watch Heat”, “live cricket”"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || (e.key === "Enter" && term.length >= 2)) {
            // Focus moves to the results only on an explicit key: the primary action when "watch X", else the first result
            e.preventDefault();
            const root = document.querySelector(".search-results");
            const el = (intent.play && root?.querySelector<HTMLElement>(".search-hero .btn.primary")) || root?.querySelector<HTMLElement>("[data-nav]");
            el?.focus();
          } else if (e.key === "Escape" && q) {
            // First Escape empties the box; with it already empty, Escape behaves as usual
            e.preventDefault();
            setQ("");
          }
        }}
      />
      <a className="chip search-ask" href="#/ask" data-nav title="Describe what you feel like watching">Not sure? Ask</a>
      {/* Opening any result remembers the query (recent searches) */}
      <div className="search-results" onClickCapture={(e) => {
          const el = e.target as HTMLElement;
          if (!el.closest(".card, .poster, .person-tile, .search-link, .search-hero .btn, .onnow-card")) return;
          remember();
          // Ranked layout: log which position was opened (1-based, the top hit is 1)
          const key = el.closest<HTMLElement>("[data-key]")?.dataset.key;
          const at = key && unified?.results ? unified.results.findIndex((x) => x.key === key) : -1;
          if (at >= 0) recordClick(term, unified!.results![at], at + 1);
        }}>
        {term.length < 2 && (mergedRecent.length > 0 || sugg) && (
          <Suggest
            s={{ recent: mergedRecent, suggested: sugg?.suggested ?? [] }}
            onPick={pick}
            onClear={() => {
              setRecent(clearLocalRecent(localStorage, userId));
              setSugg((x) => x && { ...x, recent: [] });
              void clearServerRecent();
            }}
          />
        )}
        {term.length >= 2 && unified && <Unified r={unified} intent={intent} onChip={pick} />}
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
