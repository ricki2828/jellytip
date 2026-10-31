// The in-event "Live" drawer in the player: Stats · Plays · Odds · Chat, refreshed every 15 s while open. It slides in from the
// right (landscape-friendly) and the video keeps playing. Odds are percentages with their source, never a betting link.
import { useEffect, useState } from "react";
import { liveStats, matchDetail } from "../api/matchInfo";
import { agoText, maskText, mayShow, pct, volumeText, type LiveStats } from "../lib/liveStats";
import type { Fixture } from "../lib/matchInfo";
import { SheetClose, useEscapeClose } from "./BackArrow";
import "../screens/iptv/iptv.css";

type Tab = "stats" | "plays" | "odds" | "chat";
const TABS: [Tab, string][] = [
  ["stats", "Stats"],
  ["plays", "Plays"],
  ["odds", "Odds"],
  ["chat", "Chat"],
];
const REFRESH_MS = 15_000;

export function LiveDrawer({ matchId, liveNow, spoilers, onClose }: { matchId: string; liveNow: boolean; spoilers: boolean; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>("stats");
  const [d, setD] = useState<LiveStats | null>(null);
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "missing" | "error">("loading");
  const [now, setNow] = useState(() => Date.now() / 1000);
  const allowed = mayShow(liveNow, spoilers);
  useEscapeClose(onClose);

  useEffect(() => {
    if (!allowed) return;
    let alive = true;
    let timer: number | undefined;
    const load = async () => {
      const a = await liveStats(matchId);
      if (!alive) return;
      setNow(Date.now() / 1000);
      if (a === "missing") setState("missing");
      else if (!a) setState((s) => (s === "ok" ? s : "error")); // keep what we have when a refresh fails
      else {
        setD(a);
        setState("ok");
      }
      timer = window.setTimeout(load, REFRESH_MS);
    };
    void load();
    // Team names for the odds and score lines
    void matchDetail(matchId).then((m) => alive && m && m !== "missing" && setFixture(m.fixture));
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [matchId, allowed]);

  const home = fixture?.home.name ?? "Home";
  const away = fixture?.away.name ?? "Away";
  return (
    <aside className="live-drawer" role="dialog" aria-label="Live stats" onClick={(e) => e.stopPropagation()}>
      <SheetClose onClose={onClose} />
      <h3>Live</h3>
      {!allowed ? (
        <p className="iptv-hint">Live stats would spoil this recording. You can turn on "Show scores (spoilers)" in Settings → Live sport.</p>
      ) : (
        <>
          <div className="chips" role="tablist" aria-label="Live panel">
            {TABS.map(([k, label]) => (
              <button key={k} className={`chip${tab === k ? " on" : ""}`} data-nav role="tab" aria-selected={tab === k} onClick={() => setTab(k)}>
                {label}
              </button>
            ))}
          </div>
          {state === "loading" && <p className="iptv-hint">Loading…</p>}
          {state === "missing" && <p className="iptv-hint">Live stats aren't available for this match.</p>}
          {state === "error" && <p className="iptv-hint">Couldn't load live stats. Trying again…</p>}
          {d && (
            <div className="ld-body">
              {(d.score || d.clock || d.period) && (
                <p className="ld-score">
                  {d.score ? `${home} ${d.score.home} – ${d.score.away} ${away}` : ""}
                  <span className="iptv-hint">{[d.period, d.clock].filter(Boolean).join(" · ") ? ` ${[d.period, d.clock].filter(Boolean).join(" · ")}` : ""}</span>
                </p>
              )}
              {tab === "stats" && (
                <>
                  {d.stats.length === 0 && d.leaders.length === 0 && <p className="iptv-hint">No stats yet.</p>}
                  {d.stats.length > 0 && (
                    <table className="ld-table">
                      <thead>
                        <tr>
                          <th>{home}</th>
                          <th />
                          <th>{away}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {d.stats.map((s) => (
                          <tr key={s.label}>
                            <td>{s.home}</td>
                            <td className="iptv-hint">{s.label}</td>
                            <td>{s.away}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  {d.leaders.length > 0 && (
                    <ul className="mi-list">
                      {d.leaders.map((l, i) => (
                        <li key={i}>
                          <strong>{l.name}</strong> {l.team && <span className="iptv-hint">{l.team} · </span>}
                          {l.line}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
              {tab === "plays" && (
                <>
                  {d.plays.length === 0 && <p className="iptv-hint">No plays yet.</p>}
                  <ul className="mi-list ld-plays">
                    {d.plays.map((p, i) => (
                      <li key={i} className={p.scoring ? "score" : ""}>
                        <span className="mi-num">{[p.period, p.clock].filter(Boolean).join(" ")}</span>
                        {p.text}
                      </li>
                    ))}
                  </ul>
                </>
              )}
              {tab === "odds" && (
                <>
                  {!d.market && d.winProb == null && <p className="iptv-hint">No odds for this match.</p>}
                  {d.market && (
                    <>
                      <p className="ld-odds">
                        {home} {pct(d.market.home)} · {away} {pct(d.market.away)}
                        {d.market.draw != null && ` · Draw ${pct(d.market.draw)}`}
                      </p>
                      <p className="iptv-hint">{[d.market.source === "polymarket" ? "Polymarket" : d.market.source, volumeText(d.market.volume), agoText(d.market.updatedAt, now)].filter(Boolean).join(" · ")}</p>
                    </>
                  )}
                  {d.winProb != null && (
                    <p className="iptv-hint">
                      Win probability: {home} {pct(d.winProb)}
                    </p>
                  )}
                </>
              )}
              {tab === "chat" && (
                <>
                  {(!d.chat || d.chat.comments.length === 0) && <p className="iptv-hint">No chat for this match.</p>}
                  {d.chat && d.chat.comments.length > 0 && (
                    <>
                      <ul className="ld-chat">
                        {d.chat.comments.map((c, i) => (
                          <li key={i}>
                            <strong>{c.author}</strong> {maskText(c.text)}
                          </li>
                        ))}
                      </ul>
                      <p className="iptv-hint">From {d.chat.source === "reddit" ? "Reddit" : d.chat.source}{d.chat.thread ? ` · ${d.chat.thread}` : ""}</p>
                    </>
                  )}
                </>
              )}
              <p className="iptv-hint">Updates every 15 s{agoText(d.updatedAt, now) ? ` · ${agoText(d.updatedAt, now)}` : ""}</p>
            </div>
          )}
        </>
      )}
    </aside>
  );
}
