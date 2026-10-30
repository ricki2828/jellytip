// Settings → Live sport: favourite teams and leagues (the server puts what is on now first in every live view),
// a summary of the stream checks and, for admins, "Check streams now". Shown only to people with Live TV and prefs.
import { useEffect, useState } from "react";
import { getSession } from "../api/jellyfin";
import { hasLiveTv, probeNow, qualitySummary, sportSuggest } from "../api/iptv";
import { savePrefs } from "../api/me";
import type { Prefs } from "../lib/prefs";
import { BUCKET_NAME, toggleName, type QualitySummary, type Suggestion } from "../lib/sport";

export function SportSettings({ prefs }: { prefs: Prefs }) {
  const [live, setLive] = useState(false);
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Suggestion[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [summary, setSummary] = useState<QualitySummary | null>(null);
  const [checking, setChecking] = useState(false);
  const { teams, leagues } = prefs.sport;
  const admin = !!getSession()?.isAdmin;

  useEffect(() => {
    let alive = true;
    void hasLiveTv().then((v) => {
      if (!alive || !v) return;
      setLive(true);
      void qualitySummary().then((s) => alive && setSummary(s));
    });
    return () => {
      alive = false;
    };
  }, []);

  // Suggestions follow the typing (and the empty box shows the server's starter list)
  useEffect(() => {
    if (!live) return;
    let alive = true;
    const t = setTimeout(() => void sportSuggest(q.trim()).then((r) => alive && setFound(r)), q ? 250 : 0);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [q, live]);

  if (!live) return null;

  const save = async (patch: { teams?: string[]; leagues?: string[] }) => setMsg(await savePrefs({ sport: patch }));
  const toggle = (s: Suggestion) => void (s.type === "team" ? save({ teams: toggleName(teams, s.name) }) : save({ leagues: toggleName(leagues, s.name) }));
  const picked = (s: Suggestion) => (s.type === "team" ? teams : leagues).some((x) => x.toLowerCase() === s.name.toLowerCase());
  const check = async () => {
    setChecking(true);
    const a = await probeNow();
    setChecking(false);
    setMsg(a.status === 200 || a.status === 202 ? "Checking streams when nobody is watching." : "Couldn't start the check.");
  };

  return (
    <>
      <h2 className="settings-head">Live sport</h2>
      <div className="settings-block settings-pad">
        <span className="settings-sub">Teams and leagues you follow come first in Live TV and on Home when they are on now.</span>
        <div className="chips settings-chips-gap" aria-label="Your teams and leagues">
          {teams.length + leagues.length === 0 && <span className="settings-sub">Nothing picked yet.</span>}
          {teams.map((n) => (
            <button key={`t:${n}`} className="chip on" data-nav aria-label={`Remove ${n}`} onClick={() => void save({ teams: toggleName(teams, n) })}>
              {n} <span aria-hidden>×</span>
            </button>
          ))}
          {leagues.map((n) => (
            <button key={`l:${n}`} className="chip on" data-nav aria-label={`Remove ${n}`} onClick={() => void save({ leagues: toggleName(leagues, n) })}>
              {n} <span aria-hidden>×</span>
            </button>
          ))}
        </div>
        <div className="settings-inline settings-chips-gap">
          <input className="settings-input" data-nav value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search teams and leagues" spellCheck={false} maxLength={60} aria-label="Search teams and leagues" />
        </div>
        {found.length > 0 && (
          <div className="chips settings-chips-gap" aria-label="Suggestions">
            {found.slice(0, 40).map((s) => (
              <button key={`${s.type}:${s.name}`} className={`chip${picked(s) ? " on" : ""}`} data-nav aria-pressed={picked(s)} onClick={() => toggle(s)}>
                {s.name}
                <span className="livetv-count">{s.type}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {summary && (
        <div className="settings-block settings-pad">
          <strong>Stream quality</strong>
          <span className="settings-sub">
            {summary.counts.length ? summary.counts.map((c) => `${BUCKET_NAME[c.bucket]} ${c.n}`).join(" · ") : "No channels checked yet."}
          </span>
          {summary.progress && (
            <span className="settings-sub">
              First check: {summary.progress.done} of {summary.progress.total} channels
              {summary.progress.done >= summary.progress.total ? " (done)" : ""}
            </span>
          )}
          {admin && (
            <div className="settings-inline settings-chips-gap">
              <button className="btn small" data-nav disabled={checking} onClick={() => void check()}>
                Check streams now
              </button>
            </div>
          )}
        </div>
      )}
      {!summary && admin && (
        <div className="settings-block settings-pad">
          <button className="btn small" data-nav disabled={checking} onClick={() => void check()}>
            Check streams now
          </button>
        </div>
      )}
      {msg && <p className="settings-err settings-pad">{msg}</p>}
    </>
  );
}
