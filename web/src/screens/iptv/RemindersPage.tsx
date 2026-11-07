// #/reminders: the person's game reminders and the teams they get a reminder for every game of. Stop buttons for both.
import { useEffect, useState } from "react";
import { refreshReminders, removeReminder, useReminders } from "../../api/reminderStore";
import { refreshTeamReminders, removeTeamReminder, useTeamReminders } from "../../api/teamReminderStore";
import { flashNote } from "../../components/TasteButtons";
import { go } from "../../lib/router";
import { stateOf } from "../../lib/reminders";
import "./iptv.css";
import "./gameCentre.css";

const when = (t: number) => new Date(t * 1000).toLocaleString("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

export function RemindersPage() {
  const { items: games } = useReminders();
  const { items: teams, available } = useTeamReminders();
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => {
    void refreshReminders(true);
    void refreshTeamReminders(true);
  }, []);
  const now = Math.floor(Date.now() / 1000);
  const upcoming = (games ?? []).filter((r) => stateOf(r.event, now) !== "ended");
  const stop = async (key: string, run: () => Promise<string | null>, ok: string) => {
    if (busy) return;
    setBusy(key);
    const err = await run();
    setBusy(null);
    flashNote(err ?? ok);
  };
  return (
    <div className="iptv-page up-page rem-page">
      <div className="page-head">
        <h1 className="page-title">Reminders</h1>
        <button className="btn small" data-nav onClick={() => go("/games")}>Game Centre</button>
      </div>
      {available && (
        <section className="row">
          <h2 className="row-title">Team reminders</h2>
          {teams === null && <p className="iptv-hint">Loading…</p>}
          {teams && teams.length === 0 && <p className="iptv-hint">None yet. Open a game in Game Centre and pick "Remind me for every … game".</p>}
          {teams && teams.length > 0 && (
            <ul className="rem-list">
              {teams.map((t) => (
                <li key={t.id} className="rem-item">
                  <span>
                    Every {t.teamName} game
                    {t.league && <small>{t.league}</small>}
                  </span>
                  <button className="btn small" data-nav aria-disabled={busy === t.id || undefined} aria-label={`Stop reminding for every ${t.teamName} game`} onClick={() => void stop(t.id, () => removeTeamReminder(t.id), `Stopped reminders for ${t.teamName}`)}>
                    {busy === t.id ? "Saving…" : "Stop"}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      <section className="row">
        <h2 className="row-title">Game reminders</h2>
        {games === null && <p className="iptv-hint">Loading…</p>}
        {games && upcoming.length === 0 && <p className="iptv-hint">No game reminders set.</p>}
        {upcoming.length > 0 && (
          <ul className="rem-list">
            {upcoming.map((r) => (
              <li key={String(r.id)} className="rem-item">
                <span>
                  {r.event.title}
                  <small>{[r.event.league, when(r.event.start)].filter(Boolean).join(" · ")}</small>
                </span>
                <button className="btn small" data-nav aria-disabled={busy === String(r.id) || undefined} aria-label={`Remove reminder for ${r.event.title}`} onClick={() => void stop(String(r.id), () => removeReminder(r.id), "Reminder removed")}>
                  {busy === String(r.id) ? "Saving…" : "Remove"}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
