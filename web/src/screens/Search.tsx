// The one box (#/search?q=, Ricki 23-Nov): Search and Chat in one place, "Search or describe what you want". Typing searches as you
// go: the companion's unified search when it's there (films, shows, people, collections, IPTV, Discover for admins; plain-English
// filters as removable chips; recent + suggested before typing), else plain Jellyfin search. A submit (Enter, the button, a final
// voice result) asks /v1/find: search results, the chat, or both (the chat turn deferred). Flow: lib/oneBox.ts; chat: lib/askChat.ts.
import { useEffect, useMemo, useRef, useState } from "react";
import { uid, type Item } from "../api/jellyfin";
import { clearRecent as clearServerRecent, recordClick, recordRecent, searchAll, suggestions, type Suggestions } from "../api/search";
import { resolveSeasonEpisode, searchJellyfin } from "../api/searchTask";
import { parseIntent, serverQuery, type SearchIntent } from "../lib/searchIntent";
import { availability, groupEntries, iptvLanguageNote, latestGuard } from "../lib/searchRank";
import { addRecent, clearRecent as clearLocalRecent, loadRecent } from "../lib/searchRecent";
import { filterChips, isEmpty, mergeLate, seasonHint, withoutFilter, type DiscoverHit, type LibHit, type SearchEntry, type SearchResult } from "../lib/search";
import { go, isRestoring } from "../lib/router";
import { applyError, applyResponse, ChatSender, dismissNotice, emptyState, noticeSeen, NOTICE_TEXT, retry as chatRetry, startOver as chatStartOver, submitText, tapChip, type ChatBody, type ChatState } from "../lib/askChat";
import {
  askChip, bothLine, chatOff, decided, DEFER_CHAT_MS, deferredScrolled, deferredTimer, emptyFind, FIND_ERROR, findRetryMs, FOLLOW_UP_PLACEHOLDER, leave, opened, PLACEHOLDER, retryFind,
  interrupt, searchChip, startOver, submit, typing, type FindUi, type Step,
} from "../lib/oneBox";
import { chatCall, chatState, find as findApi, findFeedback, noRoute } from "../api/find";
import { useVoice } from "../lib/useVoice";
import { ChatAnswer, useChatCards, type ChatFetched } from "../components/ChatAnswer";
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

/** TMDB collections have their own page; TV ones are Collections-hub rows. */
const collPath = (c: { hubId?: string; tmdbCollectionId?: number | null }) => (c.hubId ? `/collections/${encodeURIComponent(c.hubId)}` : `/collection/${c.tmdbCollectionId}`);
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
  if (e.kind === "collection") return collPath(e);
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
    e.kind === "collection" && e.total ? (e.inLibrary != null ? `${e.inLibrary} of ${e.total} in the library` : `${e.total} ${e.hubId ? "titles" : "films"}`) : null,
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
            <button key={c.key} className="search-link" data-key={c.key} data-nav onClick={() => go(collPath(c))}>
              <strong>{c.title}</strong>
              {c.total ? <span>{c.inLibrary != null ? `${c.inLibrary} of ${c.total} in the library` : `${c.total} ${c.hubId ? "titles" : "films"}`}</span> : null}
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
              <button key={c.hubId ?? c.tmdbCollectionId} className="search-link" data-nav onClick={() => go(collPath(c))}>
                <strong>{c.name}</strong>
                {c.total ? <span>{c.inLibrary != null ? `${c.inLibrary} of ${c.total} in the library` : `${c.total} ${c.hubId ? "titles" : "films"}`}</span> : null}
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

// ---------- the one box ----------

/** Everything on screen, kept when a result is opened so Back shows the same page without asking anything again (per user). */
interface Snapshot {
  user: string;
  /** The ?q= the page wrote: Back to that entry restores, anything else starts fresh. */
  urlQ: string;
  q: string;
  unified: SearchResult | null;
  fallback: Item[] | null;
  blocked: boolean;
  sugg: Suggestions | null;
  find: FindUi;
  chat: ChatState;
  chatFetched: ChatFetched | null;
  /** The term the shown results belong to (the typed search does not run again for it). */
  shownTerm: string;
}
let snapshot: Snapshot | null = null;
if (typeof window !== "undefined") window.addEventListener("jellytip-session", () => (snapshot = null));

const MIC = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    <rect x="9" y="3" width="6" height="11" rx="3" />
    <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
  </svg>
);

export function Search({ initial }: { initial: string }) {
  const userId = uid();
  // Back to this page: the same query, view, results and chat, nothing asked again (Ricki 21-Nov: Back lands where you were)
  const [restored] = useState<Snapshot | null>(() => (isRestoring() && snapshot && snapshot.user === userId && snapshot.urlQ === initial ? snapshot : null));
  const [q, setQ] = useState(restored?.q ?? initial);
  const [unified, setUnified] = useState<SearchResult | null>(restored?.unified ?? null);
  const [fallback, setFallback] = useState<Item[] | null>(restored?.fallback ?? null);
  const [sugg, setSugg] = useState<Suggestions | null>(restored?.sugg ?? null);
  const [blocked, setBlocked] = useState(restored?.blocked ?? false);
  const [retry, setRetry] = useState(0);
  const [find, setFindState] = useState<FindUi>(restored?.find ?? emptyFind);
  const findRef = useRef(find);
  const [chat, setChatState] = useState<ChatState>(restored?.chat ?? emptyState);
  const chatRef = useRef(chat);
  const chatFetched = useChatCards(chat, userId, restored?.chatFetched ?? null);
  const [retryWait, setRetryWait] = useState(false);
  const [notice, setNotice] = useState(() => {
    try {
      return !noticeSeen(localStorage);
    } catch {
      return true;
    }
  });
  const [recent, setRecent] = useState<string[]>(() => loadRecent(localStorage, userId));
  const mergedRecent = useMemo(() => {
    const seen = new Set<string>();
    return [...recent, ...(sugg?.recent ?? [])].filter((x) => !seen.has(x.toLowerCase()) && !!seen.add(x.toLowerCase())).slice(0, 10);
  }, [recent, sugg]);
  const input = useRef<HTMLInputElement>(null);
  const chatArea = useRef<HTMLDivElement>(null);
  const guard = useRef(latestGuard());
  const liveCtl = useRef<AbortController | null>(null);
  const findCtl = useRef<AbortController | null>(null);
  const deferTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sender = useRef<ChatSender | null>(null);
  if (!sender.current) sender.current = new ChatSender(chatCall);
  /** The term the results on screen belong to: the typed search skips it (a find answered it, or Back restored it). */
  const shownTerm = useRef(restored?.shownTerm ?? "");
  /** The words the results on screen were actually answered for (a failed find must not leave another term's results under it). */
  const resultsFor = useRef(restored?.shownTerm ?? "");
  const [chatOffNow, setChatOffNow] = useState(chatState.off);
  const qRef = useRef(q);
  qRef.current = q;
  const term = q.trim();
  const intent = useMemo(() => parseIntent(term), [term]);
  const view = find.view;
  const urlQ = view === "chat" ? find.q : term;

  const commitFind = (n: FindUi) => {
    findRef.current = n;
    setFindState(n);
  };
  const commitChat = (n: ChatState) => {
    chatRef.current = n;
    setChatState(n);
  };

  // ---- plain search (typing, a 404 fallback, "Just search for this") ----
  const runSearch = (t: string, delay: number) => {
    liveCtl.current?.abort();
    const ticket = guard.current.next();
    if (t.length < 2) {
      setUnified(null);
      setFallback(null);
      return () => undefined;
    }
    const ctl = (liveCtl.current = new AbortController());
    const live = () => !ctl.signal.aborted && guard.current.isCurrent(ticket);
    const it = parseIntent(t);
    const timer = setTimeout(async () => {
      const r = await searchAll(serverQuery(it), ctl.signal);
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
        shownTerm.current = resultsFor.current = norm(t);
        setUnified(r);
        setFallback(null);
        await lateGroups(r, it, ctl.signal, live);
        return;
      }
      // No unified search on this server (or it failed): Jellyfin's own search, with year and genre sent as server-side filters
      const items = await searchJellyfin(it, t).catch(() => [] as Item[]);
      if (!live()) return;
      shownTerm.current = resultsFor.current = norm(t);
      setUnified(null);
      setFallback(items);
    }, delay);
    return () => {
      ctl.abort();
      clearTimeout(timer);
    };
  };
  // A group answered late (Discover: TMDB slower than 40 ms; IPTV: copies still being verified): ask again for just that group and
  // merge it into what is on screen. The server sends each at most once per query per 20 s, so this cannot loop.
  const lateGroups = async (r: SearchResult, it: SearchIntent, signal: AbortSignal, live: () => boolean) => {
    for (const [what, waits] of [["discover", [300]], ["iptv", [1000, 1500]]] as const) {
      if (!r.pending.includes(what)) continue;
      for (const wait of waits) {
        await new Promise((ok) => setTimeout(ok, wait));
        if (!live()) return;
        const d = await searchAll(serverQuery(it), signal, what);
        if (!live() || !d || d === "blocked") break;
        setUnified((cur) => (cur && cur.q === r.q ? mergeLate(cur, d, what) : cur));
        if (!d.pending.includes(what)) break;
      }
    }
  };

  // ---- chat turns (lib/askChat.ts) ----
  const runChat = (body: ChatBody) => {
    void sender.current!.send(body).then((out) => {
      if (out.kind === "aborted") return; // a newer request replaced it
      if (out.kind === "ok") return commitChat(applyResponse(chatRef.current, out.resp));
      const e = applyError(chatRef.current, body.rev, out.status);
      if (e.state.error === "off") {
        // chat is off on the server: the plain results, and no more Ask chips this session
        chatState.off = true;
        setChatOffNow(true);
        return apply(chatOff(findRef.current));
      }
      commitChat(e.state);
      if (e.resend) runChat(e.resend); // 400 / 413: once more with the oldest turns trimmed
    });
  };
  const dispatchChat = (r: { state: ChatState; body: ChatBody } | null) => {
    if (!r) return false; // refused: a turn is pending (the controls are disabled too)
    commitChat(r.state);
    runChat(r.body);
    return true;
  };

  // ---- the flow's effects (lib/oneBox.ts) ----
  const stopDefer = () => {
    if (deferTimer.current) clearTimeout(deferTimer.current);
    deferTimer.current = null;
  };
  function apply(step: Step) {
    const { fx } = step;
    commitFind(step.state);
    if (fx.feedback) findFeedback(fx.feedback.id, fx.feedback.switched);
    if (fx.stopTimer) stopDefer();
    if (fx.abortFind) findCtl.current?.abort();
    if (fx.chat && chatState.off) {
      // chat is known to be off: never ask it; a chat view falls back to the plain results for the same words
      if (step.state.view === "chat") return apply(chatOff(step.state));
      if (step.state.view === "both") commitFind({ ...step.state, deferred: "cancelled" });
      return;
    }
    if (fx.resetChat) {
      sender.current!.cancel();
      commitChat(chatStartOver(chatRef.current));
    }
    if (fx.find) startFind(fx.find, step.state.seq);
    if (fx.search) {
      const t = fx.search;
      shownTerm.current = norm(t);
      setQ(t);
      runSearch(t, 0);
    }
    if (fx.chat) {
      if (step.state.view === "chat") setQ(""); // the box is the follow-up line now
      dispatchChat(submitText(chatRef.current, fx.chat));
    }
    if (fx.armTimer) {
      stopDefer();
      deferTimer.current = setTimeout(() => {
        deferTimer.current = null;
        apply(deferredTimer(findRef.current));
      }, DEFER_CHAT_MS);
    }
  }
  const startFind = (t: string, seq: number) => {
    // the typed search for these words is superseded by the find (it never lands over its answer)
    liveCtl.current?.abort();
    guard.current.next();
    shownTerm.current = norm(t);
    findCtl.current?.abort();
    const ctl = (findCtl.current = new AbortController());
    void findApi(t, ctl.signal).then((out) => {
      if (ctl.signal.aborted || findRef.current.seq !== seq) return;
      if (out.kind === "error" && resultsFor.current !== norm(t)) {
        // the results on screen are for other words (e.g. "hea" before the debounce): don't leave them under the error
        setUnified(null);
        setFallback(null);
      }
      if (out.kind === "ok" && out.d.search) {
        resultsFor.current = norm(t);
        setBlocked(false);
        setUnified(out.d.search);
        setFallback(null);
        const it = parseIntent(t);
        const ticket = guard.current.next();
        const live = () => !ctl.signal.aborted && guard.current.isCurrent(ticket);
        void lateGroups(out.d.search, it, ctl.signal, live);
      }
      if (out.kind === "ok" && out.d.mode !== "search") warm();
      apply(decided(findRef.current, seq, out, Date.now()));
    });
  };

  // The chat service warms up only when chat is likely: a chat / both answer, or the "Ask Chat about this" chip shown or focused
  // (fire and forget, at most once a minute from this page)
  const warmedAt = useRef(restored ? Date.now() : 0); // Back: the page is as it was, nothing is sent (not even a warm-up) at once
  const warm = () => {
    if (Date.now() - warmedAt.current < WARM_EVERY_MS) return;
    warmedAt.current = Date.now();
    sender.current!.warm();
  };

  const submitBox = (text: string) => {
    if (findRef.current.deciding) return;
    if (retryTimer.current) clearTimeout(retryTimer.current); // a new submit replaces a pending Try again
    retryTimer.current = null;
    setRetryWait(false);
    const r = submit(findRef.current, text, Date.now(), noRoute.missing(Date.now()));
    if (!r.fx.find && !r.fx.search && !r.fx.chat) return;
    if (r.fx.chat && chatRef.current.pending) return; // a follow-up while a turn is out: keep what was typed
    apply(r);
  };

  // ---- voice into the same box (lib/useVoice.ts: tap = until a pause, hold = until release) ----
  // busy only while a find is out, or a chat turn is out in the chat view (both's deferred turn never cuts a press short)
  const micBusy = find.deciding || (find.view === "chat" && !!chat.pending);
  const voice = useVoice({ getText: () => qRef.current, setText: setQ, onSend: submitBox, disabled: micBusy });
  const { listening, error: voiceErr } = voice;

  // The typed search: as you type, unless the results already belong to these words (a find answered them, Back restored them),
  // the box is the chat's follow-up line, or speech is still coming in (a final voice result is a submit)
  useEffect(() => {
    go(urlQ ? `/search?q=${encodeURIComponent(urlQ)}` : "/search", true);
    if (view === "chat" || listening) return;
    if (norm(term) === shownTerm.current && term.length >= 2) return;
    shownTerm.current = "";
    return runSearch(term, DEBOUNCE_MS);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [term, retry, view, listening]);

  useEffect(() => {
    if (!restored) suggestions().then(setSugg);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // A different signed-in user sees their own recents
  useEffect(() => setRecent(loadRecent(localStorage, userId)), [userId]);

  // both: scrolling the chat area into view asks the chat. Only when it was below the fold when armed (a short page shows it at
  // once: the timer covers that), and only once a real part of it shows (a 1 px scroll or the keyboard closing doesn't count)
  useEffect(() => {
    const el = chatArea.current;
    if (view !== "both" || find.deferred !== "waiting" || !el || typeof IntersectionObserver === "undefined") return;
    if (el.getBoundingClientRect().top < window.innerHeight) return;
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((x) => x.isIntersecting && x.intersectionRect.height >= SCROLL_IN_PX)) apply(deferredScrolled(findRef.current));
      },
      { threshold: [0, 0.25, 0.5, 1] },
    );
    io.observe(el);
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, find.deferred]);

  // Leaving (a result opened, the nav): keep the page for Back; nothing in flight is re-sent by itself later
  const latest = useRef<Snapshot | null>(null);
  latest.current = { user: userId, urlQ, q, unified, fallback, blocked, sugg, find, chat, chatFetched, shownTerm: shownTerm.current };
  useEffect(
    () => () => {
      stopDefer();
      if (retryTimer.current) clearTimeout(retryTimer.current);
      liveCtl.current?.abort();
      findCtl.current?.abort();
      sender.current?.cancel();
      const s = latest.current!;
      const c = s.chat.pending ? applyError(s.chat, s.chat.rev, 0).state : s.chat; // a turn out at leave: Retry, never auto-resent
      snapshot = { ...s, find: leave(findRef.current), chat: c, shownTerm: shownTerm.current };
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const pick = (v: string) => {
    apply(interrupt(findRef.current));
    setQ(v);
    input.current?.focus();
  };
  const remember = (t = term) => {
    if (t.length < 2) return;
    recordRecent(t);
    setRecent(addRecent(localStorage, userId, t));
  };
  const doStartOver = () => {
    voice.cancel();
    voice.setError(null);
    findCtl.current?.abort();
    if (retryTimer.current) clearTimeout(retryTimer.current);
    setRetryWait(false);
    apply(startOver(findRef.current));
    shownTerm.current = "";
    setQ("");
    input.current?.focus();
  };
  const tryFindAgain = () => {
    if (retryWait) return;
    const ms = findRetryMs(findRef.current.failures);
    setRetryWait(true);
    retryTimer.current = setTimeout(() => {
      retryTimer.current = null;
      setRetryWait(false);
      apply(retryFind(findRef.current));
    }, ms);
  };
  const dismiss = () => {
    try {
      dismissNotice(localStorage);
    } catch {
      /* blocked */
    }
    setNotice(false);
  };

  const chatShown = view === "chat";
  const deciding = find.deciding;
  const chatTouched = chat.turns.length > 0 || !!chat.pending || !!chat.error;
  // under the box on search answers and plain results; BOTH has its own in the chat area
  const askable = !chatOffNow && view === "search" && (term.length >= 2 || find.q.length >= 2);
  const askChipShown = (askable && !deciding) || (!chatOffNow && view === "both" && !chatTouched);
  useEffect(() => {
    if (askChipShown) warm();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [askChipShown]);
  const noticeLine = notice && (chatShown || (view === "both" && chatTouched)) ? (
    <p className="onebox-notice">
      {NOTICE_TEXT}{" "}
      <button data-nav onClick={dismiss}>
        Got it
      </button>
    </p>
  ) : null;

  return (
    <div className="search onebox">
      <form
        className="onebox-bar"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          submitBox(q);
        }}
      >
        <input
          ref={input}
          className="search-input"
          data-nav
          autoFocus
          type="search"
          enterKeyHint={chatShown ? "send" : "search"}
          maxLength={300}
          aria-label={PLACEHOLDER}
          placeholder={chatShown ? FOLLOW_UP_PLACEHOLDER : PLACEHOLDER}
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            const t = typing(findRef.current, e.target.value);
            if (t.state !== findRef.current) apply(t);
          }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") {
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
        {voice.available && (
          <button
            type="button"
            className={`btn onebox-mic${listening ? " on" : ""}`}
            data-nav
            data-testid="mic"
            title={voice.phone ? "Tap to talk, or hold and release. Voice uses your phone's speech service" : "Tap to talk, or hold and release (or hold Space). Voice uses your browser's speech service"}
            aria-label={listening ? "Listening" : "Talk"}
            aria-pressed={listening}
            disabled={micBusy}
            onPointerDown={(e) => {
              if (e.button !== 0) return;
              e.currentTarget.setPointerCapture?.(e.pointerId); // the release is seen even off the button
              apply(interrupt(findRef.current));
              voice.down();
            }}
            onPointerUp={voice.up}
            onPointerCancel={voice.up}
            onKeyDown={(e) => {
              if (e.key !== " " && e.key !== "Enter") return;
              e.preventDefault(); // no click from the keyboard: the hold is the gesture
              if (!e.repeat) {
                apply(interrupt(findRef.current));
                voice.down();
              }
            }}
            onKeyUp={(e) => {
              if (e.key !== " " && e.key !== "Enter") return;
              e.preventDefault();
              voice.up();
            }}
            onBlur={() => voice.up()}
            onContextMenu={(e) => e.preventDefault()}
          >
            {MIC}
          </button>
        )}
        <button type="submit" className="btn primary" data-nav disabled={!term || deciding || (chatShown && !!chat.pending)}>
          {chatShown ? "Send" : "Search"}
        </button>
      </form>
      {(listening || voiceErr) && (
        <p className="onebox-voice" data-testid="voice-hint" role="status" aria-live="polite">
          {listening ? "Listening…" : voiceErr}
        </p>
      )}
      <div className="onebox-actions">
        {deciding && (
          <span className="onebox-status" role="status">
            Searching…
          </span>
        )}
        {askable && !deciding && (
          <button className="chip" data-nav data-testid="ask-chip" onFocus={warm} onClick={() => apply(askChip(findRef.current, term))}>
            Ask Chat about this ›
          </button>
        )}
        {chatShown && find.decided === "chat" && !chat.pending && chat.turns.length > 0 && (
          <button className="chip" data-nav data-testid="search-chip" onClick={() => apply(searchChip(findRef.current))}>
            Just search for this
          </button>
        )}
        {(chatShown || view === "both" || find.q) && (
          <button className="btn small" data-nav data-testid="start-over" onClick={doStartOver}>
            Start over
          </button>
        )}
      </div>
      {find.error && !chatShown && (
        <div className="rows-error onebox-error" role="alert">
          <p>{FIND_ERROR}</p>
          <button className="btn" data-nav disabled={retryWait} onClick={tryFindAgain}>
            {retryWait ? "Trying again…" : "Try again"}
          </button>
        </div>
      )}
      {/* Opening any result remembers the query (recent searches) and tells the router the decision was right */}
      <div
        className="search-results"
        onClickCapture={(e) => {
          const el = e.target as HTMLElement;
          if (!el.closest(".card, .poster, .person-tile, .search-link, .search-hero .btn, .onnow-card, .iptv-card")) return;
          if (!chatShown) remember();
          apply(opened(findRef.current));
          // Ranked layout: log which position was opened (1-based, the top hit is 1)
          const key = el.closest<HTMLElement>("[data-key]")?.dataset.key;
          const at = key && unified?.results ? unified.results.findIndex((x) => x.key === key) : -1;
          if (at >= 0) recordClick(term, unified!.results![at], at + 1);
        }}
      >
        {chatShown ? (
          <>
            {noticeLine}
            <ChatAnswer state={chat} fetched={chatFetched} onTap={(op) => dispatchChat(tapChip(chatRef.current, op))} onRetry={() => dispatchChat(chatRetry(chatRef.current))} />
          </>
        ) : (
          <>
            {term.length < 2 && !deciding && (mergedRecent.length > 0 || sugg) && (
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
                <button
                  className="btn"
                  data-nav
                  onClick={() => {
                    shownTerm.current = ""; // the words were never answered: search them again
                    setRetry((n) => n + 1);
                  }}
                >
                  Try again
                </button>
              </div>
            )}
            {view === "both" && !chatOffNow && (
              <section className="onebox-chat-area" ref={chatArea} aria-label="Chat">
                <h2 className="row-title">Chat</h2>
                {!chatTouched ? (
                  <div className="chips">
                    <button className="chip" data-nav onFocus={(e) => {
                        warm();
                        if (e.currentTarget.matches(":focus-visible")) apply(deferredScrolled(findRef.current));
                      }} onClick={() => apply(askChip(findRef.current, term))}>
                      {bothLine(find.deferred, false, false)} ›
                    </button>
                  </div>
                ) : (
                  <>
                    {noticeLine}
                    <ChatAnswer
                      compact
                      state={chat}
                      fetched={chatFetched}
                      onTap={(op) => dispatchChat(tapChip(chatRef.current, op))}
                      onRetry={() => dispatchChat(chatRetry(chatRef.current))}
                      footer={
                        !chat.pending && chat.turns.length > 0 ? (
                          <div className="chips">
                            <button className="chip" data-nav onClick={() => apply(askChip(findRef.current, term))}>
                              Continue in chat ›
                            </button>
                          </div>
                        ) : null
                      }
                    />
                  </>
                )}
              </section>
            )}
          </>
        )}
      </div>
    </div>
  );
}

const norm = (t: string) => t.replace(/\s+/g, " ").trim();
const WARM_EVERY_MS = 60_000;
/** BOTH: how much of the chat area must scroll into view before that counts as moving to it. */
const SCROLL_IN_PX = 80;
