// Settings → Live sport: favourite teams and leagues (the server puts what is on now first in every live view),
// a summary of the stream checks and, for admins, "Check streams now". Shown only to people with Live TV and prefs.
import { useEffect, useState } from "react";
import { getSession } from "../api/jellyfin";
import { hasLiveTv, probeNow, qualitySummary, sportSuggest } from "../api/iptv";
import { savePrefs } from "../api/me";
import { forgetLearned, learnedTaste, setMuted } from "../api/sportTaste";
import type { Learned } from "../lib/sportMute";
import type { Prefs } from "../lib/prefs";
import { BUCKET_NAME, toggleName, type ProviderQuality, type QualitySummary, type Suggestion } from "../lib/sport";

const when = (t: number) => new Date(t * 1000).toLocaleString(undefined, { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });

/** First-pass progress, plus what the checker is doing (running, waiting for the night window, backing off). */
function progressText(p: ProviderQuality): string {
  const now = Date.now() / 1000;
  const bits: string[] = [];
  if (p.progress) bits.push(`${p.progress.done} of ${p.progress.total} channels checked`);
  if (p.running) bits.push("checking now");
  else if (p.backoffUntil && p.backoffUntil > now) bits.push(`paused until ${when(p.backoffUntil)}`);
  else if (!p.onboarded && p.autoAfter && p.autoAfter > now) bits.push(`first automatic check ${when(p.autoAfter)}`);
  return bits.join(" · ");
}

export function SportSettings({ prefs }: { prefs: Prefs }) {
  const [live, setLive] = useState(false);
  const [q, setQ] = useState("");
  const [found, setFound] = useState<Suggestion[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [summary, setSummary] = useState<QualitySummary | null>(null);
  const [checking, setChecking] = useState(false);
  const [learned, setLearned] = useState<Learned[] | null>(null);
  const { teams, leagues, muted } = prefs.sport;
  const admin = !!getSession()?.isAdmin;

  useEffect(() => {
    let alive = true;
    void hasLiveTv().then((v) => {
      if (!alive || !v) return;
      setLive(true);
      void qualitySummary().then((s) => alive && setSummary(s));
      void learnedTaste().then((l) => alive && setLearned(l));
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
        <span className="settings-sub">Teams, leagues and tournaments you follow come first in Live TV and on Home when they are on now.</span>
        <div className="chips settings-chips-gap" aria-label="Your teams, leagues and tournaments">
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
          <input className="settings-input" data-nav value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search teams, leagues &amp; tournaments" spellCheck={false} maxLength={60} aria-label="Search teams, leagues and tournaments" />
        </div>
        {found.length > 0 && (
          <div className="chips settings-chips-gap" aria-label="Suggestions">
            {found.slice(0, 40).map((s) => (
              <button key={`${s.type}:${s.name}`} className={`chip${picked(s) ? " on" : ""}`} data-nav aria-pressed={picked(s)} onClick={() => toggle(s)}>
                {s.name}
                <span className="livetv-count">{s.tournament ? "Tournament" : s.type === "league" ? "League" : "Team"}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="settings-block settings-pad">
        <strong>Not interested</strong>
        <span className="settings-sub">Hidden from live sport, Home and upcoming. They stay in the full guide.</span>
        <div className="chips settings-chips-gap" aria-label="Not interested">
          {muted.sports.length + muted.leagues.length + muted.tags.length === 0 && <span className="settings-sub">Nothing hidden. Use the menu on a live sport tile (right-click, long-press or M).</span>}
          {(["sports", "leagues", "tags"] as const).flatMap((f) =>
            muted[f].map((n) => (
              <button key={`${f}:${n}`} className="chip on" data-nav aria-label={`Show ${n} again`} onClick={async () => setMsg(await setMuted(f, n, false))}>
                {n} <span aria-hidden>×</span>
              </button>
            )),
          )}
        </div>
      </div>

      {learned && learned.length > 0 && (
        <div className="settings-block settings-pad">
          <strong>Learned from your viewing</strong>
          <span className="settings-sub">Jellytip thinks you like these, so they rank higher. Remove one to start fresh.</span>
          <div className="chips settings-chips-gap" aria-label="Learned from your viewing">
            {learned.slice(0, 20).map((l) => (
              <button
                key={l.key}
                className="chip on"
                data-nav
                aria-label={`Forget ${l.label}`}
                onClick={async () => {
                  const err = await forgetLearned(l.key);
                  if (!err) setLearned((cur) => cur?.filter((x) => x.key !== l.key) ?? null);
                  setMsg(err);
                }}
              >
                {l.label} <span aria-hidden>×</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {summary && (
        <div className="settings-block settings-pad">
          <strong>Stream quality</strong>
          {summary.providers.map((p) => (
            <div key={p.name} className="settings-sub">
              {summary.providers.length > 1 && <strong>{p.name}: </strong>}
              {p.counts.length ? p.counts.map((c) => `${BUCKET_NAME[c.bucket]} ${c.n}`).join(" · ") : "No channels checked yet."}
              <br />
              {progressText(p)}
            </div>
          ))}
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
