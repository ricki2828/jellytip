import { useEffect, useState } from "react";
import { fantasyLive, fantasyMissing, useFantasy } from "../api/fantasy";
import { FantasyMatchups } from "../components/FantasyMatchups";
import { snapshotStale, sourceAgeSeconds } from "../lib/fantasyPage";
import "./fantasy.css";

export function FantasyPage() {
  const live = useFantasy(true, true);
  const [failed, setFailed] = useState(false);
  const [checked, setChecked] = useState(false);
  const [lastCheck, setLastCheck] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    let alive = true;
    const refresh = async () => {
      if (document.hidden) return;
      try { const result = await fantasyLive(true); if (alive) { setFailed(!result); if (result) setLastCheck(Date.now()); setNow(Date.now()); } }
      catch { if (alive) setFailed(true); }
      finally { if (alive) setChecked(true); }
    };
    void refresh();
    const timer = setInterval(() => void refresh(), 45_000);
    const ageTimer = setInterval(() => setNow(Date.now()), 15_000);
    document.addEventListener("visibilitychange", refresh);
    return () => { alive = false; clearInterval(timer); clearInterval(ageTimer); document.removeEventListener("visibilitychange", refresh); };
  }, []);
  const status = live?.providers.yahoo.status;
  const stale = snapshotStale(live, failed, now);
  const age = sourceAgeSeconds(live?.updatedAt ?? null, now);
  const hasScores = !!live?.leagues.length;
  const linked = !!(live?.providers.sleeper.linked || live?.providers.yahoo.linked);
  const yahooNotice = status === "approval_required" ? <aside><h2>Yahoo application approval required</h2><p>Yahoo Fantasy access requires app owner approval. Sleeper scores remain available when linked.</p><a href="https://sports.yahoo.com/developer/application-confirmation/" target="_blank" rel="noreferrer">Confirm Yahoo Fantasy API application</a></aside>
    : status === "reconnect" ? <p role="status">Yahoo sign-in needs renewal. Reconnect in <a href="#/settings">Settings</a>.</p>
      : status === "unavailable" ? <p role="status">Yahoo scores are temporarily unavailable or awaiting refresh. Other linked leagues remain available.</p>
        : null;
  return <div className="fantasy-page">
    <h1>Fantasy{live?.week ? ` · Week ${live.week}` : ""}</h1>
    <p>All starters across your linked leagues.</p>
    {live && <div className="fantasy-freshness"><p>Source updated: {live.updatedAt != null ? new Date(live.updatedAt * 1000).toLocaleString() : "Unavailable"}{age != null && ` · ${age}s ago`}</p><p>Last successful check: {lastCheck != null ? new Date(lastCheck).toLocaleTimeString() : "Not yet checked"}</p></div>}
    {yahooNotice && !hasScores && yahooNotice}
    {status === "ready" && !hasScores && <p>Yahoo is connected and ready.</p>}
    {stale && !fantasyMissing() && <div className="fantasy-stale" role="status"><p>{failed ? "The latest check failed." : age == null ? "The source update time is unavailable." : "Live player data is older than 90 seconds."} {hasScores ? "Showing the last available scores." : "Scores are currently unavailable."}</p><button type="button" onClick={() => { void fantasyLive(true).then(result => { setFailed(!result); if (result) setLastCheck(Date.now()); setNow(Date.now()); }).catch(() => { setFailed(true); setNow(Date.now()); }); }}>Retry</button></div>}
    {!live && !checked && <p role="status">Loading fantasy scores…</p>}
    {checked && fantasyMissing() && <p>Fantasy is not available on this server yet.</p>}
    {live && !live.leagues.length && <p>{linked ? "No matchups available this week." : "No fantasy providers linked."} Manage linked accounts in <a href="#/settings">Settings</a>.</p>}
    {live && <FantasyMatchups live={live} />}
    {yahooNotice && hasScores && <div className="fantasy-provider-note">{yahooNotice}</div>}
  </div>;
}
