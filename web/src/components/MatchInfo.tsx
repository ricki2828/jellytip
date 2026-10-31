// Match info: an ⓘ button for sport tiles and the player, and the modal it opens (header, where to watch, odds, line-ups,
// form and head to head, Watch / Remind me / Record, "Show score"). Every field is optional. Contract: ~/ai/plans/jellytip-live-sport.md.
import { useEffect, useState } from "react";
import { addRecording } from "../api/iptv";
import { addReminderFor } from "../api/reminderStore";
import { matchDetail, matchInfoAvailable, onMatchInfoAvailability } from "../api/matchInfo";
import { refreshRecs } from "../api/recStore";
import { livePath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import { flagOf, formTone, heroColors, matchupTitle, oddsSplit, startsIn, tint, venueLine, type Fixture, type MatchDetail, type Player, type Side } from "../lib/matchInfo";
import { logoSrc } from "../api/iptv";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { flashNote } from "./TasteButtons";
import { onColor, teamColors, washBackground } from "../lib/broadcast";
import { Odo } from "./Odo";
import "../fonts";
import "../screens/iptv/iptv.css";

const EVENT = "jellytip-match-info";
export const openMatchInfo = (id: string) => window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: id }));

/** ⓘ for a tile or the player; nothing without a match id or when the server has no match route. */
export function MatchInfoButton({ fixture, id, className = "" }: { fixture?: Fixture | null; id?: string | null; className?: string }) {
  const [, tick] = useState(0);
  useEffect(() => onMatchInfoAvailability(() => tick((n) => n + 1)), []);
  const mid = fixture?.id ?? id;
  if (!mid || !matchInfoAvailable(mid)) return null;
  return (
    <button type="button" className={`info-btn ${className}`.trim()} data-nav onClick={() => openMatchInfo(mid)} aria-label="Match info" title="Match info">
      <svg viewBox="0 0 24 24" aria-hidden>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v6M12 7.6v.2" />
      </svg>
    </button>
  );
}

/** The small "identified" mark next to a title Jellytip worked out (the listing did not name the match). */
export const IdentifiedMark = () => (
  <span className="identified" title="Jellytip identified this match from the fixture list">
    identified
  </span>
);

/** Crest hosts the page's CSP allows (public CDNs); anything else is a companion-proxied path. */
const CDN = /^https:\/\/(a\.espncdn\.com|[a-z0-9-]+\.thesportsdb\.com)\//i;
const artSrc = (u: string | null | undefined, size: 96 | 480): string | null => (u ? (CDN.test(u) ? u : /^https?:\/\//i.test(u) ? null : logoSrc(u, size)) : null);

function Crest({ s, side }: { s: Side; side: "l" | "r" }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? null : artSrc(s.logo, 96);
  // Tennis: a country flag instead of a crest
  if (!src && s.country && flagOf(s.country)) return <span className={`mi-crest flagc in-${side}`} aria-label={s.country}>{flagOf(s.country)}</span>;
  return src ? <img className={`mi-crest in-${side}`} src={src} alt="" onError={() => setFailed(true)} /> : <span className={`mi-crest blank in-${side}`}>{s.short ?? s.name.slice(0, 3)}</span>;
}

const ago = (at: number | null) => (at ? new Date(at * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) : null);

function Lineup({ d }: { d: MatchDetail }) {
  const l = d.lineups;
  if (!l) return null;
  const [hc, ac] = heroColors(d.fixture);
  const col = (name: string, ps: Player[], color: string) => (
    <div className="mi-col" style={{ background: tint(color, 0.18), boxShadow: `inset 0 3px 0 ${color}` }}>
      <h4>{name}</h4>
      <ul className="mi-list">
        {ps.map((p) => (
          <li key={p.name + (p.num ?? "")} className={p.starter ? "" : "sub"}>
            {p.num && <span className="mi-num">{p.num}</span>}
            {p.name}
            {p.pos && <span className="iptv-hint"> {p.pos}</span>}
          </li>
        ))}
      </ul>
    </div>
  );
  return (
    <section>
      <h3>Line-ups</h3>
      {l.announced && (l.home.length || l.away.length) ? (
        <div className="mi-cols">
          {col(d.fixture.home.name, l.home, hc)}
          {col(d.fixture.away.name, l.away, ac)}
        </div>
      ) : (
        <p className="iptv-hint">Not announced yet.</p>
      )}
    </section>
  );
}

/** Mounted once (App). */
export function MatchInfoHost() {
  const [id, setId] = useState<string | null>(null);
  const [d, setD] = useState<MatchDetail | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "missing" | "error">("loading");
  const [scoreMsg, setScoreMsg] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEscapeClose(() => setId(null), id != null);
  useEffect(() => {
    const on = (e: Event) => {
      setId((e as CustomEvent<string>).detail);
      setD(null);
      setState("loading");
      setScoreMsg(null);
      setMsg(null);
    };
    window.addEventListener(EVENT, on);
    return () => window.removeEventListener(EVENT, on);
  }, []);
  // The server answers `pending` while it fetches line-ups and odds: ask again a few times
  useEffect(() => {
    if (!id) return;
    let alive = true;
    let tries = 0;
    let timer: number | undefined;
    const load = async () => {
      const a = await matchDetail(id);
      if (!alive) return;
      // Unknown match, no access or no route: the modal goes away (the icon hides itself)
      if (a === "missing") {
        setId(null);
        return flashNote("No match info for this one.");
      }
      if (!a) return setState("error");
      setD(a);
      setState("ok");
      if (a.pending && ++tries < 4) timer = window.setTimeout(load, 3000);
    };
    void load();
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [id]);

  if (!id) return null;
  const close = () => setId(null);
  const f = d?.fixture;
  const now = Date.now() / 1000;
  const best = d?.channels[0];
  const reveal = async () => {
    setScoreMsg("Checking…");
    const a = await matchDetail(id, true);
    if (a && a !== "missing" && a.fixture.score) {
      setD(a);
      setScoreMsg(null);
    } else setScoreMsg("No score available.");
  };
  return (
    <div className="livetv-sheet-back" onClick={close}>
      <div className="livetv-sheet match-info bc" role="dialog" aria-label="Match info" style={f ? { background: washBackground(teamColors(f)) } : undefined} onClick={(e) => e.stopPropagation()}>
        <SheetClose onClose={close} />
        {state === "loading" && <p className="iptv-hint">Loading…</p>}
        {state === "missing" && <p className="iptv-hint">No more is known about this match yet.</p>}
        {state === "error" && <p className="iptv-err">Couldn't load the match.</p>}
        {f && d && (
          <>
            {(() => {
              const c = teamColors(f);
              const tag = f.status === "in" ? `LIVE${f.score?.clock ? ` · ${f.score.clock}` : ""}${f.score?.period ? ` · ${f.score.period}` : ""}` : f.status === "post" ? "FINAL" : f.startsAt ? startsIn(f.startsAt, now).toUpperCase() : "";
              const slab = (side: "home" | "away", cls: "l" | "r") => (
                <div className={`bc-mslab ${cls}`} style={{ ["--sc" as string]: side === "home" ? c.home : c.away, ["--sx" as string]: onColor(side === "home" ? c.home : c.away) }}>
                  <Crest s={f[side]} side={cls} />
                  <span className="nm">
                    {f[side].name}
                    {f[side].record && <small>{f[side].record}</small>}
                  </span>
                  <span className="sc">{f.score ? <Odo value={f.score[side]} /> : ""}</span>
                </div>
              );
              if (f.kind === "event")
                return (
                  <>
                    <div className="bc-mh event">
                      <div className="bc-mslab l" style={{ ["--sc" as string]: c.home, ["--sx" as string]: onColor(c.home) }}>
                        <span className="nm">{f.title}</span>
                      </div>
                      {tag && (
                        <span className="bc-tag">
                          {f.status === "in" && <i className="mi-pulse" aria-hidden />}
                          {tag}
                        </span>
                      )}
                    </div>
                    <div className="bc-sub">
                      {f.league && <b>{f.league}</b>}
                      {venueLine(f) && <span className="bc-venue">{venueLine(f)}</span>}
                    </div>
                    {f.participants.length > 0 && <p className="bc-parts">{f.participants.slice(0, 12).join(" · ")}</p>}
                  </>
                );
              return (
                <>
                  <div className="bc-mh">
                    {slab("home", "l")}
                    {slab("away", "r")}
                    {tag && (
                      <span className="bc-tag">
                        {f.status === "in" && <i className="mi-pulse" aria-hidden />}
                        {tag}
                      </span>
                    )}
                  </div>
                  <div className="bc-sub">
                    {f.league && <b>{f.league}</b>}
                    {venueLine(f) && (
                      <span className="bc-venue">
                        <svg viewBox="0 0 24 24" aria-hidden>
                          <path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11Z" />
                          <circle cx="12" cy="10" r="2.3" />
                        </svg>
                        {venueLine(f)}
                      </span>
                    )}
                  </div>
                </>
              );
            })()}
            {!f.score && (f.status === "in" || f.status === "post") && (
              <div className="buttons">
                <button className="btn small" data-nav onClick={() => void reveal()}>
                  Show score
                </button>
                {scoreMsg && <span className="iptv-hint">{scoreMsg}</span>}
              </div>
            )}
            {f.inferred && <p className="iptv-hint">Jellytip matched this listing to the fixture list.</p>}

            {(d.channels.length > 0 || d.broadcasts.length > 0) && (
              <section>
                <h3>Where to watch</h3>
                {d.broadcasts.length > 0 && <p className="iptv-hint">{d.broadcasts.join(" · ")}</p>}
                <div className="buttons">
                  {d.channels.slice(0, 6).map((c, i) => (
                    <button key={c.id} className={`btn small${i === 0 ? " primary" : ""}`} data-nav onClick={() => (close(), go(livePath(c.id, c.name, { alternates: d.channels.filter((x) => x.id !== c.id).map((x) => ({ id: x.id, name: x.name })), matchId: f.id })))}>
                      {c.name}
                      {c.quality && c.quality !== "unknown" && <span className="livetv-count">{c.quality}</span>}
                    </button>
                  ))}
                </div>
              </section>
            )}

            {f.kind !== "event" && d.odds && (d.odds.home || d.odds.away || d.odds.draw) && (
              <section>
                <h3>Odds</h3>
                {(() => {
                  const split = oddsSplit(d.odds.home, d.odds.away);
                  const [hc, ac] = heroColors(f);
                  return split ? (
                    <div className="mi-odds" aria-label={`${f.home.name} ${Math.round(split.home * 100)}%, ${f.away.name} ${Math.round(split.away * 100)}%`}>
                      <div className="mi-oddsbar">
                        <span style={{ width: `${split.home * 100}%`, background: hc }} />
                        <span style={{ width: `${split.away * 100}%`, background: ac }} />
                      </div>
                      <div className="mi-oddsnum">
                        <strong>{f.home.short ?? f.home.name} {Math.round(split.home * 100)}%</strong>
                        <strong>{Math.round(split.away * 100)}% {f.away.short ?? f.away.name}</strong>
                      </div>
                    </div>
                  ) : null;
                })()}
                <ul className="mi-list">
                  {d.odds.home && <li>{f.home.name}: {d.odds.home}</li>}
                  {d.odds.draw && <li>Draw: {d.odds.draw}</li>}
                  {d.odds.away && <li>{f.away.name}: {d.odds.away}</li>}
                  {d.odds.overUnder && <li>Over / under: {d.odds.overUnder}</li>}
                </ul>
                <p className="iptv-hint">{[d.odds.provider, ago(d.odds.updatedAt) ? `updated ${ago(d.odds.updatedAt)}` : null].filter(Boolean).join(" · ")}</p>
              </section>
            )}

            {f.kind !== "event" && <Lineup d={d} />}

            {(d.form || d.h2h.length > 0 || d.injuries.length > 0) && (
              <section>
                <h3>Form and head to head</h3>
                {d.form && (
                  <div className="mi-formrows">
                    {([[f.home.name, d.form.home], [f.away.name, d.form.away]] as [string, string[]][]).map(([n, fm]) => (
                      <div key={n} className="mi-formrow">
                        <span>{n}</span>
                        <span className="mi-chips">
                          {fm.length ? fm.map((c, i) => <b key={i} className={`mi-chip ${formTone(c)}`}>{c.toUpperCase().slice(0, 1)}</b>) : "–"}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
                {d.h2h.length > 0 && (
                  <ul className="mi-list">
                    {d.h2h.slice(0, 5).map((x, i) => <li key={i}>{x}</li>)}
                  </ul>
                )}
                {d.injuries.length > 0 && <p className="iptv-hint">Injuries: {d.injuries.slice(0, 6).join(", ")}</p>}
              </section>
            )}

            {msg && <p className="iptv-hint">{msg}</p>}
            <div className="buttons">
              {best && f.status !== "post" && (
                <button className="btn primary" data-nav data-autofocus onClick={() => (close(), go(livePath(best.id, best.name, { alternates: d.channels.slice(1).map((x) => ({ id: x.id, name: x.name })), matchId: f.id })))}>
                  Watch
                </button>
              )}
              {f.status === "pre" && f.startsAt && best && (
                <button
                  className="btn"
                  data-nav
                  onClick={async () => setMsg((await addReminderFor({ key: f.id, title: matchupTitle(f), start: f.startsAt!, stop: f.startsAt! + 3 * 3600, channelId: best.id, channelName: best.name })) ?? "We'll remind you before it starts.")}
                >
                  Remind me
                </button>
              )}
              {f.status !== "post" && f.startsAt && best && (
                <button
                  className="btn"
                  data-nav
                  onClick={async () => {
                    const a = await addRecording(best.id, f.startsAt!);
                    if (a.status === 200 || a.status === 201) {
                      void refreshRecs(true);
                      flashNote("Recording scheduled. It'll appear on Home.");
                    } else setMsg(a.data?.error ?? `Server said ${a.status}`);
                  }}
                >
                  Record
                </button>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
