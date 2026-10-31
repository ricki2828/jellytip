// Match info: an ⓘ button for sport tiles and the player, and the modal it opens (header, where to watch, odds, line-ups,
// form and head to head, Watch / Remind me / Record, "Show score"). Every field is optional. Contract: ~/ai/plans/jellytip-live-sport.md.
import { useEffect, useState } from "react";
import { addRecording } from "../api/iptv";
import { addReminderFor } from "../api/reminderStore";
import { matchDetail, matchInfoAvailable, onMatchInfoAvailability } from "../api/matchInfo";
import { refreshRecs } from "../api/recStore";
import { livePath } from "../lib/iptvPlay";
import { go } from "../lib/router";
import { matchupTitle, statusLabel, venueLine, type Fixture, type MatchDetail, type Player, type Side } from "../lib/matchInfo";
import { logoSrc } from "../api/iptv";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { flashNote } from "./TasteButtons";
import "../screens/iptv/iptv.css";

const EVENT = "jellytip-match-info";
export const openMatchInfo = (id: string) => window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: id }));

/** ⓘ for a tile or the player; nothing without a match id or when the server has no match route. */
export function MatchInfoButton({ fixture, id, className = "" }: { fixture?: Fixture | null; id?: string | null; className?: string }) {
  const [, tick] = useState(0);
  useEffect(() => onMatchInfoAvailability(() => tick((n) => n + 1)), []);
  const mid = fixture?.id ?? id;
  if (!mid || !matchInfoAvailable()) return null;
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

function Crest({ s }: { s: Side }) {
  const [failed, setFailed] = useState(false);
  const src = s.logo && !failed ? (/^https?:\/\//i.test(s.logo) ? null : logoSrc(s.logo, 96)) : null;
  return src ? <img className="mi-crest" src={src} alt="" onError={() => setFailed(true)} /> : <span className="mi-crest blank">{s.short ?? s.name.slice(0, 3)}</span>;
}

function Team({ s }: { s: Side }) {
  return (
    <div className="mi-team">
      <Crest s={s} />
      <strong>{s.name}</strong>
      {s.record && <span className="iptv-hint">{s.record}</span>}
    </div>
  );
}

const ago = (at: number | null) => (at ? new Date(at * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) : null);

function Lineup({ d }: { d: MatchDetail }) {
  const l = d.lineups;
  if (!l) return null;
  const col = (name: string, ps: Player[]) => (
    <div>
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
          {col(d.fixture.home.name, l.home)}
          {col(d.fixture.away.name, l.away)}
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
  const [showScore, setShowScore] = useState(false);
  const [scoreMsg, setScoreMsg] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEscapeClose(() => setId(null), id != null);
  useEffect(() => {
    const on = (e: Event) => {
      setId((e as CustomEvent<string>).detail);
      setD(null);
      setState("loading");
      setShowScore(false);
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
      if (a === "missing") return setState("missing");
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
      setShowScore(true);
      setScoreMsg(null);
    } else setScoreMsg("No score available.");
  };
  return (
    <div className="livetv-sheet-back" onClick={close}>
      <div className="livetv-sheet match-info" role="dialog" aria-label="Match info" onClick={(e) => e.stopPropagation()}>
        <SheetClose onClose={close} />
        {state === "loading" && <p className="iptv-hint">Loading…</p>}
        {state === "missing" && <p className="iptv-hint">No more is known about this match yet.</p>}
        {state === "error" && <p className="iptv-err">Couldn't load the match.</p>}
        {f && d && (
          <>
            <p className="iptv-hint">{[f.league, statusLabel(f, now), venueLine(f)].filter(Boolean).join(" · ")}</p>
            <div className="mi-head">
              <Team s={f.home} />
              <div className="mi-score">
                {f.score ? (
                  <strong>
                    {f.score.home} – {f.score.away}
                  </strong>
                ) : (
                  <span className="iptv-hint">v</span>
                )}
              </div>
              <Team s={f.away} />
            </div>
            {!f.score && (f.status === "in" || f.status === "post") && (
              <div className="buttons">
                <button className="btn small" data-nav onClick={() => void reveal()}>
                  Show score
                </button>
                {scoreMsg && <span className="iptv-hint">{scoreMsg}</span>}
              </div>
            )}
            {f.score && showScore === false && f.status !== "pre" && <p className="iptv-hint">{[f.score.period, f.score.clock].filter(Boolean).join(" · ")}</p>}
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

            {d.odds && (d.odds.home || d.odds.away || d.odds.draw) && (
              <section>
                <h3>Odds</h3>
                <ul className="mi-list">
                  {d.odds.home && <li>{f.home.name}: {d.odds.home}</li>}
                  {d.odds.draw && <li>Draw: {d.odds.draw}</li>}
                  {d.odds.away && <li>{f.away.name}: {d.odds.away}</li>}
                  {d.odds.overUnder && <li>Over / under: {d.odds.overUnder}</li>}
                </ul>
                <p className="iptv-hint">{[d.odds.provider, ago(d.odds.updatedAt) ? `updated ${ago(d.odds.updatedAt)}` : null].filter(Boolean).join(" · ")}</p>
              </section>
            )}

            <Lineup d={d} />

            {(d.form || d.h2h.length > 0 || d.injuries.length > 0) && (
              <section>
                <h3>Form and head to head</h3>
                {d.form && (
                  <p>
                    {f.home.name}: <span className="mi-form">{d.form.home.join(" ") || "–"}</span>
                    <br />
                    {f.away.name}: <span className="mi-form">{d.form.away.join(" ") || "–"}</span>
                  </p>
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
