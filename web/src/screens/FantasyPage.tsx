import { useEffect, useState } from "react";
import { fantasyLive, fantasyMissing, useFantasy } from "../api/fantasy";
import { FantasyMatchups } from "../components/FantasyMatchups";
import "./fantasy.css";

export function FantasyPage() {
  const live = useFantasy(true, true);
  const [failed, setFailed] = useState(false);
  const [checked, setChecked] = useState(false);
  useEffect(() => {
    let alive = true;
    const refresh = async () => {
      if (document.hidden) return;
      try { const result = await fantasyLive(true); if (alive) setFailed(!result); }
      catch { if (alive) setFailed(true); }
      finally { if (alive) setChecked(true); }
    };
    void refresh();
    const timer = setInterval(() => void refresh(), 45_000);
    document.addEventListener("visibilitychange", refresh);
    return () => { alive = false; clearInterval(timer); document.removeEventListener("visibilitychange", refresh); };
  }, []);
  const status = live?.providers.yahoo.status;
  return <div className="fantasy-page">
    <h1>Fantasy{live?.week ? ` · Week ${live.week}` : ""}</h1>
    <p>All starters across your linked leagues. Scores refresh every 45 seconds while this page is visible.</p>
    {status === "approval_required" && <aside role="status"><h2>Yahoo application approval required</h2><p>Yahoo has denied this app access to Fantasy data. The app owner must complete Yahoo’s application approval and confirmation. Reconnecting your account will not fix this.</p><a href="https://sports.yahoo.com/developer/application-confirmation/" target="_blank" rel="noreferrer">Confirm Yahoo Fantasy API application</a></aside>}
    {status === "reconnect" && <p role="status">Yahoo sign-in needs renewal. Reconnect in <a href="#/settings">Settings</a>.</p>}
    {status === "unavailable" && <p role="status">Yahoo scores are temporarily unavailable or awaiting refresh. Other linked leagues remain available.</p>}
    {failed && <p role="status">Scores could not be refreshed. Any displayed scores may be out of date.</p>}
    {!live && !checked && <p role="status">Loading fantasy scores…</p>}
    {checked && fantasyMissing() && <p>Fantasy is not available on this server yet.</p>}
    {live && !live.leagues.length && <p>No matchups available yet. Manage linked accounts in <a href="#/settings">Settings</a>.</p>}
    {live && <FantasyMatchups live={live} />}
  </div>;
}
