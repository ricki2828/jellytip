import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { getItems, nextUp, resumeItems, uid, type Item } from "../api/jellyfin";
import { manifest, rowsBlocked, serverRows, specials as getSpecials, type Carousel, type LetterboxdRow, type ServerRow, type Special } from "../api/companion";
import { SpecialBlock } from "../components/specials/Specials";
import { Hero, IptvHero, type IptvHeroTile } from "../components/Hero";
import { onIptvFocus } from "../lib/iptvFocus";
import { buildSlides, excludedIds, heroSources, SLIDE_MS, type HeroSlide } from "../lib/heroMix";
import { Row } from "../components/Row";
import { itemsByIds, serverRowElement } from "../components/ServerRow";
import { rememberRowTitles } from "../lib/rowTitles";
import { HomeEdit, type Placement } from "../components/HomeEdit";
import { interleaveSpecials, planHome, readLocalHome, shouldDefer, TOP_KEY, WATCHED_SAMPLE, undash, writeLocalHome, type Cand, type HomePlan, type UserHome } from "../lib/homePlan";
import { OnNowStrip } from "../components/OnNowStrip";
import { LiveMatchHero, useHomeLivePicks } from "../components/LiveMatchHero";
import { RecordingsRow } from "../components/RecordingsRow";
import { ChannelsRow } from "../components/ChannelsRow";
import { homeLeadRows } from "../lib/channelsRow";
import { useHubTick } from "../api/collections";
import { iptvContinue, iptvNextAll } from "../api/iptv";
import { keepLibraryNext, type SeriesNextClaim } from "../lib/splitNext";
import type { ContinueEntry } from "../lib/continue";
import { actionsFor, buildContinue, optimisticHide, pruneHidden, readDismissed, writeDismissed, type ActionId, type RowEntry } from "../lib/continueRow";
import { markWatched } from "../api/continueActions";
import { ContinueTile } from "../components/ContinueTile";
import { flashNote } from "../components/TasteButtons";
import { RemindersRow } from "../components/Reminders";
import { UpcomingRow } from "../components/Upcoming";
import { savePrefs, usePrefs } from "../api/me";
import { RowsError } from "../components/RowsError";
import { dropPersonalCaches } from "../api/companion";
import { go } from "../lib/router";
import { useActiveJourney, type ActiveJourney } from "../api/journeysV1";
import { heroSlots, keepSlide, nextSlide } from "../lib/homeHero";
import { JourneyHero } from "../components/JourneyHero";

const MAX_CAROUSEL_ROWS = 10;
const MAX_LETTERBOXD_ROWS = 6;
const ROW_LIMIT = 24;
const GATE_MS = 1200; // first paint of the rows waits this long at most for rows, specials, prefs and watched data together

/** Rows the quality filter may judge by their library ids: plain lists. Ranked, person, collection and IPTV rows are shown as sent. */
const filterable = (r: ServerRow) => r.layout !== "franchise" && !r.person && !r.collection && !r.order.length && !r.missing.length && !r.iptvItems.length && !r.id.startsWith("trending-top10");

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

/**
 * The hero: his top live game (one slide at most), the active journey's step, then the featured mix, rotating until someone
 * touches a tile; then it follows focus. Own state (and its own fixtures poll), so a slide change does not re-render Home.
 * Order and "the slide on screen stays put" rules: lib/homeHero (TV JellytipHeroCarousel).
 */
function HomeHero({ focused, focusedIptv, slides, engaged, journey }: { focused: Item | null; focusedIptv: IptvHeroTile | null; slides: HeroSlide[]; engaged: boolean; journey: ActiveJourney | null }) {
  const { picks, nowSec, mine } = useHomeLivePicks();
  const { prefs } = usePrefs({ defer: true });
  const game = picks[0] ?? null;
  const mixKeys = useMemo(() => slides.map((s) => s.item.Id), [slides]);
  const journeyId = journey?.journey.summary.id ?? null;
  const slots = useMemo(() => heroSlots(game?.fid ?? null, journeyId, mixKeys), [game?.fid, journeyId, mixKeys]);
  const slotsRef = useRef(slots);
  slotsRef.current = slots;
  // Where the hero is: the slide on screen by key, so slides arriving or leaving never swap it (keepSlide)
  const pos = useRef<{ key: string | null; i: number }>({ key: null, i: 0 });
  const rotated = useRef(false);
  const [, setTick] = useState(0);
  const idx = keepSlide(slots, pos.current.key, pos.current.i, rotated.current);
  pos.current = { key: slots[idx]?.key ?? null, i: idx };
  // Pointer over or focus inside the hero: it holds still (a button must not change under the click), and gets a full slide after
  const [held, setHeld] = useState(false);
  // Held = pinned: the slide on screen keeps its place by key even before the first turn (a game or journey arriving
  // while he is on a slide's button never swaps it; keepSlide)
  const hold = () => {
    rotated.current = true;
    setHeld(true);
  };
  // Focus was inside the hero: when the slide under it changes anyway (its game ended), focus goes into the new slide
  const focusIn = useRef<HTMLElement | null>(null);
  const heroEl = useRef<HTMLDivElement | null>(null);
  const slideKey = engaged ? null : slots[idx]?.key ?? null;
  useLayoutEffect(() => {
    const was = focusIn.current;
    // Only when the focused element went away with its slide (not a click elsewhere that left focus on the page)
    if (!was || was.isConnected || document.activeElement !== document.body) return;
    focusIn.current = null;
    const el = heroEl.current?.querySelector<HTMLElement>("[data-nav], button, a[href]");
    if (el) el.focus({ preventScroll: true });
    else setHeld(false);
  }, [slideKey]);
  const n = slots.length;
  useEffect(() => {
    if (engaged || held || n < 2) return;
    const t = setInterval(() => {
      rotated.current = true;
      const s = slotsRef.current;
      const i = nextSlide(pos.current.i, s.length);
      pos.current = { key: s[i]?.key ?? null, i };
      setTick((k) => k + 1);
    }, SLIDE_MS);
    return () => clearInterval(t);
  }, [engaged, held, n]);
  const slot = engaged ? null : slots[idx] ?? null;
  let body: React.ReactNode;
  if (slot?.kind === "journey" && journey) body = <JourneyHero s={journey.journey.summary} j={journey.journey} />;
  else if (slot?.kind === "game" && game)
    body = (
      <div className="hero hero-game">
        <LiveMatchHero key={game.fid} picks={[game]} nowSec={nowSec} kicker={mine ? "Your game is on" : "Live now"} spoilers={!!prefs?.sport.spoilers} />
      </div>
    );
  else {
    const s = slot?.kind === "mix" && slot.index != null ? slides[slot.index] ?? null : null;
    body = focusedIptv && !s ? <IptvHero tile={focusedIptv} /> : <Hero item={s?.item ?? focused} kicker={s?.kicker} rotating={!!s} />;
  }
  return (
    <div
      ref={heroEl}
      className="home-hero"
      onPointerEnter={(e) => e.pointerType === "mouse" && hold()}
      onPointerLeave={(e) => e.pointerType === "mouse" && setHeld(false)}
      onFocusCapture={(e) => {
        focusIn.current = e.target as HTMLElement;
        hold();
      }}
      onBlurCapture={(e) => {
        // A blur to nothing: the focused button unmounted with its slide (the effect above moves focus back in), or a tap on a
        // blank area / the window losing focus; a frame later, focus that isn't in the hero and wasn't unmounted lets go
        if (!e.relatedTarget) {
          const hero = e.currentTarget;
          requestAnimationFrame(() => {
            const was = focusIn.current;
            if (was && !was.isConnected) return;
            if (!hero.contains(document.activeElement)) {
              focusIn.current = null;
              setHeld(false);
            }
          });
          return;
        }
        if (!e.currentTarget.contains(e.relatedTarget as Node)) {
          focusIn.current = null;
          setHeld(false);
        }
      }}
    >
      {body}
    </div>
  );
}

/**
 * Continue Watching's sources, kept for 10 min so Back to Home doesn't re-ask and re-order the row (Ricki 21-Nov: Back must land
 * where he was). Dropped on a session change, when playback stops or saves progress ("jellytip-watched"), and by a manual reload.
 */
const CONTINUE_TTL_MS = 10 * 60_000;
let continueMemo: { uid: string; at: number; v: ContinueSrc } | null = null;
if (typeof window !== "undefined") for (const ev of ["jellytip-session", "jellytip-watched"]) window.addEventListener(ev, () => (continueMemo = null));

interface ContinueSrc {
  resume: Item[];
  next: Item[];
  ic: ContinueEntry[];
  inx: ContinueEntry[];
  /** The server's merged next per split series: Jellyfin's next-up episode it disagrees with is left out (lib/splitNext). */
  claims?: ReadonlyMap<string, SeriesNextClaim>;
}

const NO_CLAIMS: ReadonlyMap<string, SeriesNextClaim> = new Map();
/** Jellyfin's Next Up minus the split-series episodes the merged next disagrees with (Celebrity Traitors: E6 when IPTV E3 is next). */
const libraryNext = (src: ContinueSrc): Item[] => (src.claims?.size ? src.next.filter((i) => keepLibraryNext(i.SeriesId, i.Id, i.UserData?.PlaybackPositionTicks ?? 0, src.claims!)) : src.next);

export function Home() {
  useHubTick();
  const journey = useActiveJourney();
  const [src, setSrc] = useState<ContinueSrc | null>(null);
  // Tiles hidden by an action in flight (or just done): key -> the server's last-played time then. Keys, not a snapshot of
  // the row, so two actions never undo each other.
  const [hidden, setHidden] = useState<ReadonlyMap<string, number>>(new Map());
  // Newest fetch wins: an older answer arriving late must not overwrite it
  const seq = useRef(0);
  const [undo, setUndo] = useState<{ msg: string; run: () => void } | null>(null);
  const [dismissVer, setDismissVer] = useState(0);
  const user = uid();
  const entries = useMemo(
    () => (src ? buildContinue(src.resume, libraryNext(src), src.ic, src.inx, { hidden, dismissed: readDismissed(localStorage, user) }) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [src, hidden, user, dismissVer],
  );
  const continueItems = useMemo(() => (entries ? entries.flatMap((e) => (e.lib ? [e.lib] : [])) : null), [entries]);
  // Another person signing in: nothing of the last one's row stays
  useEffect(() => {
    const on = () => {
      setSrc(null);
      setHidden(new Map());
      setUndo(null);
      seq.current++;
    };
    window.addEventListener("jellytip-session", on);
    return () => window.removeEventListener("jellytip-session", on);
  }, []);
  const [carousels, setCarousels] = useState<Carousel[]>([]);
  const [lists, setLists] = useState<LetterboxdRow[]>([]);
  // Rows v1 from the companion when it has them; null = the Kometa carousels + Letterboxd lists as before
  const [rows, setRows] = useState<ServerRow[] | null>(null);
  const [focused, setFocused] = useState<Item | null>(null);
  // Specials wait for the manifest so they land between the right rows (no jump when the rows arrive)
  const [rowsReady, setRowsReady] = useState(false);
  const [specials, setSpecials] = useState<Special[]>([]);
  const [specialsReady, setSpecialsReady] = useState(false);
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
    // Continue Watching paints from the library at once. The IPTV part (in progress / up next) is asked for after the
    // rows request is answered, so it never holds the first screen up or takes a connection from it, and is merged in when it arrives.
    const me = uid();
    const mine = ++seq.current;
    const apply = (v: ContinueSrc) => {
      if (!alive || uid() !== me || seq.current !== mine) return; // late: someone else signed in, or a newer fetch landed
      setSrc(v);
    };
    const memo = reload === 0 && continueMemo && continueMemo.uid === me && Date.now() - continueMemo.at < CONTINUE_TTL_MS ? continueMemo.v : null;
    if (memo) apply(memo);
    const libP = memo
      ? Promise.resolve(null)
      : Promise.all([resumeItems().catch(() => [] as Item[]), nextUp().catch(() => [] as Item[])]).then(([resume, next]) => {
          apply({ resume, next, ic: [], inx: [] });
          return { resume, next };
        });
    setRowsFailed(false);
    const rowsP = serverRows("home")
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
    void Promise.all([libP, rowsP.catch(() => undefined)]).then(async ([lib]) => {
      if (!lib) return; // from the memo: the IPTV part is in it
      const { resume, next } = lib;
      const [ic, nx] = await Promise.all([iptvContinue().catch(() => [] as ContinueEntry[]), iptvNextAll().catch(() => ({ items: [] as ContinueEntry[], claims: NO_CLAIMS }))]);
      const inx = nx.items;
      const claims = nx.claims;
      if (ic.length || inx.length || claims.size) apply({ resume, next, ic, inx, claims });
      if (alive && uid() === me && seq.current === mine) continueMemo = { uid: me, at: Date.now(), v: { resume, next, ic, inx, claims } };
    });
    getSpecials("home")
      .then((s) => alive && setSpecials(s))
      .catch(() => undefined)
      .finally(() => alive && setSpecialsReady(true));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  // The hero starts on the first library tile of Continue Watching
  useEffect(() => {
    if (continueItems?.[0]) setFocused((f) => f ?? continueItems[0]);
  }, [continueItems]);

  const onAction = useCallback(
    (a: ActionId, e: RowEntry, wrap: HTMLElement | null) => {
      const me = uid();
      // Focus moves to a neighbouring tile, else the next (or previous) row, never the page top, when the tile goes
      const row = wrap?.closest("section.row");
      const target =
        (wrap?.nextElementSibling ?? wrap?.previousElementSibling)?.querySelector<HTMLElement>("button.card") ??
        row?.nextElementSibling?.querySelector<HTMLElement>("[data-nav]") ??
        row?.previousElementSibling?.querySelector<HTMLElement>("[data-nav]") ??
        document.querySelector<HTMLElement>(".home [data-nav]");
      const focusAway = () => requestAnimationFrame(() => target && document.contains(target) && target.focus());
      const hide = (k: string) => {
        setHidden((h) => new Map(h).set(k, e.at));
        focusAway();
      };
      const unhide = (k: string) =>
        setHidden((h) => {
          const n = new Map(h);
          n.delete(k);
          return n;
        });
      const refresh = () => {
        const mine = ++seq.current;
        return Promise.all([resumeItems().catch(() => null), nextUp().catch(() => null), iptvContinue().catch(() => null), iptvNextAll().catch(() => null)]).then(([resume, next, ic, nx]) => {
          if (uid() !== me || seq.current !== mine || !resume || !next) return;
          const v: ContinueSrc = { resume, next, ic: ic ?? [], inx: nx?.items ?? [], claims: nx?.claims ?? NO_CLAIMS };
          setSrc(v);
          continueMemo = { uid: me, at: Date.now(), v };
          // Hides for items the server no longer returns, or has since seen played again, are done with
          const present = buildContinue(resume, libraryNext(v), v.ic, v.inx);
          setHidden((h) => pruneHidden(h, present));
        });
      };
      if (a === "remove") {
        // Local only: nothing on the server changes
        writeDismissed(localStorage, me, e.key, true, e.at);
        setDismissVer((n) => n + 1);
        focusAway();
        setUndo({
          msg: "Hidden on this device.",
          run: () => {
            if (uid() !== me) return;
            writeDismissed(localStorage, me, e.key, false);
            setDismissVer((n) => n + 1);
            setUndo(null);
          },
        });
      } else if (a === "watched" && actionsFor(e).watched) {
        void optimisticHide({ hide, unhide, notify: flashNote }, e.key, () => markWatched(e), "Couldn't mark it as watched. Try again.", "Marked as watched").then((ok) => {
          if (ok) continueMemo = null; // even if the refresh below fails, Back must not bring the item back from the memo
          if (ok) void refresh();
        });
      }
    },
    [],
  );

  useEffect(() => {
    if (!undo) return;
    const t = window.setTimeout(() => setUndo(null), 7000);
    return () => window.clearTimeout(t);
  }, [undo]);

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

  // An IPTV tile in focus shows its own hero (and trailer); a Jellyfin tile takes it back
  const [focusedIptv, setFocusedIptv] = useState<IptvHeroTile | null>(null);
  useEffect(
    () =>
      onIptvFocus((t) => {
        setFocusedIptv(t);
        if (userInput.current) setEngaged(true);
      }),
    [],
  );
  const onFocusItem = useCallback((i: Item) => {
    setFocusedIptv(null);
    setFocused(i);
    if (userInput.current) setEngaged(true);
  }, []);

  // ---- Row plan: first screen / More rows / left out (rules in lib/homePlan.ts) ----
  const [local, setLocal] = useState<UserHome>(() => readLocalHome(localStorage, uid()));
  useEffect(() => {
    const on = () => setLocal(readLocalHome(localStorage, uid()));
    window.addEventListener("jellytip-session", on);
    return () => window.removeEventListener("jellytip-session", on);
  }, []);
  const userHome: UserHome = useMemo(() => (personal && prefs ? { pinned: prefs.home.pinned, hidden: prefs.home.hidden } : personal === false ? local : { pinned: [], hidden: [] }), [personal, prefs, local]);

  const cands = useMemo<Cand[]>(() => {
    const out: Cand[] = [];
    if (rows) {
      for (const r of rows) out.push({ key: r.id, ids: filterable(r) ? r.itemIds.map(undash) : null, count: r.itemIds.length + r.iptvItems.length + r.missing.length });
    } else {
      for (const c of carousels) out.push({ key: c.id, ids: null, count: MIN_UNKNOWN });
      for (const l of lists) out.push({ key: l.slug, ids: null, count: l.itemIds.length });
    }
    let pickTaken = false;
    for (const s of specials) {
      const pick = s.layout === "tonights_pick" && !pickTaken;
      if (pick) pickTaken = true;
      out.push({ key: `special-${s.id}`, ids: null, count: MIN_UNKNOWN, pick });
    }
    return out;
  }, [rows, carousels, lists, specials]);

  // Rows, specials and prefs are all in (or GATE_MS has passed) before the first plan, so nothing lands above a row late
  const [gateOpen, setGateOpen] = useState(false);
  useEffect(() => {
    setGateOpen(false);
    const t = window.setTimeout(() => setGateOpen(true), GATE_MS);
    return () => window.clearTimeout(t);
  }, [reload]);
  const inputsReady = rowsReady && specialsReady && personal !== undefined;
  const [watched, setWatched] = useState<ReadonlySet<string> | null>(null);
  const [watchedReady, setWatchedReady] = useState(false);
  useEffect(() => setWatchedReady(false), [rows]);
  useEffect(() => {
    if (!rows) return setWatchedReady(true);
    if (!inputsReady) return;
    let alive = true;
    // Only rows that could land on the first screen (with spare for ones the filter drops); More rows are not looked up
    const near = new Set(planHome(interleaveSpecials(cands, specials), userHome, null, 2 * MAX_DISCOVERY_LOOKUP).visible);
    const ids = [...new Set(rows.filter((r) => filterable(r) && near.has(r.id)).flatMap((r) => r.itemIds.slice(0, WATCHED_SAMPLE)))];
    (ids.length ? itemsByIds(ids) : Promise.resolve([] as Item[]))
      .then((items) => alive && setWatched(new Set(items.filter((it) => it.UserData?.Played).map((it) => undash(it.Id)))))
      .catch(() => undefined)
      .finally(() => alive && setWatchedReady(true));
    return () => {
      alive = false;
    };
  }, [rows, inputsReady, cands, specials, userHome]);
  const planReady = rowsReady && (gateOpen || (inputsReady && watchedReady));
  const target = useMemo<HomePlan | null>(() => (planReady ? planHome(interleaveSpecials(cands, specials), userHome, watched) : null), [planReady, cands, specials, userHome, watched]);

  // What is on screen. A late change that would move the row in focus waits until focus leaves that spot.
  const [shown, setShown] = useState<HomePlan | null>(null);
  const shownRef = useRef<HomePlan | null>(null);
  const targetRef = useRef<HomePlan | null>(null);
  const rowsEl = useRef<HTMLDivElement>(null);
  // Ricki 7-Nov: show every row, no "More rows" toggle (all rows start open)
  const moreOpenRef = useRef(true);
  const [moreOpen, setMoreOpen] = useState(true);
  moreOpenRef.current = moreOpen;
  const seqOf = (p: HomePlan) => (moreOpenRef.current ? [...p.visible, ...p.more] : p.visible);
  /** Where focus is now, asked of the DOM each time (a row that unmounted under focus leaves nothing stale). */
  const focusKeyNow = (): string | null => {
    const box = rowsEl.current;
    const el = document.activeElement;
    if (!box || !el || !box.contains(el)) return null;
    const k = el.closest<HTMLElement>("[data-rowkey]")?.dataset.rowkey;
    if (k) return k;
    const first = box.querySelector("[data-rowkey]");
    return first && first.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING ? TOP_KEY : null;
  };
  const settle = useCallback(() => {
    const t = targetRef.current;
    const cur = shownRef.current;
    if (!t || t === cur) return;
    if (cur && shouldDefer(seqOf(cur), seqOf(t), focusKeyNow())) return;
    shownRef.current = t;
    setShown(t);
  }, []);
  useEffect(() => {
    targetRef.current = target;
    settle();
  }, [target, settle]);
  // Another person signing in: plan again from scratch
  useEffect(() => {
    const on = () => {
      shownRef.current = null;
      setShown(null);
      setMoreOpen(true);
      settle();
    };
    window.addEventListener("jellytip-session", on);
    return () => window.removeEventListener("jellytip-session", on);
  }, [settle]);
  const rowBlur = useCallback(
    (e: React.FocusEvent) => {
      // Focus left the rows (or went nowhere): a held change may land now
      if (!e.relatedTarget || !e.currentTarget.contains(e.relatedTarget as Node)) requestAnimationFrame(settle);
    },
    [settle],
  );

  // Hovering or focusing a tile changes `focused` (the hero follows): unchanged elements keep React from re-rendering every row
  const elements = useMemo(() => {
    const m = new Map<string, React.ReactNode>();
    if (rows) for (const r of rows) {
      const ids = shown?.ids[r.id];
      m.set(r.id, serverRowElement(ids ? { ...r, itemIds: ids } : r, { onFocusItem }));
    }
    else {
      for (const c of carousels) m.set(c.id, <Row key={c.id} title={c.title} load={carouselLoader(c)} onFocusItem={onFocusItem} />);
      for (const l of lists) m.set(l.slug, <Row key={l.slug} title={LETTERBOXD_PREFIX + l.name} load={letterboxdLoader(l)} onFocusItem={onFocusItem} />);
    }
    for (const s of specials) m.set(`special-${s.id}`, <SpecialBlock key={`special-${s.id}`} s={s} />);
    return m;
  }, [rows, carousels, lists, specials, shown, onFocusItem]);
  const wrap = (k: string) => {
    const el = elements.get(k);
    return el ? (
      <div key={k} data-rowkey={k} style={{ display: "contents" }}>
        {el}
      </div>
    ) : null;
  };
  const placement = useMemo(() => {
    const p: Record<string, Placement> = {};
    if (target) {
      target.visible.forEach((k) => (p[k] = "first"));
      target.more.forEach((k) => (p[k] = "more"));
      target.dropped.forEach((k) => (p[k] = "auto"));
    }
    return p;
  }, [target]);
  const saveHome = useCallback(
    async (home: { pinned: string[]; hidden: string[]; order: string[]; specialsPerDay: number }) => {
      if (personal) return savePrefs({ home });
      const h = { pinned: home.pinned, hidden: home.hidden };
      if (!writeLocalHome(localStorage, uid(), h)) return "Couldn't save on this device.";
      setLocal(h);
      return null;
    },
    [personal],
  );

  return (
    <div className="home">
      {undo && (
        <p className="taste-note continue-undo" role="status">
          {undo.msg}{" "}
          <button className="btn small" data-nav onClick={undo.run}>
            Undo
          </button>
        </p>
      )}
      {/* His live game is one slide of the hero (Ricki 22-Nov), not a takeover */}
      <HomeHero focused={focused} focusedIptv={focusedIptv} slides={slides} engaged={engaged} journey={journey} />
      <div className="rows" ref={rowsEl} onFocusCapture={settle} onBlurCapture={rowBlur}>
        {/* Continue watching, then My Channels (Ricki 23-Nov; first when there is no Continue row), then reminders, recordings,
            live sport and upcoming: order in lib/channelsRow.homeLeadRows */}
        {homeLeadRows(!!entries && entries.length > 0).map((k) => {
          switch (k) {
            case "continue":
              return (
                <Row
                  key={k}
                  title="Continue Watching"
                  items={[]}
                  extra={entries!.map((e, i) => ({ id: e.key, node: <ContinueTile key={e.key} entry={e} autoFocus={i === 0} onFocusItem={onFocusItem} onAction={onAction} /> }))}
                />
              );
            case "mychannels":
              return <ChannelsRow key={k} />;
            case "reminders":
              return <RemindersRow key={k} />;
            case "recordings":
              return <RecordingsRow key={k} />;
            case "livesport":
              // People with an IPTV provider: live sport right now, only when there are at least two
              return <OnNowStrip key={k} title="Live sport on now" sportOnly min={2} checkProvider />;
            case "upcoming":
              return <UpcomingRow key={k} />;
          }
        })}
        {rowsFailed && (
          <RowsError
            onRetry={() => {
              dropPersonalCaches();
              setReload((n) => n + 1);
            }}
          />
        )}
        {!rowsFailed && !shown && (
          <div className="home-skel" aria-hidden="true">
            <div />
            <div />
            <div />
          </div>
        )}
        {!rowsFailed && shown && shown.visible.map(wrap)}
        {!rowsFailed && shown && moreOpen && shown.more.map(wrap)}
        {/* In flow at the end of Home (was absolutely positioned over the hero and overlapped its buttons) */}
        <div className="home-open">
          <button className="btn" data-nav onClick={() => go("/tonight")}>Pick for tonight</button>
          {rows && rows.length > 0 && (personal ? !!prefs : personal === false) && (
            <button className="btn" data-nav onClick={() => setEditing(true)}>
              Edit Home
            </button>
          )}
        </div>
      </div>
      {editing && rows && (
        <HomeEdit
          rows={rows}
          home={personal && prefs ? prefs.home : { ...local, order: local.pinned, specialsPerDay: 3 }}
          onSave={saveHome}
          placement={placement}
          specials={!!personal}
          reorder={!!personal}
          onClose={(saved) => {
            setEditing(false);
            if (saved && personal) {
              dropPersonalCaches();
              setReload((n) => n + 1);
            }
          }}
        />
      )}
    </div>
  );
}

const MIN_UNKNOWN = 99;
const MAX_DISCOVERY_LOOKUP = 4; // carousels and specials: size not known up front, so the minimum never applies
