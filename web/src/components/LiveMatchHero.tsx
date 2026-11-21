// Live match hero and colour-edge tiles (TV port: jellytip/iptv/JellytipLiveHero.kt, Game Centre look 2A). A full-width card washed
// in the two team colours with big crests, a gold-on-red LIVE tag (never a score or clock), Watch / From kick-off / More and, with more
// than one of his games live, ‹ › to cycle them. Fixtures carry no colours: they come from the match record (GET v1/sport/match/{id},
// the in-match palette's source), cached per match and fetched at most 3 at a time; neutral until then or when the server has none.
import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from "react";
import { hasLiveTv, logoSrc } from "../api/iptv";
import { refreshFixtures, useFixtures } from "../api/fixtures";
import { usePrefs } from "../api/me";
import { afterStartup } from "../lib/startup";
import { matchDetailCached } from "../api/matchInfo";
import { BASE, blend, onColor, pairColors, teamColors, type TeamColors } from "../lib/broadcast";
import { isFavourite, waysForFixture, type FxItem } from "../lib/gameCentre";
import { cycle, heroLine, heroTag, homePicks } from "../lib/liveHero";
import { go } from "../lib/router";
import { skippedNote } from "../lib/streamFail";
import { flashNote } from "./TasteButtons";
import { HeroArt, LeagueBadge, useArt } from "./FixtureArt";
import "./LiveMatchHero.css";

const NEUTRAL: TeamColors = teamColors(null);

// ---- team colours per match, from the match record ----
const colourCache = new Map<string, TeamColors>();
const misses = new Map<string, number>();
const MISS_TTL_MS = 10 * 60_000;
let running = 0;
let generation = 0;
const queue: (() => void)[] = [];
/** Runs [job] when one of the 3 slots is free; a job whose card has gone (or from before a session switch) is skipped, not fetched. */
const slot = <T,>(job: () => Promise<T>, wanted: () => boolean): Promise<T | null> =>
  new Promise<T | null>((resolve, reject) => {
    const gen = generation;
    const run = () => {
      if (gen !== generation || !wanted()) {
        resolve(null);
        queue.shift()?.();
        return;
      }
      running++;
      job()
        .then(resolve, reject)
        .finally(() => {
          running--;
          queue.shift()?.();
        });
    };
    if (running < 3) run();
    else queue.push(run);
  });
if (typeof window !== "undefined")
  window.addEventListener("jellytip-session", () => {
    generation++;
    colourCache.clear();
    misses.clear();
  });

/** The fixture's team colours (neutral until known); [lazy] = only once the element is on screen. */
export function useFixtureColours(f: Pick<FxItem, "matchId" | "homeColor" | "awayColor" | "awayAlt">, el?: RefObject<HTMLElement | null>): TeamColors {
  // The fixture list carries the colours (server 9415290): no request at all; same clash rule as the match record
  const own = f.homeColor || f.awayColor ? pairColors(f.homeColor, f.awayColor, f.awayAlt) : null;
  const id = own ? null : f.matchId;
  const [c, setC] = useState<TeamColors>(() => (id && colourCache.get(id)) || NEUTRAL);
  useEffect(() => {
    if (!id) return setC(NEUTRAL);
    const have = colourCache.get(id);
    if (have) return setC(have);
    setC(NEUTRAL);
    const miss = misses.get(id);
    if (miss && Date.now() - miss < MISS_TTL_MS) return;
    let alive = true;
    const gen = generation;
    const load = () =>
      void slot(() => matchDetailCached(id), () => alive).then(
        (d) => {
          if (d === null && !alive) return;
          if (gen !== generation) return;
          if (d && d !== "missing") {
            const tc = teamColors(d.fixture);
            colourCache.set(id, tc);
            if (alive) setC(tc);
          } else misses.set(id, Date.now());
        },
        () => misses.set(id, Date.now()),
      );
    const node = el?.current;
    if (!node || typeof IntersectionObserver === "undefined") {
      load();
      return () => {
        alive = false;
      };
    }
    const io = new IntersectionObserver((es) => {
      if (es.some((e) => e.isIntersecting)) {
        io.disconnect();
        load();
      }
    });
    io.observe(node);
    return () => {
      alive = false;
      io.disconnect();
    };
  }, [id, el]);
  // a fixture that just lost its own colours shows the remembered match-record ones at once (no neutral flash)
  return own ?? (id ? colourCache.get(id) : undefined) ?? c;
}

/** Crest hosts the page's CSP allows (public CDNs); anything else must be a companion-proxied path. */
const CDN = /^https:\/\/(a\.espncdn\.com|[a-z0-9-]+\.thesportsdb\.com)\//i;
const crestSrc = (u: string | null | undefined): string | null => (u ? (CDN.test(u) ? u : logoSrc(u, 192)) : null);
const initials = (name: string): string => {
  const w = name.replace(/[^\p{L}\p{N}\s]/gu, "").split(/\s+/).filter(Boolean);
  return (w.length > 1 ? w.slice(0, 3).map((x) => x[0]).join("") : (w[0] ?? "?").slice(0, 3)).toUpperCase();
};

export function BigCrest({ logo, name, colour, small = false }: { logo: string | null; name: string; colour: string; small?: boolean }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? null : crestSrc(logo);
  return (
    <span className={`lh-crest${small ? " small" : ""}`} style={{ background: colour, color: onColor(colour) }} aria-hidden>
      {src ? <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} /> : initials(name)}
    </span>
  );
}

/** The 2A hero for [picks] (cycling with ‹ ›). [kicker] labels it ("Your game is on", "Up next"). */
/** autoFocus: "always" = take focus on mount; "ifLost" = only when nothing has focus (the hero just replaced what had it). */
export function LiveMatchHero({ picks, nowSec, kicker, autoFocus, spoilers = false }: { picks: FxItem[]; nowSec: number; kicker?: string; autoFocus?: "always" | "ifLost"; spoilers?: boolean }) {
  const [index, setIndex] = useState(0);
  const ref = useRef<HTMLElement>(null);
  const f = picks[Math.min(index, picks.length - 1)];
  const c = useFixtureColours(f ?? { matchId: null });
  const art = useArt(f?.art, 960);
  useEffect(() => {
    // keyboard / remote only: on a touch screen a focus ring on Watch is noise
    const lost = !document.activeElement || document.activeElement === document.body;
    if ((autoFocus === "always" || (autoFocus === "ifLost" && lost)) && window.matchMedia?.("(hover: hover)").matches) ref.current?.querySelector<HTMLElement>("[data-hero-primary]")?.focus({ preventScroll: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (!f) return null;
  const w = waysForFixture(f, null, nowSec);
  const watch = w.ways.find((x) => x.kind === "live");
  const fromStart = w.ways.find((x) => x.kind === "catchup");
  const press = (path: string) => {
    if (w.skipped) flashNote(skippedNote(w.skipped));
    go(path);
  };
  const wash = c.known ? `linear-gradient(90deg, ${blend(c.home, 0.85)} 0%, ${BASE} 46%, ${BASE} 54%, ${blend(c.away, 0.85)} 100%)` : BASE;
  // With art: the art under a team-colour gradient (lib/artOverlay) on the dark base; without art or when it fails, the plain wash
  const style = { background: art.src ? BASE : wash } as CSSProperties;
  const n = picks.length;
  return (
    <section className={`lh${art.src ? " has-art" : ""}`} style={style} ref={ref} aria-label={`${heroTag(f, nowSec)}: ${f.away ? `${f.home} v ${f.away}` : f.home}`}>
      {art.src && <HeroArt key={art.src} src={art.src} colours={c} onError={art.failed} />}
      <BigCrest logo={f.homeLogo} name={f.homeShort ?? f.home} colour={c.home} />
      <div className="lh-mid">
        {art.src && <LeagueBadge key={f.badge ?? ""} path={f.badge} className="lh-badge" />}
        {kicker && <span className="lh-kicker">{kicker}</span>}
        <span className={`lh-tag${f.status === "in" ? " live" : ""}`}>{heroTag(f, nowSec)}</span>
        <h2 className="lh-title">
          <span className="full">{f.home}</span>
          <span className="short">{f.homeShort ?? f.home}</span>
          {f.away && (
            <>
              <span className="v">v</span>
              <span className="full">{f.away}</span>
              <span className="short">{f.awayShort ?? f.away}</span>
            </>
          )}
        </h2>
        <p className="lh-line">{heroLine(f, spoilers)}</p>
        <div className="lh-buttons">
          {watch ? (
            <button className="btn primary" data-nav data-hero-primary onClick={() => press(watch.path)}>
              ▶ Watch
            </button>
          ) : (
            w.noStream && <span className="lh-line">No stream found yet</span>
          )}
          {fromStart && (
            <button className="btn" data-nav {...(!watch ? { "data-hero-primary": true } : {})} onClick={() => press(fromStart.path)}>
              From kick-off
            </button>
          )}
          <button className="btn" data-nav {...(!watch && !fromStart ? { "data-hero-primary": true } : {})} onClick={() => go(`/games?fid=${encodeURIComponent(f.fid)}`)}>
            More
          </button>
        </div>
        {n > 1 && (
          <div className="lh-cycle">
            <button className="btn small" data-nav aria-label="Previous game" onClick={() => setIndex((i) => cycle(Math.min(i, n - 1), n, -1))}>
              ‹
            </button>
            <span>
              {Math.min(index, n - 1) + 1} of {n} of your games live
            </span>
            <button className="btn small" data-nav aria-label="Next game" onClick={() => setIndex((i) => cycle(Math.min(i, n - 1), n, 1))}>
              ›
            </button>
          </div>
        )}
      </div>
      {f.away ? <BigCrest logo={f.awayLogo} name={f.awayShort ?? f.away} colour={c.away} /> : <span className="lh-crest-gap" />}
    </section>
  );
}

/** Colour edges for a fixture tile (2A): the home colour on the left edge, the away colour on the right; none until known. */
/** Drawn by .gc-card.edged::before/::after from these vars, so the card's own box-shadow states (live ring, no-stream, hover, flash) stay. */
export function useEdgeStyle(f: Pick<FxItem, "matchId" | "away" | "homeColor" | "awayColor" | "awayAlt">, el: RefObject<HTMLElement | null>): CSSProperties | undefined {
  return edgeStyle(useFixtureColours(f, el), f);
}

/** The edge vars for known colours (for a card that also needs the colours themselves, e.g. its art wash). */
export function edgeStyle(c: TeamColors, f: Pick<FxItem, "away">): CSSProperties | undefined {
  if (!c.known) return undefined;
  return { "--edge-home": c.home, "--edge-away": f.away ? c.away : "transparent" } as CSSProperties;
}

/**
 * Home's live match picks: live games of his starred teams or followed leagues only (Ricki 21-Nov: no top-ranked fallback). Empty when none is live,
 * the person has no Live TV, or the fixtures route is missing. Re-checked every minute.
 */
export function useHomeLivePicks(): { picks: FxItem[]; nowSec: number; mine: boolean } {
  const [live, setLive] = useState<boolean | null>(null);
  useEffect(() => {
    let alive = true;
    void afterStartup()
      .then(() => hasLiveTv())
      .then((ok) => alive && setLive(ok));
    return () => {
      alive = false;
    };
  }, []);
  const { data } = useFixtures(live === true);
  const { prefs } = usePrefs({ defer: true });
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    if (live !== true) return;
    const t = window.setInterval(() => {
      setNow(Date.now() / 1000);
      void refreshFixtures();
    }, 60_000);
    return () => window.clearInterval(t);
  }, [live]);
  const refs = prefs?.sport.teamRefs;
  const muted = prefs?.sport.muted;
  const leagues = prefs?.sport.leagues;
  return useMemo(() => {
    const picks = live === true && data ? homePicks(data.items, now, refs ?? [], muted, leagues ?? []) : [];
    return { picks, nowSec: now, mine: picks.some((f) => isFavourite(f, refs ?? [])) };
  }, [live, data, now, refs, muted, leagues]);
}

/**
 * Home's top: the live match hero while a game is live (it takes the hero's place, no trailer behind it, as on the TV), else
 * [fallback] (the normal hero). Its own component, so the fixtures polling re-renders only this, not the whole Home tree.
 */
export function HomeTopHero({ fallback }: { fallback: ReactNode }) {
  const { picks, nowSec, mine } = useHomeLivePicks();
  const { prefs } = usePrefs({ defer: true });
  if (!picks.length) return <>{fallback}</>;
  // key: a different set of games starts again at the first one; focus only if the swap took it away from the old hero
  return (
    <div className="home-live">
      <LiveMatchHero key={picks.map((f) => f.fid).join("|")} picks={picks} nowSec={nowSec} kicker={mine ? "Your game is on" : "Live now"} autoFocus="ifLost" spoilers={!!prefs?.sport.spoilers} />
    </div>
  );
}
