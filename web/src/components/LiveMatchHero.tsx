// Live match hero and colour-edge tiles (TV port: jellytip/iptv/JellytipLiveHero.kt, Game Centre look 2A). A full-width card washed
// in the two team colours with big crests, a gold-on-red LIVE tag (never a score or clock), Watch / From kick-off / More and, with more
// than one of his games live, ‹ › to cycle them. Fixtures carry no colours: they come from the match record (GET v1/sport/match/{id},
// the in-match palette's source), cached per match and fetched at most 3 at a time; neutral until then or when the server has none.
import { useEffect, useMemo, useRef, useState, type CSSProperties, type RefObject } from "react";
import { hasLiveTv, logoSrc } from "../api/iptv";
import { refreshFixtures, useFixtures } from "../api/fixtures";
import { usePrefs } from "../api/me";
import { afterStartup } from "../lib/startup";
import { matchDetailCached } from "../api/matchInfo";
import { BASE, blend, onColor, teamColors, type TeamColors } from "../lib/broadcast";
import { isFavourite, waysForFixture, type FxItem } from "../lib/gameCentre";
import { cycle, heroLine, heroTag, homePicks } from "../lib/liveHero";
import { go } from "../lib/router";
import { skippedNote } from "../lib/streamFail";
import { flashNote } from "./TasteButtons";
import "./LiveMatchHero.css";

const NEUTRAL: TeamColors = teamColors(null);

// ---- team colours per match, from the match record ----
const colourCache = new Map<string, TeamColors>();
const misses = new Map<string, number>();
const MISS_TTL_MS = 10 * 60_000;
let running = 0;
const queue: (() => void)[] = [];
const slot = <T,>(job: () => Promise<T>): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const run = () => {
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
    colourCache.clear();
    misses.clear();
  });

/** The fixture's team colours (neutral until known); [lazy] = only once the element is on screen. */
export function useFixtureColours(f: Pick<FxItem, "matchId">, el?: RefObject<HTMLElement | null>): TeamColors {
  const id = f.matchId;
  const [c, setC] = useState<TeamColors>(() => (id && colourCache.get(id)) || NEUTRAL);
  useEffect(() => {
    if (!id) return setC(NEUTRAL);
    const have = colourCache.get(id);
    if (have) return setC(have);
    setC(NEUTRAL);
    const miss = misses.get(id);
    if (miss && Date.now() - miss < MISS_TTL_MS) return;
    let alive = true;
    const load = () =>
      void slot(() => matchDetailCached(id)).then(
        (d) => {
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
  return c;
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
export function LiveMatchHero({ picks, nowSec, kicker, autoFocus = false }: { picks: FxItem[]; nowSec: number; kicker?: string; autoFocus?: boolean }) {
  const [index, setIndex] = useState(0);
  const ref = useRef<HTMLElement>(null);
  const f = picks[Math.min(index, picks.length - 1)];
  const c = useFixtureColours(f ?? { matchId: null });
  useEffect(() => {
    // keyboard / remote only: on a touch screen a focus ring on Watch is noise
    if (autoFocus && window.matchMedia?.("(hover: hover)").matches) ref.current?.querySelector<HTMLElement>("[data-hero-primary]")?.focus({ preventScroll: true });
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
  const style = { background: wash } as CSSProperties;
  const n = picks.length;
  return (
    <section className="lh" style={style} ref={ref} aria-label={`${heroTag(f, nowSec)}: ${f.away ? `${f.home} v ${f.away}` : f.home}`}>
      <BigCrest logo={f.homeLogo} name={f.homeShort ?? f.home} colour={c.home} />
      <div className="lh-mid">
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
        <p className="lh-line">{heroLine(f)}</p>
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
export function useEdgeStyle(f: Pick<FxItem, "matchId" | "away">, el: RefObject<HTMLElement | null>): CSSProperties | undefined {
  const c = useFixtureColours(f, el);
  if (!c.known) return undefined;
  return { boxShadow: `inset 5px 0 0 ${c.home}${f.away ? `, inset -5px 0 0 ${c.away}` : ""}` };
}

/**
 * Home's live match picks (TV: Ricki 21-Nov, option 1): his teams' live games, else the top-ranked live one. Empty when no game is live,
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
  return useMemo(() => {
    const picks = live === true && data ? homePicks(data.items, now, refs ?? [], muted) : [];
    return { picks, nowSec: now, mine: picks.some((f) => isFavourite(f, refs ?? [])) };
  }, [live, data, now, refs, muted]);
}

/** Home's hero while a game is live: it takes the hero's place (no trailer behind it), as on the TV. */
export function HomeLiveHero({ picks, nowSec, mine }: { picks: FxItem[]; nowSec: number; mine: boolean }) {
  if (!picks.length) return null;
  // key: a different set of games starts again at the first one
  return (
    <div className="home-live">
      <LiveMatchHero key={picks.map((f) => f.fid).join("|")} picks={picks} nowSec={nowSec} kicker={mine ? "Your game is on" : "Live now"} />
    </div>
  );
}
