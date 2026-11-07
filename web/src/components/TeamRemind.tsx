// "Remind me for every <team> game": a secondary action per side wherever Remind me appears for a match.
import { useState } from "react";
import { addTeamReminder, removeTeamReminder, useTeamReminders } from "../api/teamReminderStore";
import { offerSides, remindEveryText, remindingText, shortTeam, type TeamSide } from "../lib/teamReminders";
import { flashNote } from "./TasteButtons";

export function TeamRemind({ sides, className = "" }: { sides: TeamSide[]; className?: string }) {
  const { items, available } = useTeamReminders();
  const [busy, setBusy] = useState<string | null>(null);
  const offer = offerSides(sides);
  if (!available || !items || !offer.length) return null;
  const toggle = async (s: (typeof offer)[number], id: string | null) => {
    if (busy) return;
    setBusy(s.teamId);
    const err = id ? await removeTeamReminder(id) : await addTeamReminder(s);
    setBusy(null);
    if (err) flashNote(err);
    else flashNote(id ? `Stopped reminders for ${shortTeam(s)}` : `We'll remind you for every ${shortTeam(s)} game`);
  };
  return (
    <div className={`team-remind ${className}`.trim()}>
      {offer.map((s) => {
        const on = items.find((t) => t.teamId === s.teamId);
        const nm = shortTeam(s);
        return on ? (
          <span key={s.teamId} className="team-remind-on" role="status">
            {remindingText(nm)} ·{" "}
            <button className="btn small" data-nav aria-disabled={busy === s.teamId || undefined} aria-label={`Stop reminding for every ${nm} game`} onClick={() => void toggle(s, on.id)}>
              {busy === s.teamId ? "Saving…" : "Stop"}
            </button>
          </span>
        ) : (
          <button key={s.teamId} className="btn small team-remind-btn" data-nav aria-disabled={busy === s.teamId || undefined} onClick={() => void toggle(s, null)}>
            {busy === s.teamId ? "Saving…" : remindEveryText(nm)}
          </button>
        );
      })}
    </div>
  );
}
