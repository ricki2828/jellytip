// #/games: Game Centre. Followed teams' games from today and recent days, each with its ways to watch. Spoiler-safe by
// default: no scores, results, art or programme descriptions; scores only behind a two-press reveal (or when "Show scores
// (spoilers)" is on in Settings). Logic in lib/gameCentre.ts.
import { RemindControl } from "../../components/TeamRemind";
import { fixtureSides, type TeamSide } from "../../lib/teamReminders";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { addRecording, errorText } from "../../api/iptv";
import { refreshFixtures, showAllLeague, useFixtures } from "../../api/fixtures";
import { matchDetail } from "../../api/matchInfo";
import { usePrefs } from "../../api/me";
import { refreshRecs, useRecs } from "../../api/recStore";
import { addFixtureReminder, addReminder, refreshReminders, removeReminder, useReminders } from "../../api/reminderStore";
import { refreshUpcoming, useUpcoming } from "../../api/upcomingStore";
import { openMatchInfo } from "../../components/MatchInfo";
import { logoSrc } from "../../api/iptv";
import { buildGames, fxScoreText, fxTitle, gateArmed, gatePress, gateStart, bestChannel, groupFixtures, legacyForced, todayMore, scoreText, scoreVisible, sectionGames, spoilersOn, waysFor, waysForFixture, type FxItem, type FxMore, type Game, type Gate } from "../../lib/gameCentre";
import type { Rec } from "../../lib/recs";
import { go, useRoute } from "../../lib/router";
import "./iptv.css";
import "./gameCentre.css";


/** Crest hosts the page's CSP allows (public CDNs); anything else must be a companion-proxied path. */
const CDN = /^https:\/\/(a\.espncdn\.com|[a-z0-9-]+\.thesportsdb\.com)\//i;
const crestSrc = (u: string | null | undefined, size: 96 | 192): string | null => (u ? (CDN.test(u) ? u : logoSrc(u, size)) : null);
const initials = (name: string): string => {
  const w = name.replace(/[^\p{L}\p{N}\s]/gu, "").split(/\s+/).filter(Boolean);
  return (w.length > 1 ? w.slice(0, 3).map((x) => x[0]).join("") : (w[0] ?? "?").slice(0, 3)).toUpperCase();
};

function Crest({ logo, name }: { logo: string | null | undefined; name: string }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? null : crestSrc(logo, 96);
  return src ? <img className="gc-crest" src={src} width={44} height={44} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} /> : <span className="gc-crest blank" aria-hidden>{initials(name)}</span>;
}

function ChannelLogo({ logo, name }: { logo: string | null | undefined; name: string }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? null : logoSrc(logo, 96);
  return src ? <img className="gc-chan" src={src} width={36} height={20} alt="" title={name} loading="lazy" decoding="async" onError={() => setFailed(true)} /> : null;
}

/** Two crests + names; a sideless event shows its title alone. */
function Matchup({ title, home, away, homeLogo, awayLogo }: { title: string; home: string | null; away: string | null; homeLogo?: string | null; awayLogo?: string | null }) {
  if (!home || !away) return <h3 className="gc-title">{title}</h3>;
  return (
    <h3 className="gc-title gc-vs" aria-label={title}>
      <span className="gc-side"><Crest logo={homeLogo} name={home} /><span className="gc-name">{home}</span></span>
      <span className="gc-v" aria-hidden>v</span>
      <span className="gc-side"><Crest logo={awayLogo} name={away} /><span className="gc-name">{away}</span></span>
    </h3>
  );
}

/** The whole card is the primary action: Enter / click. Inner buttons keep their own handlers and never bubble here. */
const cardProps = (act: (() => void) | null) => ({
  "data-nav": true as const,
  tabIndex: 0,
  role: act ? ("link" as const) : undefined,
  onClick: act ? (e: React.MouseEvent) => { if (!(e.target as HTMLElement).closest("button, a")) act(); } : undefined,
  onKeyDown: act ? (e: React.KeyboardEvent) => { if (e.target === e.currentTarget && e.key === "Enter") { e.preventDefault(); act(); } } : undefined,
});

/** "Remind me" / "Reminder set ✓ (tap to remove)": a plain toggle, ignored while a request is in flight (double-press guard). */
function RemindButton({ set, busy, title, onToggle, sides }: { set: boolean; busy: boolean; title: string; onToggle: () => void; sides: TeamSide[] }) {
  return <RemindControl thisOn={set} busy={busy} label={title} sides={sides} onThis={onToggle} />;
}

/** Reminder toggling shared by both screens; `run` does the add, the id (when set) is removed. Errors show on the card. */
function useRemind() {
  const { items } = useReminders();
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set());
  const [msgs, setMsgs] = useState<Record<string, string>>({});
  const inflight = useRef(new Set<string>());
  useEffect(() => void refreshReminders(), []);
  const toggle = useCallback(async (k: string, id: string | number | null, add: () => Promise<string | null>) => {
    if (inflight.current.has(k)) return;
    inflight.current.add(k);
    setBusy((b) => new Set(b).add(k));
    const err = id != null ? await removeReminder(id) : await add();
    inflight.current.delete(k);
    setBusy((b) => {
      const n = new Set(b);
      n.delete(k);
      return n;
    });
    setMsgs((m) => {
      const n = { ...m };
      if (err) n[k] = err;
      else delete n[k];
      return n;
    });
    if (!err) {
      void refreshFixtures(true);
      void refreshUpcoming(true);
    }
  }, []);
  return { items, busy, msgs, toggle };
}

function GameCard({ g, now, pref, shown, scoreLine, gate, onPress, remId, remBusy, remMsg, onRemind }: { g: Game; now: number; pref: boolean; shown: boolean; scoreLine: string | null | undefined; gate: Gate; onPress: () => void; remId: string | number | null; remBusy: boolean; remMsg: string | undefined; onRemind: () => void }) {
  const w = waysFor(g, now);
  const armed = gateArmed(gate, Date.now());
  const fx = g.fixture && g.fixture.kind === "match" ? g.fixture : null;
  const mid = g.fixture?.id ?? null;
  const act = w.ways[0] ? () => go(w.ways[0].path) : mid ? () => openMatchInfo(mid) : null;
  const chan = g.event?.channels[0];
  return (
    <li className={`gc-card ${w.phase}${act ? " tap" : ""}`} {...cardProps(act)}>
      <div className="gc-top">
        {w.phase === "live" && <span className="rec-chip rec">LIVE</span>}
        {g.league && <span className="gc-league">{g.league}</span>}
        {g.channel && <span className="gc-league">{g.channel}</span>}
        {chan && <ChannelLogo logo={chan.logo} name={chan.name} />}
      </div>
      <Matchup title={g.title} home={fx?.home.name ?? null} away={fx?.away.name ?? null} homeLogo={fx?.home.logo} awayLogo={fx?.away.logo} />
      <p className="gc-note">{w.note}</p>
      {shown && w.phase !== "upcoming" && <p className="gc-score">{scoreLine === undefined ? "Fetching the score…" : scoreLine ?? "No score available"}</p>}
      <div className="gc-actions">
        {w.ways.map((x, i) => (
          <button key={x.kind} className={`btn small${i === 0 ? " primary" : ""}`} data-nav onClick={() => go(x.path)}>
            {x.label}
          </button>
        ))}
        {!pref && w.phase !== "upcoming" && (
          <button className="btn small gc-reveal" data-nav aria-disabled={shown || undefined} onClick={shown ? undefined : onPress} aria-label={shown ? `Score shown for ${g.title}` : `Reveal score for ${g.title}`}>
            {shown ? "Score shown" : armed ? "Press again to reveal" : "Reveal score"}
          </button>
        )}
        {w.phase === "upcoming" && (g.event || mid) && <RemindButton set={remId != null} busy={remBusy} title={g.title} onToggle={onRemind} sides={g.event?.fixture ? fixtureSides(g.event.fixture, g.event.sportName ?? null) : []} />}
      </div>
      {remMsg && (
        <p className="gc-note" role="status">
          {remMsg}
        </p>
      )}
    </li>
  );
}

function LegacyGameCentre() {
  const { prefs } = usePrefs({ defer: true });
  const events = useUpcoming(false);
  const recs = useRecs(false);
  const [now, setNow] = useState(() => Date.now() / 1000);
  const [revealed, setRevealed] = useState<ReadonlySet<string>>(new Set());
  const [scores, setScores] = useState<Record<string, string | null>>({});
  const [gates, setGates] = useState<Record<string, Gate>>({});
  const pref = spoilersOn(prefs);
  const rem = useRemind();

  useEffect(() => {
    const prev = document.title;
    document.title = "Game Centre";
    void refreshUpcoming(true);
    void refreshRecs(true);
    const t = setInterval(() => setNow(Date.now() / 1000), 30_000);
    // A new session (another person signing in) starts again with everything hidden
    const reset = () => {
      setRevealed(new Set());
      setScores({});
      setGates({});
    };
    window.addEventListener("jellytip-session", reset);
    return () => {
      document.title = prev;
      clearInterval(t);
      window.removeEventListener("jellytip-session", reset);
    };
  }, []);

  const teams = useMemo(() => [...(prefs?.sport.teams ?? []), ...(prefs?.sport.teamRefs.map((r) => r.name) ?? [])], [prefs]);
  const games = useMemo(() => buildGames(events, recs, teams, now), [events, recs, teams, now]);
  const sections = useMemo(() => sectionGames(games, now), [games, now]);

  const press = useCallback(
    (g: Game) => {
      const r = gatePress(gates[g.key] ?? gateStart, Date.now());
      setGates((x) => ({ ...x, [g.key]: r.gate }));
      if (!r.revealed) {
        // The armed label lapses by itself
        window.setTimeout(() => setNow(Date.now() / 1000), 4100);
        return;
      }
      setRevealed((s) => new Set(s).add(g.key));
      const fromFixture = scoreText(g.fixture);
      if (fromFixture || !g.fixture?.id) return setScores((s) => ({ ...s, [g.key]: fromFixture }));
      void matchDetail(g.fixture.id, true).then((d) => setScores((s) => ({ ...s, [g.key]: d && d !== "missing" ? scoreText(d.fixture) : null })));
    },
    [gates],
  );

  return (
    <div className="iptv-page gc-page">
      <div className="page-head">
        <h1 className="page-title">Game Centre</h1>
      </div>
      <p className="iptv-hint">{pref ? "Scores are on (Settings → Live sport)." : "Spoiler-safe: no scores or results on this screen."}</p>
      {(events === null || recs === null) && games.length === 0 && <p className="iptv-hint">Loading…</p>}
      {events !== null && games.length === 0 && <p className="iptv-hint">{teams.length ? "No games for your teams in the last few days or coming up." : "Follow a team in Settings → Live sport and its games show up here."}</p>}
      {sections.map((s) => (
        <section key={s.day} className="row">
          <h2 className="row-title">{s.label}</h2>
          <ul className="gc-list">
            {s.games.map((g) => (
              <GameCard
                key={g.key}
                g={g}
                now={now}
                pref={pref}
                shown={scoreVisible(pref, revealed, g.key)}
                scoreLine={pref ? scoreText(g.fixture) : scores[g.key]}
                gate={gates[g.key] ?? gateStart}
                onPress={() => press(g)}
                remId={g.event ? rem.items?.find((r) => r.event.key === g.event!.key)?.id ?? g.event.reminderId ?? null : rem.items?.find((r) => r.event.key === g.fixture?.id)?.id ?? null}
                remBusy={rem.busy.has(g.key)}
                remMsg={rem.msgs[g.key]}
                onRemind={() => {
                  const id = g.event ? rem.items?.find((r) => r.event.key === g.event!.key)?.id ?? g.event.reminderId ?? null : null;
                  void rem.toggle(g.key, id, () => (g.event && g.event.channels[0]?.id ? addReminder(g.event) : addFixtureReminder({ matchId: g.fixture?.id })));
                }}
              />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

// ---------- fixture-first ----------

const fxSides = (f: FxItem): TeamSide[] =>
  f.away ? [{ teamId: f.homeId, name: f.home, short: f.homeShort, sport: f.sport, league: f.league }, { teamId: f.awayId, name: f.away, short: f.awayShort, sport: f.sport, league: f.league }] : [];

function FixtureCard({ f, rec, now, pref, shown, scoreLine, gate, onPress, onRecord, msg, busy, recorded, remId, remBusy, remMsg, onRemind }: { f: FxItem; rec: Rec | null; now: number; pref: boolean; shown: boolean; scoreLine: string | null | undefined; gate: Gate; onPress: () => void; onRecord: () => void; msg: string | undefined; busy: boolean; recorded: boolean; remId: string | number | null; remBusy: boolean; remMsg: string | undefined; onRemind: () => void }) {
  const w = waysForFixture(f, rec, now);
  const armed = gateArmed(gate, Date.now());
  const title = fxTitle(f);
  const canRec = !!w.recordOn && !recorded && !busy;
  const act = w.ways[0] ? () => go(w.ways[0].path) : w.phase === "upcoming" && canRec ? onRecord : f.matchId ? () => openMatchInfo(f.matchId!) : null;
  const best = bestChannel(f);
  return (
    <li className={`gc-card ${w.phase}${w.noStream ? " nostream" : ""}${act ? " tap" : ""}`} data-fid={f.fid} {...cardProps(act)}>
      <div className="gc-top">
        {w.phase === "live" && <span className="rec-chip rec">LIVE</span>}
        {f.league && <span className="gc-league">{f.league}</span>}
        {f.stage && <span className="gc-league">{f.stage}</span>}
        {best && <ChannelLogo logo={best.logo} name={best.name} />}
      </div>
      <Matchup title={title} home={f.away ? f.home : null} away={f.away || null} homeLogo={f.homeLogo} awayLogo={f.awayLogo} />
      <p className="gc-note">{w.note}</p>
      {shown && w.phase !== "upcoming" && <p className="gc-score">{scoreLine === undefined ? "Fetching the score…" : scoreLine ?? "No score available"}</p>}
      <div className="gc-actions">
        {w.ways.map((x, i) => (
          <button key={x.kind} className={`btn small${i === 0 ? " primary" : ""}`} data-nav onClick={() => go(x.path)}>
            {x.label}
          </button>
        ))}
        {w.recordOn && !recorded && (
          <button className="btn small" data-nav aria-disabled={busy || undefined} onClick={busy ? undefined : onRecord}>
            {busy ? "Recording…" : `Record on ${w.recordOn.name}${w.recordOn.maybe ? " (may carry it)" : ""}`}
          </button>
        )}
        {!pref && w.phase !== "upcoming" && (
          <button className="btn small gc-reveal" data-nav aria-disabled={shown || undefined} onClick={shown ? undefined : onPress} aria-label={shown ? `Score shown for ${title}` : `Reveal score for ${title}`}>
            {shown ? "Score shown" : armed ? "Press again to reveal" : "Reveal score"}
          </button>
        )}
        {w.phase === "upcoming" && <RemindButton set={remId != null} busy={remBusy} title={title} onToggle={onRemind} sides={fxSides(f)} />}
      </div>
      {(msg || remMsg) && (
        <p className="gc-note" role="status">
          {remMsg ?? msg}
        </p>
      )}
    </li>
  );
}

function FixtureGameCentre({ items, degraded, more, expanded, focusFid }: { items: FxItem[]; degraded: boolean; more: FxMore[]; expanded: ReadonlySet<string>; focusFid: string | null }) {
  const { prefs } = usePrefs({ defer: true });
  const recs = useRecs(false);
  const [now, setNow] = useState(() => Date.now() / 1000);
  const [revealed, setRevealed] = useState<ReadonlySet<string>>(new Set());
  const [scores, setScores] = useState<Record<string, string | null>>({});
  const [gates, setGates] = useState<Record<string, Gate>>({});
  const [msgs, setMsgs] = useState<Record<string, string>>({});
  const [done, setDone] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set());
  const pref = spoilersOn(prefs);
  const rem = useRemind();
  const remIdOf = (f: FxItem) => (rem.items ? rem.items.find((r) => r.fid === f.fid || (f.reminderId != null && String(r.id) === String(f.reminderId)))?.id ?? null : f.reminderId);
  const lapse = useRef<number | undefined>(undefined);

  useEffect(() => {
    void refreshRecs(true);
    const t = setInterval(() => {
      setNow(Date.now() / 1000);
      void refreshFixtures();
    }, 30_000);
    const reset = () => {
      setRevealed(new Set());
      setScores({});
      setGates({});
      setMsgs({});
      setDone(new Set());
    };
    window.addEventListener("jellytip-session", reset);
    return () => {
      clearInterval(t);
      window.clearTimeout(lapse.current);
      window.removeEventListener("jellytip-session", reset);
    };
  }, []);

  const groups = useMemo(() => groupFixtures(items, prefs?.sport.teamRefs ?? []), [items, prefs]);

  // #/games?fid=… (a reminder's link): scroll to that card, focus it and flash it once; again only for a different fid
  const focused = useRef<string | null>(null);
  useEffect(() => {
    if (!focusFid || focused.current === focusFid) return;
    const el = [...document.querySelectorAll<HTMLElement>("li.gc-card[data-fid]")].find((x) => x.dataset.fid === focusFid);
    if (!el) return;
    focused.current = focusFid;
    el.scrollIntoView({ block: "center" });
    el.focus({ preventScroll: true });
    el.classList.add("gc-flash");
    const t = window.setTimeout(() => el.classList.remove("gc-flash"), 2500);
    return () => window.clearTimeout(t);
  }, [focusFid, groups]);

  const press = useCallback(
    (f: FxItem) => {
      const r = gatePress(gates[f.fid] ?? gateStart, Date.now());
      setGates((x) => ({ ...x, [f.fid]: r.gate }));
      if (!r.revealed) {
        lapse.current = window.setTimeout(() => setNow(Date.now() / 1000), 4100);
        return;
      }
      setRevealed((s) => new Set(s).add(f.fid));
      const own = fxScoreText(f);
      if (own || !f.matchId) return setScores((s) => ({ ...s, [f.fid]: own }));
      void matchDetail(f.matchId, true).then((d) => setScores((s) => ({ ...s, [f.fid]: d && d !== "missing" ? scoreText(d.fixture) : null })));
    },
    [gates],
  );

  const record = useCallback(async (f: FxItem) => {
    const c = waysForFixture(f, null, Date.now() / 1000).recordOn;
    if (!c) return;
    setBusy((b) => new Set(b).add(f.fid));
    const a = await addRecording(c.id, c.start ?? f.kickoff);
    setBusy((b) => {
      const n = new Set(b);
      n.delete(f.fid);
      return n;
    });
    const ok = a.status === 200 || a.status === 201;
    setMsgs((m) => ({ ...m, [f.fid]: ok ? a.data?.warning || "Recording scheduled. It'll appear on Home." : errorText(a) }));
    if (ok) {
      setDone((d) => new Set(d).add(f.fid));
      void refreshRecs(true);
      void refreshFixtures(true);
    }
  }, []);

  return (
    <>
      <p className="iptv-hint">{pref ? "Scores are on (Settings → Live sport)." : "Spoiler-safe: no scores or results on this screen."}</p>
      {degraded && <p className="iptv-hint">Some sport data is unavailable right now, so this list may be incomplete.</p>}
      {items.length === 0 && <p className="iptv-hint">No games to show right now.</p>}
      {groups.map((g) => (
        <section key={g.id} className="row">
          <h2 className="row-title">{g.label}</h2>
          <ul className="gc-list">
            {g.items.map((f) => (
              <FixtureCard
                key={f.fid}
                f={f}
                rec={f.recordingId != null ? recs?.find((r) => String(r.id) === String(f.recordingId)) ?? null : null}
                now={now}
                pref={pref}
                shown={scoreVisible(pref, revealed, f.fid)}
                scoreLine={pref ? fxScoreText(f) : scores[f.fid]}
                gate={gates[f.fid] ?? gateStart}
                onPress={() => press(f)}
                onRecord={() => void record(f)}
                msg={msgs[f.fid]}
                busy={busy.has(f.fid)}
                recorded={done.has(f.fid)}
                remId={remIdOf(f)}
                remBusy={rem.busy.has(f.fid)}
                remMsg={rem.msgs[f.fid]}
                onRemind={() => void rem.toggle(f.fid, remIdOf(f), () => addFixtureReminder({ fid: f.fid, matchId: f.matchId }))}
              />
            ))}
          </ul>
          {g.id === "big" &&
            todayMore(more, now, expanded).map((m) => (
              <p key={m.key} className="iptv-hint gc-more">
                +{m.hidden} more {m.league} {m.hidden === 1 ? "game" : "games"} today ·{" "}
                <button className="btn small" data-nav onClick={() => void showAllLeague(m.key)}>
                  Show all
                </button>
              </p>
            ))}
        </section>
      ))}
    </>
  );
}

/** Fixture-first by default; the legacy upcoming-feed screen when jt.gcLegacy=1 or the server has no /sport/fixtures. */
export function GameCentre() {
  const legacy = legacyForced((k) => {
    try {
      return localStorage.getItem(k);
    } catch {
      return null;
    }
  });
  const { data, unavailable } = useFixtures(!legacy);
  const focusFid = useRoute().query.get("fid");
  useEffect(() => {
    if (legacy) return;
    const prev = document.title;
    document.title = "Game Centre";
    void refreshFixtures(true);
    return () => {
      document.title = prev;
    };
  }, [legacy]);
  if (legacy || unavailable) return <LegacyGameCentre />;
  return (
    <div className="iptv-page gc-page">
      <div className="page-head">
        <h1 className="page-title">Game Centre</h1>
      </div>
      {data === null ? <p className="iptv-hint">Loading…</p> : <FixtureGameCentre items={data.items} degraded={data.degraded} more={data.more} expanded={data.expanded} focusFid={focusFid} />}
    </div>
  );
}
