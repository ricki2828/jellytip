// First-run "Make live sport yours": pick sports, then teams. Shown once to a person with IPTV live channels who hasn't set any sport
// preference, on Live TV or Home; skippable. Remembered on this device and in prefs (sport.setup).
import { useEffect, useState } from "react";
import { getSession } from "../api/jellyfin";
import { hasLiveTv, sportSuggest } from "../api/iptv";
import { savePrefs, usePrefs } from "../api/me";
import { MUTED_EVENT } from "../api/sportTaste";
import { useRoute } from "../lib/router";
import { toggleName } from "../lib/sport";
import { hasSportPrefs } from "../lib/sportMute";
import type { Suggestion } from "../lib/sport";
import { SportsPicker } from "./SportPicker";

const seenKey = () => `jellytip.sportSetup.${getSession()?.userId ?? ""}`;

export function SportSetup() {
  const route = useRoute();
  const { prefs } = usePrefs({ defer: true });
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState<1 | 2>(1);
  const [teams, setTeams] = useState<Suggestion[]>([]);
  const [q, setQ] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const eligibleRoute = route.name === "livetv" || route.name === "home";

  useEffect(() => {
    if (!eligibleRoute || !prefs || !prefs.sportsSupported || open || localStorage.getItem(seenKey()) || hasSportPrefs(prefs.sport)) return;
    let alive = true;
    // Let the page settle first, and only for people who have live channels
    const t = window.setTimeout(() => void hasLiveTv().then((v) => alive && v && setOpen(true)), 3500);
    return () => {
      alive = false;
      window.clearTimeout(t);
    };
  }, [eligibleRoute, prefs, open]);

  useEffect(() => {
    if (!open || step !== 2) return;
    let alive = true;
    const t = setTimeout(() => void sportSuggest(q.trim()).then((r) => alive && setTeams(r.filter((s) => s.type === "team"))), q ? 250 : 0);
    return () => {
      alive = false;
      clearTimeout(t);
    };
  }, [open, step, q]);

  if (!open || !prefs || !prefs.sportsSupported) return null;
  const finish = async () => {
    localStorage.setItem(seenKey(), "1");
    setOpen(false);
    void savePrefs({ sport: { setup: true } });
    window.dispatchEvent(new Event(MUTED_EVENT));
  };
  const picked = prefs.sport.teams;
  return (
    <div className="livetv-sheet-back" onClick={() => void finish()}>
      <div
        className="livetv-sheet sport-setup"
        role="dialog"
        aria-label="Make live sport yours"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape" || e.key === "Backspace") {
            if ((e.target as HTMLElement).tagName === "INPUT") return;
            e.preventDefault();
            e.stopPropagation();
            void finish();
          }
        }}
      >
        <h2>Make live sport yours</h2>
        {step === 1 ? (
          <>
            <p className="iptv-hint">Tap a sport to follow it, again to hide it. You can change this any time in Settings.</p>
            <SportsPicker prefs={prefs} onMsg={setMsg} />
          </>
        ) : (
          <>
            <p className="iptv-hint">Pick your teams. They go first when they are on.</p>
            <input className="settings-input" data-nav value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search teams" spellCheck={false} maxLength={60} aria-label="Search teams" />
            <div className="chips settings-chips-gap" aria-label="Teams">
              {[...picked.map((n) => ({ name: n, type: "team" as const })), ...teams.filter((s) => !picked.some((n) => n.toLowerCase() === s.name.toLowerCase()))].slice(0, 40).map((s) => {
                const on = picked.some((n) => n.toLowerCase() === s.name.toLowerCase());
                return (
                  <button key={s.name} className={`chip${on ? " on" : ""}`} data-nav aria-pressed={on} onClick={async () => setMsg(await savePrefs({ sport: { teams: toggleName(picked, s.name) } }))}>
                    {s.name}
                  </button>
                );
              })}
            </div>
          </>
        )}
        {msg && <p className="iptv-err">{msg}</p>}
        <div className="buttons">
          {step === 1 ? (
            <button className="btn primary" data-nav data-autofocus onClick={() => setStep(2)}>
              Next: teams
            </button>
          ) : (
            <button className="btn primary" data-nav data-autofocus onClick={() => void finish()}>
              Done
            </button>
          )}
          <button className="btn" data-nav onClick={() => void finish()}>
            Skip
          </button>
        </div>
      </div>
    </div>
  );
}
