import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getItems, nextUp, resumeItems, type Item } from "../api/jellyfin";
import { manifest, rowsBlocked, serverRows, specials as getSpecials, type Carousel, type LetterboxdRow, type ServerRow, type Special } from "../api/companion";
import { withSpecials } from "../components/specials/Specials";
import { Hero } from "../components/Hero";
import { buildSlides, excludedIds, heroSources, SLIDE_MS, type HeroSlide } from "../lib/heroMix";
import { Row } from "../components/Row";
import { itemsByIds, serverRowElement } from "../components/ServerRow";
import { rememberRowTitles } from "../lib/rowTitles";
import { HomeEdit } from "../components/HomeEdit";
import { OnNowStrip } from "../components/OnNowStrip";
import { RecordingsRow } from "../components/RecordingsRow";
import { usePrefs } from "../api/me";
import { RowsError } from "../components/RowsError";
import { dropPersonalCaches } from "../api/companion";

const MAX_CAROUSEL_ROWS = 10;
const MAX_LETTERBOXD_ROWS = 6;
const ROW_LIMIT = 24;

export const LETTERBOXD_PREFIX = "Letterboxd · ";

function sortFor(order: string): { SortBy?: string; SortOrder?: string } {
  switch (order) {
    case "random":
      return { SortBy: "Random", SortOrder: "Ascending" };
    case "release":
    case "release.desc":
      return { SortBy: "PremiereDate,SortName", SortOrder: "Descending,Ascending" };
    case "added.desc":
      return { SortBy: "DateCreated,SortName", SortOrder: "Descending,Ascending" };
    default:
      return {};
  }
}

export const carouselLoader = (c: Carousel) => async () => (await getItems({ ParentId: c.id, Limit: ROW_LIMIT, ...sortFor(c.order) })).Items;

export const letterboxdLoader = (l: LetterboxdRow) => async () => {
  // Keep the list's own order; the server returns Ids in library order
  const r = await getItems({ Ids: [...new Set(l.itemIds)].join(","), IncludeItemTypes: "Movie" });
  const byId = new Map(r.Items.map((i) => [i.Id, i]));
  return [...new Set(l.itemIds)].map((id) => byId.get(id)).filter((x): x is Item => !!x);
};

/** The hero: the featured mix rotates until someone touches a tile, then it follows focus. Own state, so a slide change does not re-render Home. */
function HomeHero({ focused, slides, engaged }: { focused: Item | null; slides: HeroSlide[]; engaged: boolean }) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (engaged || slides.length < 2) return;
    const t = setInterval(() => setI((n) => n + 1), SLIDE_MS);
    return () => clearInterval(t);
  }, [engaged, slides]);
  const slide = !engaged && slides.length ? slides[i % slides.length] : null;
  return <Hero item={slide?.item ?? focused} kicker={slide?.kicker} rotating={!!slide} />;
}

export function Home() {
  const [continueItems, setContinue] = useState<Item[] | null>(null);
  const [carousels, setCarousels] = useState<Carousel[]>([]);
  const [lists, setLists] = useState<LetterboxdRow[]>([]);
  // Rows v1 from the companion when it has them; null = the Kometa carousels + Letterboxd lists as before
  const [rows, setRows] = useState<ServerRow[] | null>(null);
  const [focused, setFocused] = useState<Item | null>(null);
  // Specials wait for the manifest so they land between the right rows (no jump when the rows arrive)
  const [rowsReady, setRowsReady] = useState(false);
  const [specials, setSpecials] = useState<Special[]>([]);
  // Personal prefs (Home edit) when the server has them; a save reloads rows + specials
  const { available: personal, prefs } = usePrefs({ defer: true });
  const [editing, setEditing] = useState(false);
  const [reload, setReload] = useState(0);
  const [rowsFailed, setRowsFailed] = useState(false);
  const [slides, setSlides] = useState<HeroSlide[]>([]);
  // The mix runs until the person acts: focus the autofocused first tile does not count
  const [engaged, setEngaged] = useState(false);
  const userInput = useRef(false);
  useEffect(() => {
    const on = () => {
      userInput.current = true;
    };
    const evs = ["keydown", "pointerdown", "touchstart", "mousemove"] as const;
    evs.forEach((e) => window.addEventListener(e, on, { passive: true, once: true }));
    return () => evs.forEach((e) => window.removeEventListener(e, on));
  }, []);

  useEffect(() => {
    let alive = true;
    Promise.all([resumeItems().catch(() => []), nextUp().catch(() => [])]).then(([resume, next]) => {
      if (!alive) return;
      // Continue Watching first, then Next Up episodes of shows not already in it
      const seen = new Set(resume.map((i) => i.SeriesId ?? i.Id));
      const merged = [...resume, ...next.filter((n) => !seen.has(n.SeriesId ?? n.Id))];
      setContinue(merged);
      if (merged[0]) setFocused((f) => f ?? merged[0]);
    });
    setRowsFailed(false);
    serverRows("home")
      .then((r) => {
        if (r?.length) return r;
        // A 503 (the server couldn't tell who is asking) must not fall back to the unpersonalised carousels
        if (rowsBlocked("home")) {
          if (alive) setRowsFailed(true);
          return null;
        }
        return manifest().then((m) => {
          if (alive && m) {
            setCarousels(m.carousels.slice(0, MAX_CAROUSEL_ROWS));
            setLists(m.letterboxd.slice(0, MAX_LETTERBOXD_ROWS));
          }
          return null;
        });
      })
      .then((r) => {
        if (!alive) return;
        setRows(r);
        setRowsReady(true);
        if (r) rememberRowTitles(r);
      });
    getSpecials("home").then((s) => alive && setSpecials(s));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  // No Continue Watching: the hero starts on the first carousel's first title
  useEffect(() => {
    if (focused || continueItems === null || continueItems.length) return;
    // IPTV rows carry no library items: the hero starts on the first library row
    const lib = rows?.find((r) => r.itemIds.length);
    const first = lib ? itemsByIds(lib.itemIds) : carousels[0] ? carouselLoader(carousels[0])() : null;
    first?.then((items) => items[0] && setFocused((f) => f ?? items[0])).catch(() => undefined);
  }, [focused, continueItems, carousels, rows]);

  // Featured mix from the rows and specials already fetched; the items arrive in the same batch as the first rows
  useEffect(() => {
    if (!rowsReady || continueItems === null || !rows) return setSlides([]);
    let alive = true;
    const sources = heroSources(rows, specials);
    const ids = [...new Set(sources.flatMap((s) => s.ids))];
    if (!ids.length) return setSlides([]);
    itemsByIds(ids)
      .then((items) => {
        if (!alive) return;
        const byId = new Map(items.map((it) => [it.Id.replace(/-/g, ""), it]));
        const day = Math.floor((Date.now() - new Date(new Date().getFullYear(), 0, 0).getTime()) / 86_400_000);
        setSlides(
          buildSlides(
            sources.map((s) => ({ kicker: s.kicker, items: s.ids.map((id) => byId.get(id.replace(/-/g, ""))).filter((x): x is Item => !!x) })),
            excludedIds(continueItems),
            day,
          ),
        );
      })
      .catch(() => alive && setSlides([]));
    return () => {
      alive = false;
    };
  }, [rows, specials, rowsReady, continueItems]);

  const onFocusItem = useCallback((i: Item) => {
    setFocused(i);
    if (userInput.current) setEngaged(true);
  }, []);

  // Hovering or focusing a tile changes `focused` (the hero follows): unchanged elements keep React from re-rendering every row
  const body = useMemo(
    () =>
      withSpecials(
        rows
          ? rows.map((r) => serverRowElement(r, { onFocusItem }))
          : [
              ...carousels.map((c) => <Row key={c.id} title={c.title} load={carouselLoader(c)} onFocusItem={onFocusItem} />),
              ...lists.map((l) => <Row key={l.slug} title={LETTERBOXD_PREFIX + l.name} load={letterboxdLoader(l)} onFocusItem={onFocusItem} />),
            ],
        rowsReady ? specials : [],
      ),
    [rows, carousels, lists, specials, rowsReady, onFocusItem],
  );

  return (
    <div className="home">
      <HomeHero focused={focused} slides={slides} engaged={engaged} />
      <div className="rows">
        {continueItems && continueItems.length > 0 && <Row title="Continue Watching" items={continueItems} onFocusItem={onFocusItem} autoFocusFirst />}
        {/* Recordings: ready, recording now and scheduled, right after Continue Watching */}
        <RecordingsRow />
        {/* People with an IPTV provider: live sport right now, only when there are at least two */}
        <OnNowStrip title="Live sport on now" sportOnly min={2} checkProvider />
        {rowsFailed && (
          <RowsError
            onRetry={() => {
              dropPersonalCaches();
              setReload((n) => n + 1);
            }}
          />
        )}
        {!rowsFailed && body}
        {personal && prefs && rows && rows.length > 0 && (
          <button className="btn home-edit-open" data-nav onClick={() => setEditing(true)}>
            Edit Home
          </button>
        )}
      </div>
      {editing && prefs && rows && (
        <HomeEdit
            rows={rows}
            prefs={prefs}
            onClose={(saved) => {
              setEditing(false);
              if (saved) {
                dropPersonalCaches();
                setReload((n) => n + 1);
              }
            }}
          />
      )}
    </div>
  );
}
