// The person's "remind me for every <team> game" reminders. A 404 (server without the route) hides every team action.
import { useEffect, useState } from "react";
import { iptvGet, iptvPost } from "./iptv";
import { afterStartup } from "../lib/startup";
import { parseTeamReminders, type TeamReminder, type TeamSide } from "../lib/teamReminders";
import { refreshReminders } from "./reminderStore";

const EVENT = "jellytip-team-reminders";
let state: TeamReminder[] | null = null;
let unavailable = false;
let inflight: Promise<void> | null = null;

const emit = () => window.dispatchEvent(new Event(EVENT));

export function refreshTeamReminders(force = false): Promise<void> {
  if (inflight && !force) return inflight;
  if (unavailable || (!force && state)) return Promise.resolve();
  const run = () =>
    iptvGet<unknown>("v1/iptv/reminders/teams").then((a) => {
      if (a.status === 404) unavailable = true;
      else if (a.status === 200) state = parseTeamReminders(a.data);
      emit();
    });
  // A forced refresh (after an add / stop) must see the change: it queues behind a GET already in flight instead of reusing its older answer
  const p: Promise<void> = (inflight ? inflight.then(run, run) : run()).finally(() => {
    if (inflight === p) inflight = null;
  });
  inflight = p;
  return p;
}

window.addEventListener("jellytip-session", () => {
  state = null;
  unavailable = false;
  emit();
});

/** Turn it on for a team; returns an error text, or null. The server fills the game reminders a moment later, so the single-reminder list is refreshed twice. */
export async function addTeamReminder(s: TeamSide & { teamId: string }): Promise<string | null> {
  const a = await iptvPost<{ id?: string; error?: string }>("v1/iptv/reminders/teams", { teamId: s.teamId, teamName: s.name, sport: s.sport ?? undefined, league: s.league ?? undefined });
  if (a.status === 404) {
    unavailable = true;
    emit();
    return "Team reminders aren't available on this server yet.";
  }
  if (a.status !== 200 && a.status !== 201) return a.data?.error ?? `Server said ${a.status}`;
  await refreshTeamReminders(true);
  void refreshReminders(true);
  window.setTimeout(() => void refreshReminders(true), 6000);
  return null;
}

export async function removeTeamReminder(id: string): Promise<string | null> {
  const a = await iptvPost<{ ok?: boolean; error?: string }>(`v1/iptv/reminders/teams/${id}/delete`);
  if (a.status !== 200 && a.status !== 204 && a.status !== 404) return a.data?.error ?? `Server said ${a.status}`;
  await refreshTeamReminders(true);
  void refreshReminders(true);
  return null;
}

export function useTeamReminders(): { items: TeamReminder[] | null; available: boolean } {
  const [, tick] = useState(0);
  useEffect(() => {
    let alive = true;
    const on = () => tick((n) => n + 1);
    window.addEventListener(EVENT, on);
    void afterStartup().then(() => (alive ? refreshTeamReminders() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
  }, []);
  return { items: unavailable ? null : state, available: !unavailable };
}
