import { useEffect, useState } from "react";
import { fantasyMissing, fantasyRefreshStatus, subscribeFantasyRefresh, useFantasy } from "../api/fantasy";
import { FantasyMatchups } from "../components/FantasyMatchups";
import { FantasySummary } from "../components/FantasySummary";
import { FantasyFreshness } from "../components/FantasyFreshness";
import "./fantasy.css";

export function FantasyPage() {
  const live = useFantasy(true, true);
  const [failed, setFailed] = useState(false);
  const [checked, setChecked] = useState(false);
  const [lastCheck, setLastCheck] = useState<number | null>(null);
  useEffect(() => {
    const unsubscribe = subscribeFantasyRefresh((result) => {
      const refreshed = fantasyRefreshStatus();
      setFailed(refreshed.failed);
      setChecked(refreshed.checked);
      if (result && !refreshed.failed) setLastCheck(Date.now());
    });
    const resetForSession = () => { setFailed(false); setChecked(false); setLastCheck(null); };
    window.addEventListener("jellytip-session", resetForSession);
    return () => { unsubscribe(); window.removeEventListener("jellytip-session", resetForSession); };
  }, []);
  const status = live?.providers.yahoo.status;
  const hasScores = !!live?.leagues.length;
  const linked = !!(live?.providers.sleeper.linked || live?.providers.yahoo.linked);
  const yahooNotice = status === "approval_required" ? <aside><h2>Yahoo application approval required</h2><p>Yahoo Fantasy access requires app owner approval. Sleeper scores remain available when linked.</p><a href="https://sports.yahoo.com/developer/application-confirmation/" target="_blank" rel="noreferrer">Confirm Yahoo Fantasy API application</a></aside>
    : status === "reconnect" ? <p role="status">Yahoo sign-in needs renewal. Reconnect in <a href="#/settings">Settings</a>.</p>
      : status === "unavailable" ? <p role="status">Yahoo scores are temporarily unavailable or awaiting refresh. Other linked leagues remain available.</p>
        : null;
  return <div className="fantasy-page">
    <h1>Fantasy{live?.week ? ` · Week ${live.week}` : ""}</h1>
    <p>All starters across your linked leagues.</p>
    {live && hasScores && <FantasySummary live={live} />}
    {live && <FantasyFreshness live={live} failed={failed} lastCheck={lastCheck} />}
    {yahooNotice && !hasScores && yahooNotice}
    {status === "ready" && !hasScores && <p>Yahoo is connected and ready.</p>}
    {!live && !checked && <p role="status">Loading fantasy scores…</p>}
    {checked && fantasyMissing() && <p>Fantasy is not available on this server yet.</p>}
    {live && !live.leagues.length && <p>{linked ? "No matchups available this week." : "No fantasy providers linked."} Manage linked accounts in <a href="#/settings">Settings</a>.</p>}
    {live && <FantasyMatchups live={live} />}
    {yahooNotice && hasScores && <div className="fantasy-provider-note">{yahooNotice}</div>}
  </div>;
}
