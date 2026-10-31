// Settings → Live sport → Fantasy: link a Sleeper username (the server looks the leagues up), the scoring-alert toggle and a
// "Yahoo: coming soon" line. Hidden when the server has no fantasy route.
import { useEffect, useState } from "react";
import { fantasyLive, fantasyMissing, saveAlertMin, saveFantasyAlerts, saveSleeperUsername, startYahooConnect } from "../api/fantasy";
import type { Prefs } from "../lib/prefs";

export function FantasySettings({ prefs, onMsg }: { prefs: Prefs; onMsg: (m: string | null) => void }) {
  const linked = prefs.fantasy.sleeperUsername;
  const [name, setName] = useState(linked ?? "");
  const [busy, setBusy] = useState(false);
  const [leagues, setLeagues] = useState<string[] | null>(null);
  const [missing, setMissing] = useState(false);
  // Yahoo: `providers.yahoo` on /v1/fantasy/live says whether the server has an app (available) and whether this person connected (linked)
  const [yahoo, setYahoo] = useState<{ available: boolean; linked: boolean } | null>(null);
  useEffect(() => setName(linked ?? ""), [linked]);
  // Probe once (and list the leagues when an account is already linked); a 404 hides the whole section
  useEffect(() => {
    let alive = true;
    void fantasyLive(true).then((d) => {
      if (!alive) return;
      setMissing(fantasyMissing());
      setYahoo(d?.providers.yahoo ?? null);
      if (d && linked) setLeagues(d.leagues.map((l) => l.name));
    });
    return () => {
      alive = false;
    };
  }, [linked]);
  if (missing) return null;

  const link = async (username: string | null) => {
    setBusy(true);
    setLeagues(null);
    const err = await saveSleeperUsername(username);
    if (err) {
      setBusy(false);
      return onMsg(err);
    }
    onMsg(null);
    if (username) {
      const d = await fantasyLive(true);
      setMissing(fantasyMissing());
      setLeagues(d ? d.leagues.map((l) => l.name) : []);
    }
    setBusy(false);
  };

  return (
    <div className="settings-block settings-pad">
      <strong>Fantasy</strong>
      <span className="settings-sub">See your Sleeper matchup score on NFL games, and a pop-up when one of your players scores.</span>
      <div className="settings-inline settings-chips-gap">
        <input className="settings-input" data-nav value={name} onChange={(e) => setName(e.target.value)} placeholder="Sleeper username" spellCheck={false} autoCapitalize="none" maxLength={40} aria-label="Sleeper username" />
        <button className="btn small primary" data-nav disabled={busy || !name.trim() || name.trim() === linked} onClick={() => void link(name.trim())}>
          {linked ? "Change" : "Link"}
        </button>
        {linked && (
          <button className="btn small" data-nav disabled={busy} onClick={() => void link(null)}>
            Unlink
          </button>
        )}
      </div>
      {linked && leagues && (leagues.length > 0 ? <p className="settings-sub">Leagues found: {leagues.join(", ")}</p> : <p className="settings-sub">No leagues found for “{linked}” this season.</p>)}
      {linked && (
        <label className="iptv-check">
          <input type="checkbox" data-nav checked={prefs.fantasy.alerts} onChange={async (e) => onMsg(await saveFantasyAlerts(e.target.checked))} />
          <span>Scoring alerts (a pop-up when a player of yours or your opponent's scores)</span>
        </label>
      )}
      {linked && prefs.fantasy.alerts && (
        <div className="settings-inline">
          <span className="settings-sub">Alert threshold</span>
          <button className="btn small" data-nav aria-label="Lower the threshold" disabled={prefs.fantasy.alertMin <= 1} onClick={async () => onMsg(await saveAlertMin(prefs.fantasy.alertMin - 1))}>
            −
          </button>
          <strong aria-live="polite">{prefs.fantasy.alertMin} pts</strong>
          <button className="btn small" data-nav aria-label="Raise the threshold" disabled={prefs.fantasy.alertMin >= 10} onClick={async () => onMsg(await saveAlertMin(prefs.fantasy.alertMin + 1))}>
            +
          </button>
        </div>
      )}
      {yahoo?.linked ? (
        <span className="settings-sub">Yahoo: Connected</span>
      ) : yahoo?.available ? (
        <div className="settings-inline">
          <button
            className="btn small"
            data-nav
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              const r = await startYahooConnect();
              setBusy(false);
              if ("url" in r) window.location.href = r.url;
              else onMsg(r.error);
            }}
          >
            Connect Yahoo
          </button>
          <span className="settings-sub">Sign in with Yahoo to link your Yahoo Fantasy leagues.</span>
        </div>
      ) : (
        <span className="settings-sub">Yahoo: coming soon</span>
      )}
    </div>
  );
}
