// The in-event "Live" drawer in the player (plan: ~/ai/plans/jellytip-live-sport.md "In-event panel" and "Live panel: pop, context,
// performance"): a scoreboard strip in the team colours, pill tabs (Stats · Plays · Odds · Chat · Fantasy), split-bar stats, leader
// cards, a plays timeline, the odds bar with a sparkline and a per-sport "situation". Polls every 15 s while open only; rows are
// memoised and keyed so a poll re-renders only what changed; motion is transform and opacity only.
import { memo, useEffect, useMemo, useRef, useState } from "react";
import { liveStats, matchDetailCached } from "../api/matchInfo";
import { agoText, liveTabs, maskText, mayShow, newMoments, pct, situationBlocks, sparkPoints, statShare, volumeText, FLAG_LABEL, type Block, type LeaderRow, type LiveStats, type Moment, type TowerRow } from "../lib/liveStats";
import { flagOf, readable, type Fixture } from "../lib/matchInfo";
import { GOLD, onColor, teamColors, washBackground } from "../lib/broadcast";
import { Odo } from "./Odo";
import { usePrefs } from "../api/me";
import "../fonts";
import { logoSrc } from "../api/iptv";
import { FantasyTab } from "./Fantasy";
import { useFantasy } from "../api/fantasy";
import { isNfl } from "../lib/fantasy";
import { announceFlips } from "./FlipAlerts";
import { SheetClose, useEscapeClose } from "./BackArrow";
import "../screens/iptv/iptv.css";

type Tab = "stats" | "plays" | "odds" | "chat" | "fantasy";
const REFRESH_MS = 15_000;
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

/** Keys seen on earlier renders: what is not in the set is new (the first batch is never new). */
function useNewKeys(keys: string[]): Set<string> {
  const seen = useRef<Set<string> | null>(null);
  const fresh = new Set<string>();
  if (seen.current && keys.length) for (const k of keys) if (!seen.current.has(k)) fresh.add(k);
  useEffect(() => {
    seen.current = new Set([...(seen.current ?? []), ...keys]);
  });
  return fresh;
}

function Crest({ f, side }: { f: Fixture | null; side: "home" | "away" }) {
  const s = f?.[side];
  const src = imgSrc(s?.logo);
  const [failed, setFailed] = useState(false);
  // Tennis: a country flag instead of a crest
  if (!src && s?.country && flagOf(s.country)) return <span className="ld-crest flag" aria-label={s.country}>{flagOf(s.country)}</span>;
  return src && !failed ? <img className="ld-crest" src={src} alt="" onError={() => setFailed(true)} /> : <span className="ld-crest blank">{(s?.short ?? s?.name ?? side).slice(0, 3)}</span>;
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
  if (f?.kind === "event") {
    // F1, golf: no teams. The event title on a series-colour slab
    return (
      <div className="bc-stripbox">
        <div className="bc-strip event">
          <div className="bc-slab l" style={{ ["--sc" as string]: c.home, ["--sx" as string]: onColor(c.home) }}>
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

const TYRE_COLOR: Record<string, string> = { S: "#ff3b3b", M: "#ffd12c", H: "#f2f2f2", I: "#3ec56b", W: "#3b8dff" };

/** One timing-tower row: position, team colour bar, driver code, gap and a coloured tyre chip. */
const TowerRowView = memo(function TowerRowView({ r }: { r: TowerRow }) {
  return (
    <li className={`ld-trow${r.fastest ? " fast" : ""}`}>
      <b className="pos">{r.pos}</b>
      <i style={{ background: r.color ?? "#5a6372" }} aria-hidden />
      <span className="drv">{r.driver}</span>
      <span className="gap">{r.gap ?? ""}</span>
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

export function LiveDrawer({ matchId, liveNow, spoilers, onClose }: { matchId: string; liveNow: boolean; spoilers: boolean; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>("stats");
  const [d, setD] = useState<LiveStats | null>(null);
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "missing" | "error">("loading");
  const [now, setNow] = useState(() => Date.now() / 1000);
  const [flash, setFlash] = useState<{ label: string; team: "home" | "away" | null; n: number } | null>(null);
  const seenMoment = useRef<number | null>(null);
  const flashN = useRef(0);
  const allowed = mayShow(liveNow, spoilers);
  // Fantasy: only for an NFL game with leagues from either source
  const fantasy = useFantasy(liveNow);
  const showFantasy = !!fantasy && fantasy.leagues.length > 0 && !!fixture && isNfl(fixture.leagueKey);
  // Slides out for 200 ms before it goes
  const [leaving, setLeaving] = useState(false);
  const close = () => {
    if (leaving) return;
    setLeaving(true);
    window.setTimeout(onClose, OUT_MS);
  };
  useEscapeClose(close);
  const tabs = liveTabs(!!d?.chat, showFantasy) as [Tab, string][];
  // The tab being viewed went away (chat disappeared from the answer): back to the first
  useEffect(() => {
    if (!tabs.some(([k]) => k === tab)) setTab("stats");
  }, [tabs.map(([k]) => k).join(), tab]);
  const newPlays = useNewKeys(d?.plays.map((p, i) => `${p.period}${p.clock}${p.text}${i === 0 ? "" : ""}`) ?? []);
  const newChat = useNewKeys(d?.chat?.comments.map((c) => `${c.author}${c.text}`) ?? []);

  useEffect(() => {
    if (!allowed) return;
    let alive = true;
    let timer: number | undefined;
    let flashTimer: number | undefined;
    const load = async () => {
      const a = await liveStats(matchId);
      if (!alive) return;
      setNow(Date.now() / 1000);
      if (a === "missing") setState("missing");
      else if (!a) setState((s) => (s === "ok" ? s : "error")); // keep what we have when a refresh fails
      else {
        setD(a);
        setState("ok");
        announceFlips(matchId, a);
        // A big moment since the last poll flashes the strip in that team's colour with its label; the first answer only sets the baseline
        const latest = a.moments.reduce((m, x) => Math.max(m, x.at), 0);
        if (seenMoment.current == null) seenMoment.current = latest;
        else {
          const fresh: Moment[] = newMoments(a.moments, seenMoment.current);
          seenMoment.current = Math.max(seenMoment.current, latest);
          const m = fresh[fresh.length - 1];
          if (m) {
            setFlash({ label: m.label.toUpperCase(), team: m.team, n: ++flashN.current });
            window.clearTimeout(flashTimer);
            flashTimer = window.setTimeout(() => setFlash(null), FLASH_MS);
          }
        }
      }
      timer = window.setTimeout(load, REFRESH_MS);
    };
    void load();
    // Team names, crests and colours
    void matchDetailCached(matchId).then((m) => alive && m && m !== "missing" && setFixture(m.fixture));
    return () => {
      alive = false;
      window.clearTimeout(timer);
      window.clearTimeout(flashTimer);
    };
  }, [matchId, allowed]);

  const home = fixture?.home.name ?? "Home";
  const away = fixture?.away.name ?? "Away";
  const colors = teamColors(fixture);
  // Bars and names sit on near-black cards: a dark team colour is lightened there (the slabs keep the real colours)
  const [hc, ac] = [readable(colors.home), readable(colors.away)];
  const floating = usePrefs({ defer: true }).prefs?.sport.panelStyle === "floating";
  const blocks = useMemo(() => (d ? situationBlocks(d.layout, d.situation, { home, away }) : []), [d?.layout, d?.situation, home, away]);
  const teamColor = (t: "home" | "away" | null) => (t === "away" ? ac : t === "home" ? hc : "rgba(255,255,255,0.5)");

  return (
    <div className={`ld-wrap${floating ? " floating" : ""}${leaving ? " out" : ""}`} onClick={(e) => e.stopPropagation()}>
      <aside className="live-drawer bc" role="dialog" aria-label="Live stats" style={{ background: washBackground(colors) }}>
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
                  {LABEL[k]}
                </button>
              ))}
            </div>
            {state === "loading" && <p className="iptv-hint">Loading…</p>}
            {state === "missing" && <p className="iptv-hint">Live stats aren't available for this match.</p>}
            {state === "error" && <p className="iptv-hint">Couldn't load live stats. Trying again…</p>}
            {d && (
              <div className="ld-body">
                <div key={tab} className="ld-tab">
                  {tab === "stats" && (
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
                      {d.market && (
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
                          <p className="iptv-hint">{[d.market.source === "polymarket" ? "Polymarket" : d.market.source, volumeText(d.market.volume), agoText(d.market.updatedAt, now)].filter(Boolean).join(" · ")}</p>
                        </div>
                      )}
                      {d.winProb != null && (
                        <p className="iptv-hint">
                          Win probability: {home} {pct(d.winProb)}
                        </p>
                      )}
                    </>
                  )}
                  {tab === "fantasy" && showFantasy && fixture && <FantasyTab live={fantasy!} fixtureId={fixture.id} />}
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
}
