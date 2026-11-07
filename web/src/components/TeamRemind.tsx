// The Remind me control for a match, with "every <team> game" folded in. Tap opens a small inline menu: This game, Every <Home>
// game, Every <Away> game (each toggles). When anything is on, the button reads as one compact line ("Reminding: this game ·
// every Celtics game") and the same menu manages it. Without a team id (or on a server without team reminders) it is the plain
// Remind me toggle it always was.
import { useEffect, useRef, useState } from "react";
import { addTeamReminder, removeTeamReminder, useTeamReminders } from "../api/teamReminderStore";
import { offerSides, shortTeam, type TeamSide } from "../lib/teamReminders";
import { flashNote } from "./TasteButtons";

export function RemindControl({ thisOn, busy, label, sides, onThis, className = "btn small gc-remind" }: { thisOn: boolean; busy: boolean; label: string; sides: TeamSide[]; onThis: () => void | Promise<unknown>; className?: string }) {
  const { items, available } = useTeamReminders();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const offer = available && items ? offerSides(sides) : [];
  const onTeams = offer.flatMap((s) => {
    const t = items?.find((x) => x.teamId === s.teamId);
    return t ? [{ s, id: t.id }] : [];
  });
  useEffect(() => {
    if (!open) return;
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" || e.key === "Backspace") {
        e.stopPropagation();
        setOpen(false);
        wrap.current?.querySelector<HTMLElement>("button")?.focus();
      }
    };
    document.addEventListener("keydown", key, true);
    wrap.current?.querySelector<HTMLElement>("[role=menuitem]")?.focus();
    return () => document.removeEventListener("keydown", key, true);
  }, [open]);
  const any = thisOn || onTeams.length > 0;
  const summary = ["Reminding:", [thisOn ? "this game" : null, ...onTeams.map((t) => `every ${shortTeam(t.s)}`)].filter(Boolean).join(" · ")].join(" ");
  const run = async (fn: () => unknown, ok: string) => {
    if (saving || busy) return;
    setSaving(true);
    try {
      const err = await fn();
      if (typeof err === "string" && err) flashNote(err);
      else if (ok) flashNote(ok);
    } catch {
      flashNote("Couldn't save the reminder. Try again.");
    } finally {
      setSaving(false);
      setOpen(false);
    }
  };
  const working = busy || saving;
  if (!offer.length) {
    return (
      <button className={`${className}${thisOn ? " on" : ""}`} data-nav aria-pressed={thisOn} aria-disabled={working || undefined} aria-label={thisOn ? `Remove reminder for ${label}` : `Remind me about ${label}`} onClick={working ? undefined : () => void onThis()}>
        {working ? "Saving…" : thisOn ? "Reminder set ✓ (tap to remove)" : "Remind me"}
      </button>
    );
  }
  return (
    <div className="remind-ctl" ref={wrap}>
      <button className={`${className}${any ? " on" : ""}`} data-nav aria-haspopup="menu" aria-expanded={open} aria-disabled={working || undefined} aria-label={any ? `${summary} for ${label}. Manage` : `Remind me about ${label}`} onClick={working ? undefined : () => setOpen((o) => !o)}>
        {working ? "Saving…" : any ? `${summary} ▾` : "Remind me ▾"}
      </button>
      {open && (
        <div className="remind-menu" role="menu" aria-label="Reminder options">
          <button role="menuitem" className="btn small" data-nav onClick={() => void run(() => onThis(), "")}>
            {thisOn ? "Stop reminding for this game" : "This game"}
          </button>
          {offer.map((s) => {
            const on = onTeams.find((t) => t.s.teamId === s.teamId);
            const nm = shortTeam(s);
            return (
              <button key={s.teamId} role="menuitem" className="btn small" data-nav onClick={() => void run(() => (on ? removeTeamReminder(on.id) : addTeamReminder(s)), on ? `Stopped reminders for ${nm}` : `We'll remind you for every ${nm} game`)}>
                {on ? `Stop reminding for every ${nm} game` : `Every ${nm} game`}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
