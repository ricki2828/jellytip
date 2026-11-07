// The in-event "Live" drawer in the player (plan: ~/ai/plans/jellytip-live-sport.md "In-event panel" and "Live panel: pop, context,
// performance"): a scoreboard strip in the team colours, pill tabs (Stats · Plays · Odds · Chat · Fantasy), split-bar stats, leader
// cards, a plays timeline, the odds bar with a sparkline and a per-sport "situation". Polls every 15 s while open only; rows are
// memoised and keyed so a poll re-renders only what changed; motion is transform and opacity only.
import { memo, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { LiveSource } from "./Companion/useLiveFeed";
import { liveStats, matchDetailCached } from "../api/matchInfo";
import { agoText, applyReveal, liveTabs, maskText, mayShow, newMoments, pct, revealAll, situationBlocks, sparkPoints, statShare, tabsFor, volumeText, FLAG_LABEL, type Block, type Game, type LeaderRow, type LiveStats, type Moment, type Reveal, type TowerRow } from "../lib/liveStats";
import { blowoutLine, closerGame, deciderRows, favouriteSide, gameStateOf, recapPlays, resultLine, type CloserGame, type GameState, type Row } from "../lib/gameState";
import { startsText } from "../lib/gameCentre";
import { playersInGame } from "../lib/fantasyGame";
import { useFixtures } from "../api/fixtures";
import { go } from "../lib/router";
import { advanceNewKeys, newKeysInit, type NewKeysState } from "../lib/newKeys";
import { flagOf, readable, type Fixture } from "../lib/matchInfo";
import { GOLD, onColor, teamColors, washBackground } from "../lib/broadcast";
import { Odo } from "./Odo";
import { ThinPill, WinnerList } from "./WinnerList";
import { usePrefs } from "../api/me";
import "../fonts";
import { logoSrc } from "../api/iptv";
import { FantasyTab, useRevealedFantasy } from "./Fantasy";
import { FantasyYourPlayers } from "./FantasyYourPlayers";
import { useFantasy } from "../api/fantasy";
import { isNfl } from "../lib/fantasy";
import { announceFlips } from "./FlipAlerts";
import { SheetClose, useEscapeClose } from "./BackArrow";
import "../screens/iptv/iptv.css";
import "./LiveDrawerState.css";

type Tab = "stats" | "plays" | "odds" | "chat" | "fantasy";
const REFRESH_MS = 15_000;
const NOOP_SUB = () => () => undefined;
const NOOP_GET = () => ({ raw: null, shown: null, fixture: null, state: "loading" as const });
const OUT_MS = 200;
const FLASH_MS = 3200;

const ICON: Record<Tab, string> = {
  stats: "M4 20V10M10 20V4M16 20v-7M22 20H2",
  plays: "M5 6h14M5 12h14M5 18h9",
  odds: "M3 17l6-6 4 4 8-9",
  chat: "M4 5h16v11H9l-5 4Z",
  fantasy: "M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.2 6.4 20.2l1.1-6.2L3 9.6l6.2-.9Z",
};
const LABEL: Record<Tab, string> = { stats: "Stats", plays: "Plays", odds: "Odds", chat: "Chat", fantasy: "Fantasy" };

const CDN = /^https:\/\/(a\.espncdn\.com|[a-z0-9-]+\.thesportsdb\.com)\//i;
const imgSrc = (u: string | null | undefined, size: 96 | 192 = 96): string | null => (u ? (CDN.test(u) ? u : /^https?:\/\//i.test(u) ? null : logoSrc(u, size)) : null);

/** Keys that are new since the last batch (the first batch is never new). Recomputed only when the key list changes. */
function useNewKeys(keys: string[]): Set<string> {
  const st = useRef<NewKeysState>(null as unknown as NewKeysState);
  if (!st.current) st.current = newKeysInit();
  return advanceNewKeys(st.current, keys);
}

function Crest({ f, side }: { f: Fixture | null; side: "home" | "away" }) {
  const s = f?.[side];
  const src = imgSrc(s?.logo);
  const [failed, setFailed] = useState(false);
  // Tennis: a country flag instead of a crest
  if (!src && s?.country && flagOf(s.country)) return <span className="ld-crest flag" aria-label={s.country}>{flagOf(s.country)}</span>;
  return src && !failed ? <img className="ld-crest" src={src} alt="" width={34} height={34} decoding="async" onError={() => setFailed(true)} /> : <span className="ld-crest blank">{(s?.short ?? s?.name ?? side).slice(0, 3)}</span>;
}

/** The score shown, held back while a moment's wipe covers the strip so the swap happens underneath it. */
function useCoveredScore(score: LiveStats["score"], flashN: number | null) {
  const [shown, setShown] = useState(score);
  useEffect(() => {
    if (flashN == null) return setShown(score);
    const t = window.setTimeout(() => setShown(score), 350);
    return () => window.clearTimeout(t);
  }, [score?.home, score?.away, flashN]);
  return shown;
}

/** Scoreboard strip (Broadcast): angled team-colour slabs with crest, name and score, then a clock row; a moment wipes the scoring team's colour across it. */
function Strip({ d, f, flash }: { d: LiveStats; f: Fixture | null; flash: { label: string; team: "home" | "away" | null; n: number } | null }) {
  const c = teamColors(f);
  const live = d.status === "in" || d.status === "live";
  const score = useCoveredScore(d.score, flash?.n ?? null);
  const mc = flash?.team === "away" ? c.away : flash?.team === "home" ? c.home : GOLD;
  const name = (side: "home" | "away") => (f?.[side].short ?? f?.[side].name ?? (side === "home" ? "HOME" : "AWAY")).toUpperCase();
  if (f && f.kind !== "match") {
    // F1, golf, RedZone: no teams. The title on a series-colour (RedZone: gold) slab
    return (
      <div className="bc-stripbox">
        <div className="bc-strip event">
          <div className="bc-slab l" style={{ ["--sc" as string]: f.kind === "multi" ? GOLD : c.home, ["--sx" as string]: onColor(f.kind === "multi" ? GOLD : c.home) }}>
            <span className="nm">{f.title}</span>
          </div>
          {flash && (
            <div key={flash.n} className="bc-moment" style={{ ["--mc" as string]: c.home, ["--mx" as string]: onColor(c.home) }}>
              <b>{flash.label}</b>
            </div>
          )}
        </div>
        <div className="bc-clock">
          {d.period && <span className="q">{d.period}</span>}
          {live && <i className="mi-pulse" aria-hidden />}
          <span>{d.clock ?? (live ? "LIVE" : d.status === "post" ? "FINAL" : "")}</span>
        </div>
      </div>
    );
  }
  return (
    <div className="bc-stripbox">
      <div className="bc-strip">
        <div className="bc-slab l" style={{ ["--sc" as string]: c.home, ["--sx" as string]: onColor(c.home) }}>
          <Crest f={f} side="home" />
          <span className="nm">{name("home")}</span>
          <strong className="sc">{score ? <Odo value={score.home} /> : "–"}</strong>
        </div>
        <div className="bc-slab r" style={{ ["--sc" as string]: c.away, ["--sx" as string]: onColor(c.away) }}>
          <strong className="sc">{score ? <Odo value={score.away} /> : "–"}</strong>
          <span className="nm">{name("away")}</span>
          <Crest f={f} side="away" />
        </div>
        {flash && (
          <div key={flash.n} className="bc-moment" style={{ ["--mc" as string]: mc, ["--mx" as string]: onColor(mc) }}>
            <b>{flash.label}</b>
            {flash.team && f && <span>{f[flash.team].name}</span>}
          </div>
        )}
      </div>
      <div className="bc-clock">
        {d.period && <span className="q">{d.period}</span>}
        {live && <i className="mi-pulse" aria-hidden />}
        <span>{d.clock ?? (live ? "LIVE" : d.status === "post" ? "FT" : "")}</span>
      </div>
    </div>
  );
}

const StatRow = memo(function StatRow({ label, home, away, hc, ac }: { label: string; home: string; away: string; hc: string; ac: string }) {
  const sh = statShare(home, away);
  return (
    <div className="ld-stat">
      <div className="ld-stat-n">
        <b>{home}</b>
        <span>{label}</span>
        <b>{away}</b>
      </div>
      {sh != null && (
        <div className="ld-split" aria-hidden>
          <i style={{ width: `${sh * 100}%`, background: hc }} />
          <i style={{ width: `${(1 - sh) * 100}%`, background: ac }} />
        </div>
      )}
    </div>
  );
});

const LeaderCard = memo(function LeaderCard({ name, team, line, headshot, color }: { name: string; team: string | null; line: string; headshot: string | null; color: string }) {
  const src = imgSrc(headshot);
  const [failed, setFailed] = useState(false);
  return (
    <li className="ld-leader">
      {src && !failed ? <img src={src} alt="" loading="lazy" decoding="async" style={{ borderColor: color }} onError={() => setFailed(true)} /> : <span className="ld-initial" style={{ borderColor: color }}>{name.slice(0, 1)}</span>}
      <span>
        <strong>{name}</strong>
        <small>{[team, line].filter(Boolean).join(" · ")}</small>
      </span>
    </li>
  );
});

const PlayRow = memo(function PlayRow({ when, text, scoring, delta, color, fresh }: { when: string; text: string; scoring: boolean; delta: number | null; color: string; fresh: boolean }) {
  return (
    <li className={`ld-play${scoring ? " score" : ""}${fresh ? " ld-new" : ""}`}>
      <i style={{ background: color }} aria-hidden />
      <span className="ld-when">{when}</span>
      <span className="ld-text">{text}</span>
      {scoring && delta != null && <b className="ld-delta">+{delta}</b>}
    </li>
  );
});

const ChatRow = memo(function ChatRow({ author, text, fresh }: { author: string; text: string; fresh: boolean }) {
  return (
    <li className={fresh ? "ld-new" : undefined}>
      <strong>{author}</strong> {maskText(text)}
    </li>
  );
});

function Spark({ series, color }: { series: { t: number; p: number }[]; color: string }) {
  const pts = useMemo(() => sparkPoints(series, 200, 36), [series]);
  if (!pts) return null;
  return (
    <svg className="ld-spark" viewBox="0 0 200 36" preserveAspectRatio="none" aria-label="Probability over the last 30 minutes" role="img">
      <polyline points={pts} fill="none" stroke={color} strokeWidth="2.2" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** RedZone: every live game as a compact slab row with its score, clock, down and distance and a red-zone flag. */
const GameRow = memo(function GameRow({ g }: { g: Game }) {
  return (
    <li className={`ld-game-row${g.redZone ? " rz" : ""}`}>
      <span className="tm">{(g.home.short ?? g.home.name).toUpperCase()}</span>
      <b className="sc">{g.score && g.status !== "pre" ? <Odo value={g.score.home} /> : "–"}</b>
      <span className="mid">
        {g.status === "pre" && g.startsAt ? new Date(g.startsAt * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : g.status === "post" ? "FINAL" : [g.period, g.clock].filter(Boolean).join(" ")}
        {g.situation && g.status !== "post" && <small>{g.situation}</small>}
        {g.lastPlay && g.status === "in" && <small className="lp">{g.lastPlay}</small>}
      </span>
      <b className="sc">{g.score && g.status !== "pre" ? <Odo value={g.score.away} /> : "–"}</b>
      <span className="tm">{(g.away.short ?? g.away.name).toUpperCase()}</span>
      {g.redZone && <em className="rzflag">RED ZONE</em>}
    </li>
  );
});

function GamesBoard({ games }: { games: Game[] }) {
  if (!games.length) return <p className="iptv-hint">No games on right now.</p>;
  return (
    <ul className="ld-games">
      {games.map((g) => (
        <GameRow key={g.matchId || `${g.home.name}${g.away.name}`} g={g} />
      ))}
    </ul>
  );
}

const TYRE_COLOR: Record<string, string> = { S: "#ff3b3b", M: "#ffd12c", H: "#f2f2f2", I: "#3ec56b", W: "#3b8dff" };

/** One timing-tower row: position, team colour bar, driver code, gap and a coloured tyre chip. */
const TowerRowView = memo(function TowerRowView({ r }: { r: TowerRow }) {
  return (
    <li className={`ld-trow${r.fastest ? " fast" : ""}`}>
      <b className="pos">{r.pos}</b>
      <i style={{ background: r.color ?? "#5a6372" }} aria-hidden />
      <span className="drv">{r.driver}</span>
      <span className="gap">{r.best ? `${r.best}${r.gap ? ` ${r.gap}` : ""}` : (r.gap ?? "")}</span>
      {r.tyre && (
        <span className="tyre" style={{ borderColor: TYRE_COLOR[r.tyre] }} title={r.tyreAge ? `${r.tyre}, ${r.tyreAge} laps` : r.tyre}>
          {r.tyre}
        </span>
      )}
    </li>
  );
});

const LeaderRowView = memo(function LeaderRowView({ r }: { r: LeaderRow }) {
  return (
    <li className="ld-lrow">
      <b className="pos">{r.pos}</b>
      <span className="pl">
        {flagOf(r.country) && <span className="flag">{flagOf(r.country)}</span>} {r.player}
      </span>
      <span className="thru">{r.thru ?? ""}</span>
      <b className={`par${r.toPar.startsWith("-") ? " under" : ""}`}>{r.toPar}</b>
    </li>
  );
});

function SituationView({ blocks, hc, ac }: { blocks: Block[]; hc: string; ac: string }) {
  return (
    <>
      {blocks.map((b, i) => {
        if (b.kind === "kv")
          return (
            <div key={i} className="ld-card">
              {b.title && <h4>{b.title}</h4>}
              {b.rows.map(([k, v]) => (
                <p key={k} className="ld-kv">
                  <span>{k}</span>
                  <b>{v}</b>
                </p>
              ))}
            </div>
          );
        if (b.kind === "bar") return <StatRow key={i} label={b.label} home={b.homeText} away={b.awayText} hc={hc} ac={ac} />;
        if (b.kind === "field")
          return (
            <div key={i} className="ld-card">
              <div className="ld-field" aria-label={b.text ?? "Field position"}>
                <i className="ld-ball" style={{ left: `${b.pos}%`, background: b.side === "away" ? ac : hc }} />
                {b.redZone && <span className="ld-rz">RED ZONE</span>}
              </div>
              {b.text && <p className="iptv-hint">{b.text}</p>}
            </div>
          );
        if (b.kind === "chips")
          return (
            <div key={i} className="ld-card">
              <h4>{b.label}</h4>
              <span className="ld-balls">
                {b.items.map((x, j) => (
                  <b key={j} className={`ld-ball-chip ${/^w|wkt|out/i.test(x) ? "w" : /^[46]/.test(x) ? "b" : ""}`}>
                    {x}
                  </b>
                ))}
              </span>
            </div>
          );
        if (b.kind === "banner")
          return (
            <div key={i} className="ld-card ld-f1">
              <div className="ld-f1-top">
                {b.lap && <b className="ld-lap">{b.lap}</b>}
                {b.session && <span>{b.session}</span>}
                {b.flag && <span className={`ld-flag ${b.flag}`}>{FLAG_LABEL[b.flag]}</span>}
              </div>
              {b.fastest && <p className="iptv-hint">Fastest lap {b.fastest}</p>}
            </div>
          );
        if (b.kind === "tower")
          return (
            <ol key={i} className="ld-tower">
              {b.rows.map((r) => (
                <TowerRowView key={r.driver} r={r} />
              ))}
            </ol>
          );
        if (b.kind === "sets")
          return (
            <div key={i} className="ld-card">
              <table className="ld-table">
                <thead>
                  <tr>
                    <th />
                    {b.head.map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {b.rows.map((r) => (
                    <tr key={r.name}>
                      <td>
                        {r.serving && <i className="ld-serve" aria-label="Serving" />}
                        {r.name}
                      </td>
                      {r.cells.map((c, j) => (
                        <td key={j}>{c}</td>
                      ))}
                      {r.game !== "" && <td className="ld-game">{r.game}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        if (b.kind === "matches")
          return (
            <div key={i} className="ld-card">
              <h4>{b.title}</h4>
              <ul className="ld-tm">
                {b.rows.map((m) => (
                  <li key={m.id}>
                    <span className="pl">
                      {m.serving === "home" && <i className="ld-serve" />}
                      {m.home}
                    </span>
                    <b className="sc">{m.score ?? ""}</b>
                    <span className="pl r">
                      {m.away}
                      {m.serving === "away" && <i className="ld-serve" />}
                    </span>
                    <small>{[m.round, m.court, m.status].filter(Boolean).join(" · ")}</small>
                  </li>
                ))}
              </ul>
            </div>
          );
        if (b.kind === "leaderboard")
          return (
            <div key={i} className="ld-card">
              <h4>{b.title}</h4>
              {b.pinned.length > 0 && (
                <ul className="ld-lb pinned">
                  {b.pinned.map((r) => (
                    <LeaderRowView key={`p${r.player}`} r={r} />
                  ))}
                </ul>
              )}
              <ul className="ld-lb">
                {b.rows.map((r) => (
                  <LeaderRowView key={r.player} r={r} />
                ))}
              </ul>
            </div>
          );
        return (
          <div key={i} className="ld-card">
            <h4>{b.label}</h4>
            <table className="ld-table">
              <thead>
                <tr>
                  <th />
                  {b.head.map((h, j) => (
                    <th key={j}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {b.rows.map((r) => (
                  <tr key={r.name}>
                    <td>{r.name}</td>
                    {r.cells.map((c, j) => (
                      <td key={j}>{c}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
      })}
    </>
  );
}


/** Labels the lead card already says; the matching rows of the sport block are dropped under it. */
const COVERS: Record<string, string> = { "Down & distance": "Down & distance", "Ball on": "Ball on", Run: "Run", Required: "Required rate", "Run rate": "Required rate" };
function withoutCovered(blocks: Block[], lead: Row[]): Block[] {
  const have = new Set(lead.map(([k]) => k));
  return blocks.flatMap((b): Block[] => {
    if (b.kind === "bar") return b.label.toLowerCase() === "fouls" && have.has("Team fouls") ? [] : [b];
    if (b.kind !== "kv") return [b];
    const rows = b.rows.filter(([k]) => !(COVERS[k] && have.has(COVERS[k])));
    return rows.length ? [{ ...b, rows }] : [];
  });
}

const Mine = () => <span className="ld-mine">YOUR TEAM</span>;

/** Pre-game: when it starts. Line-ups, where to watch and form are in Match info. */
const PreCard = memo(function PreCard({ startsAt, now }: { startsAt: number | null; now: number }) {
  const left = startsAt != null ? startsAt - now : null;
  const text = left == null ? "Not started yet" : left <= 0 ? "Starting now" : left < 5400 ? `Kick-off in ${Math.max(1, Math.round(left / 60))} min` : startsText(startsAt!, now);
  return (
    <div className="ld-state pre">
      <h4>Up next</h4>
      <p className="ld-oneline">{text}</p>
      <p className="iptv-hint">Line-ups, form and where to watch are in Match info.</p>
    </div>
  );
});

/** Tight game: what decides it, first. */
const DecideCard = memo(function DecideCard({ rows, mine }: { rows: Row[]; mine: boolean }) {
  return (
    <div className="ld-state tight">
      <h4>
        What decides it{mine && <Mine />}
      </h4>
      <ul className="ld-decide">
        {rows.map(([k, v]) => (
          <li key={k}>
            <span>{k}</span>
            <b>{v}</b>
          </li>
        ))}
      </ul>
    </div>
  );
});

/** Blowout: one line, a way out to a closer game, and the full stats behind a toggle. */
const BlowoutCard = memo(function BlowoutCard({ line, closer, full, onToggle }: { line: string; closer: CloserGame | null; full: boolean; onToggle: () => void }) {
  return (
    <div className="ld-state blowout">
      <p className="ld-oneline">{line}</p>
      {closer && (
        <button className="ld-closer" data-nav onClick={() => go(closer.path)}>
          <span>
            Closer game
            <small>{[closer.title, closer.item.league].filter(Boolean).join(" · ")}</small>
          </span>
          <span aria-hidden>›</span>
        </button>
      )}
      <button className="ld-more" data-nav aria-expanded={full} onClick={onToggle}>
        {full ? "Hide full stats" : "Show full stats"}
      </button>
    </div>
  );
});

/** Break: what scored, who is playing well, and what moved your fantasy players. */
const RecapCard = memo(function RecapCard({ plays, leaders, fantasy, title }: { plays: ReturnType<typeof recapPlays>; leaders: LiveStats["leaders"]; fantasy: { name: string; delta: number }[]; title: string }) {
  return (
    <div className="ld-state break">
      <h4>{title}</h4>
      {plays.length > 0 ? (
        <ul className="ld-recap">
          {plays.map((p, i) => (
            <li key={`${p.when}${p.text}${i}`} className={p.mine ? "mine" : undefined}>
              <span className="ld-when">{p.when}</span>
              <span className="ld-text">{p.text}</span>
              {p.delta != null && <b>+{p.delta}</b>}
            </li>
          ))}
        </ul>
      ) : (
        <p className="iptv-hint">No scoring yet.</p>
      )}
      {leaders.length > 0 && (
        <>
          <p className="ld-sub">Top performers</p>
          <ul className="ld-recap">
            {leaders.slice(0, 3).map((l) => (
              <li key={l.name}>
                <span className="ld-text">
                  <strong>{l.name}</strong> {[l.team, l.line].filter(Boolean).join(" · ")}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {fantasy.length > 0 && (
        <>
          <p className="ld-sub">Your fantasy players</p>
          <ul className="ld-recap">
            {fantasy.map((f) => (
              <li key={f.name}>
                <span className="ld-text">{f.name}</span>
                <b>
                  {f.delta >= 0 ? "+" : ""}
                  {(Math.round(f.delta * 10) / 10).toFixed(1)}
                </b>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
});

/** Final: the result and the key moments. */
const FinalCard = memo(function FinalCard({ result, moments, plays }: { result: string | null; moments: Moment[]; plays: ReturnType<typeof recapPlays> }) {
  return (
    <div className="ld-state final">
      <h4>Full time</h4>
      {result && <p className="ld-oneline">{result}</p>}
      {moments.length > 0 ? (
        <ul className="ld-recap">
          {moments.map((m) => (
            <li key={`${m.at}${m.label}`}>
              <span className="ld-text">{m.label}</span>
            </li>
          ))}
        </ul>
      ) : (
        plays.length > 0 && (
          <ul className="ld-recap">
            {plays.map((p, i) => (
              <li key={`${p.when}${i}`} className={p.mine ? "mine" : undefined}>
                <span className="ld-when">{p.when}</span>
                <span className="ld-text">{p.text}</span>
              </li>
            ))}
          </ul>
        )
      )}
    </div>
  );
});

export const LiveDrawer = memo(function LiveDrawer({ matchId, liveNow, spoilers, reveal, embedded = false, source, onClose }: { matchId: string; liveNow: boolean; spoilers: boolean; reveal?: Reveal; /** Hosted by the player's companion panel: no slide-in, no scrim, and Back is the player's to handle. */ embedded?: boolean; /** The player's own feed (one /live poll): the drawer shows what it has instead of polling. Without it the drawer polls itself. */ source?: LiveSource; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>("stats");
  const [raw, setRaw] = useState<LiveStats | null>(null);
  // Everything shown goes through `reveal` (the broadcast delay, T2.3): a new function identity means its answers may have changed
  const revealFn = reveal ?? revealAll;
  const revealRef = useRef(revealFn);
  revealRef.current = revealFn;
  const d = useMemo(() => (raw ? applyReveal(raw, revealFn) : null), [raw, revealFn]);
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "missing" | "error">("loading");
  const [now, setNow] = useState(() => Date.now() / 1000);
  const [flash, setFlash] = useState<{ label: string; team: "home" | "away" | null; n: number } | null>(null);
  const seenMoment = useRef<number | null>(null);
  const flashN = useRef(0);
  const flashTimer = useRef<number | undefined>(undefined);
  const [full, setFull] = useState(false);
  const allowed = mayShow(liveNow, spoilers);
  // Fantasy: only for an NFL game with leagues from either source
  const fantasy = useRevealedFantasy(useFantasy(liveNow), revealFn);
  const showFantasy = !!fantasy && fantasy.leagues.length > 0 && !!fixture && isNfl(fixture.leagueKey);
  // Slides out for 200 ms before it goes
  const [leaving, setLeaving] = useState(false);
  const close = () => {
    if (embedded) return onClose();
    if (leaving) return;
    setLeaving(true);
    window.setTimeout(onClose, OUT_MS);
  };
  useEscapeClose(close, !embedded);
  const multi = fixture?.kind === "multi";
  // Odds and win probability spoil blowouts: they exist only with spoilers on
  let tabs = tabsFor(liveTabs(!!d?.chat, showFantasy), spoilers) as [Tab, string][];
  // RedZone: no single game, no market. Fantasy leads, then the multi-game scoreboard
  if (multi) tabs = [...tabs.filter(([k]) => k === "fantasy"), ...tabs.filter(([k]) => k !== "fantasy" && k !== "odds")];
  const defaulted = useRef(false);
  useEffect(() => {
    if (multi && showFantasy && !defaulted.current) {
      defaulted.current = true;
      setTab("fantasy");
    }
  }, [multi, showFantasy]);
  // The tab being viewed went away (chat disappeared from the answer): back to the first
  useEffect(() => {
    if (!tabs.some(([k]) => k === tab)) setTab("stats");
  }, [tabs.map(([k]) => k).join(), tab]);
  const newPlays = useNewKeys(d?.plays.map((p) => p.id ?? `${p.period}${p.clock}${p.text}`) ?? []);
  const newChat = useNewKeys(d?.chat?.comments.map((c) => `${c.author}${c.text}`) ?? []);

  // A big moment (already past the delay) flashes the strip in that team's colour; the first batch only sets the baseline.
  // In a blowout only the viewer's own team's moments flash.
  useEffect(() => {
    if (!d) return;
    const latest = d.moments.reduce((m, x) => Math.max(m, x.at), 0);
    if (seenMoment.current == null) {
      seenMoment.current = latest;
      return;
    }
    const fresh: Moment[] = newMoments(d.moments, seenMoment.current);
    seenMoment.current = Math.max(seenMoment.current, latest);
    const m = fresh.filter((x) => kind !== "blowout" || (fav != null && x.team === fav)).pop();
    if (m) {
      setFlash({ label: m.label.toUpperCase(), team: m.team, n: ++flashN.current });
      window.clearTimeout(flashTimer.current);
      flashTimer.current = window.setTimeout(() => setFlash(null), FLASH_MS);
    }
  }, [d?.moments]);
  useEffect(() => () => window.clearTimeout(flashTimer.current), []);

  const ext = useSyncExternalStore(source?.subscribe ?? NOOP_SUB, source?.get ?? NOOP_GET);
  useEffect(() => {
    if (!source) return;
    setNow(Date.now() / 1000);
    if (ext.fixture) setFixture(ext.fixture);
    // What the spoiler delay has released (the newest released snapshot plus every released moment), never the newest raw answer
    if (ext.shown) setRaw(ext.shown);
    setState(ext.state === "ok" && !ext.shown ? "loading" : ext.state);
  }, [source, ext]);
  useEffect(() => {
    if (!allowed || source) return;
    let alive = true;
    let timer: number | undefined;
    const load = async () => {
      const a = await liveStats(matchId);
      if (!alive) return;
      setNow(Date.now() / 1000);
      if (a === "missing") setState("missing");
      else if (!a) setState((s) => (s === "ok" ? s : "error")); // keep what we have when a refresh fails
      else {
        setRaw(a);
        setState("ok");
        // Odds flips are odds: only with spoilers on, and only as far as the delay allows
        if (spoilers) announceFlips(matchId, applyReveal(a, revealRef.current));
      }
      timer = window.setTimeout(tick, REFRESH_MS);
    };
    // A hidden tab skips the poll and catches up the moment it is visible again
    let waiting = false;
    const tick = () => {
      if (document.hidden) {
        waiting = true;
        return;
      }
      void load();
    };
    const onVisible = () => {
      if (!document.hidden && waiting && alive) {
        waiting = false;
        void load();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    void load();
    // Team names, crests and colours
    void matchDetailCached(matchId).then((m) => alive && m && m !== "missing" && setFixture(m.fixture));
    return () => {
      alive = false;
      document.removeEventListener("visibilitychange", onVisible);
      window.clearTimeout(timer);
    };
  }, [matchId, allowed, spoilers, source]);

  const home = fixture?.home.name ?? "Home";
  const away = fixture?.away.name ?? "Away";
  const names = useMemo(() => ({ home, away }), [home, away]);
  // The tight card is narrow: short names there
  const shortNames = useMemo(() => ({ home: fixture?.home.short ?? home, away: fixture?.away.short ?? away }), [fixture, home, away]);
  const colors = teamColors(fixture);
  // Bars and names sit on near-black cards: a dark team colour is lightened there (the slabs keep the real colours)
  const [hc, ac] = [readable(colors.home), readable(colors.away)];
  const sportPrefs = usePrefs({ defer: true }).prefs?.sport;
  const floating = sportPrefs?.panelStyle === "floating";
  // Game state: derived from the revealed answer only, so a hidden score can never drive a blowout line
  const fav = useMemo(() => favouriteSide(fixture, [...(sportPrefs?.teams ?? []), ...(sportPrefs?.teamRefs ?? []).map((r) => r.name)]), [fixture, sportPrefs?.teams, sportPrefs?.teamRefs]);
  const gs: GameState | null = useMemo(() => (d && !multi ? gameStateOf(d, fixture, spoilers) : null), [d, fixture, spoilers, multi]);
  const kind = gs?.kind ?? null;
  const decide = useMemo(() => (d && gs && kind === "live-tight" ? deciderRows(d, gs, shortNames, spoilers) : []), [d, gs, kind, shortNames, spoilers]);
  const fx = useFixtures(kind === "blowout");
  const closer = useMemo(() => (kind === "blowout" ? closerGame(fx.data?.items ?? [], { fixtureId: fixture?.id, matchId }) : null), [kind, fx.data, fixture?.id, matchId]);
  const recap = useMemo(() => (d && (kind === "break" || kind === "final") ? recapPlays(d, fav) : []), [d, kind, fav]);
  const fantasyRows = useMemo(
    () => (kind === "break" && showFantasy && revealFn(undefined) ? playersInGame(fantasy, fixture).flatMap((g) => g.players.flatMap((p) => (p.last ? [{ name: p.name, delta: p.last.delta }] : []))).slice(0, 4) : []),
    [kind, showFantasy, fantasy, fixture, revealFn],
  );
  const blocks0 = useMemo(() => (d ? situationBlocks(d.layout, d.situation, { home, away }) : []), [d?.layout, d?.situation, home, away]);
  const blocks = useMemo(() => (decide.length ? withoutCovered(blocks0, decide) : blocks0), [blocks0, decide]);
  const teamColor = (t: "home" | "away" | null) => (t === "away" ? ac : t === "home" ? hc : "rgba(255,255,255,0.5)");

  return (
    <div className={`ld-wrap${embedded ? " embedded" : floating ? " floating" : ""}${leaving ? " out" : ""}`} onClick={(e) => e.stopPropagation()}>
      <aside className="live-drawer bc" data-state={kind ?? undefined} role="dialog" aria-label="Live stats" style={{ background: washBackground(colors) }}>
        <SheetClose onClose={close} />
        {!allowed ? (
          <p className="iptv-hint">Live stats would spoil this recording. You can turn on "Show scores (spoilers)" in Settings → Live sport.</p>
        ) : (
          <>
            {d && <Strip d={d} f={fixture} flash={flash} />}
            <div className="bc-tabs" role="tablist" aria-label="Live panel">
              {tabs.map(([k]) => (
                <button key={k} className={`bc-tab${tab === k ? " on" : ""}`} data-nav role="tab" aria-selected={tab === k} onClick={() => setTab(k)}>
                  <svg viewBox="0 0 24 24" aria-hidden>
                    <path d={ICON[k]} />
                  </svg>
                  {multi && k === "stats" ? "Games" : LABEL[k]}
                </button>
              ))}
            </div>
            {tab !== "fantasy" && revealFn(undefined) && <FantasyYourPlayers live={fantasy} fixture={fixture} />}
            {state === "loading" && <p className="iptv-hint">Loading…</p>}
            {state === "missing" && <p className="iptv-hint">Live stats aren't available for this match.</p>}
            {state === "error" && <p className="iptv-hint">Couldn't load live stats. Trying again…</p>}
            {d && (
              <div className="ld-body">
                <div key={tab} className="ld-tab">
                  {tab === "stats" && multi && <GamesBoard games={d.games} />}
                  {tab === "stats" && !multi && kind === "pre" && <PreCard startsAt={fixture?.startsAt ?? null} now={now} />}
                  {tab === "stats" && !multi && kind === "live-tight" && decide.length > 0 && <DecideCard rows={decide} mine={fav != null} />}
                  {tab === "stats" && !multi && kind === "blowout" && gs && <BlowoutCard line={blowoutLine(d, gs, names, fav)} closer={closer} full={full} onToggle={() => setFull((x) => !x)} />}
                  {tab === "stats" && !multi && kind === "break" && <RecapCard title="Break recap" plays={recap} leaders={d.leaders} fantasy={fantasyRows} />}
                  {tab === "stats" && !multi && kind === "final" && <FinalCard result={resultLine(d, names)} moments={d.moments.slice(-6)} plays={recap} />}
                  {tab === "stats" && !multi && (kind !== "blowout" || full) && (
                    <>
                      <SituationView blocks={blocks} hc={hc} ac={ac} />
                      {d.stats.length === 0 && d.leaders.length === 0 && blocks.length === 0 && <p className="iptv-hint">No stats yet.</p>}
                      {d.stats.length > 0 && (
                        <div className="ld-card">
                          <div className="ld-names">
                            <b style={{ color: hc }}>{home}</b>
                            <b style={{ color: ac }}>{away}</b>
                          </div>
                          {d.stats.map((s) => (
                            <StatRow key={s.label} label={s.label} home={s.home} away={s.away} hc={hc} ac={ac} />
                          ))}
                        </div>
                      )}
                      {d.leaders.length > 0 && (
                        <ul className="ld-leaders">
                          {d.leaders.map((l) => (
                            <LeaderCard key={l.name} name={l.name} team={l.team} line={l.line} headshot={l.headshot} color={l.team && (l.team === fixture?.home.short || l.team === fixture?.home.name) ? hc : l.team && (l.team === fixture?.away.short || l.team === fixture?.away.name) ? ac : GOLD} />
                          ))}
                        </ul>
                      )}
                    </>
                  )}
                  {tab === "plays" && (
                    <>
                      {d.plays.length === 0 && <p className="iptv-hint">No plays yet.</p>}
                      <ul className="ld-plays">
                        {d.plays.map((p, i) => {
                          const k = p.id ?? `${p.period}${p.clock}${p.text}`;
                          return <PlayRow key={p.id ?? `${k}${i}`} when={[p.period, p.clock].filter(Boolean).join(" ")} text={p.text} scoring={p.scoring} delta={p.delta} color={teamColor(p.team)} fresh={newPlays.has(k)} />;
                        })}
                      </ul>
                    </>
                  )}
                  {tab === "odds" && (
                    <>
                      {!d.market && d.winProb == null && <p className="iptv-hint">No odds for this match.</p>}
                      {d.market?.kind === "winner" && (
                        <div className="ld-card">
                          <WinnerList market={d.market} color={fixture?.kind === "match" || !fixture ? hc : colors.known ? hc : GOLD} now={now} />
                          <Spark series={d.market.series} color={GOLD} />
                        </div>
                      )}
                      {d.market && d.market.kind !== "winner" && (
                        <div className="ld-card">
                          <div className="ld-oddsnum">
                            <b style={{ color: hc }}>
                              {home} <Odo value={pct(d.market.home)} />
                            </b>
                            <b style={{ color: ac }}>
                              <Odo value={pct(d.market.away)} /> {away}
                            </b>
                          </div>
                          <div className="ld-split big" aria-hidden>
                            <i style={{ width: `${d.market.home * 100}%`, background: hc }} />
                            <i style={{ width: `${d.market.away * 100}%`, background: ac }} />
                          </div>
                          {d.market.draw != null && <p className="iptv-hint">Draw {pct(d.market.draw)}</p>}
                          <Spark series={d.market.series} color={d.market.favourite === "away" ? ac : hc} />
                          <p className="iptv-hint">
                            {[d.market.source === "polymarket" ? "Polymarket" : d.market.source, volumeText(d.market.volume), agoText(d.market.updatedAt, now)].filter(Boolean).join(" · ")}
                            {d.market.thin && <ThinPill />}
                          </p>
                        </div>
                      )}
                      {d.winProb != null && (
                        <p className="iptv-hint">
                          Win probability: {home} {pct(d.winProb)}
                        </p>
                      )}
                    </>
                  )}
                  {tab === "fantasy" && showFantasy && fixture && <FantasyTab live={fantasy!} fixtureId={fixture.id} multi={multi} />}
                  {tab === "chat" && d.chat && d.chat.comments.length > 0 && (
                    <>
                      <ul className="ld-chat">
                        {d.chat.comments.map((c) => (
                          <ChatRow key={`${c.author}${c.text}`} author={c.author} text={c.text} fresh={newChat.has(`${c.author}${c.text}`)} />
                        ))}
                      </ul>
                      <p className="iptv-hint">
                        From {d.chat.source === "reddit" ? "Reddit" : d.chat.source}
                        {d.chat.thread ? ` · ${d.chat.thread}` : ""}
                      </p>
                    </>
                  )}
                </div>
                <p className="iptv-hint">Updates every 15 s{agoText(d.updatedAt, now) ? ` · ${agoText(d.updatedAt, now)}` : ""}</p>
              </div>
            )}
          </>
        )}
      </aside>
    </div>
  );
});
