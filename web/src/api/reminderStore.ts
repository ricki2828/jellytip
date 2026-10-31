// The person's reminders for upcoming sport, shared by the overlay toggle, the Home row and the nudge toast. A 404
// (server without the route) marks it unavailable and everything stays hidden. Polled every minute, but only while
// there are any.
import { useEffect, useState } from "react";
import { iptvGet, iptvPost } from "./iptv";
import { afterStartup } from "../lib/startup";
import { parseReminders, type Reminder } from "../lib/reminders";
import type { UpEvent } from "../lib/upcoming";

const EVENT = "jellytip-reminders";
const POLL_MS = 60_000;
let state: Reminder[] | null = null;
let unavailable = false;
let inflight: Promise<void> | null = null;
let timer: ReturnType<typeof setInterval> | null = null;

const emit = () => window.dispatchEvent(new Event(EVENT));

function schedule() {
  const want = !!state?.length;
  if (want && !timer) timer = setInterval(() => void refreshReminders(true), POLL_MS);
  else if (!want && timer) {
    clearInterval(timer);
    timer = null;
  }
}

export function refreshReminders(force = false): Promise<void> {
  if (inflight) return inflight;
  if (unavailable || (!force && state)) return Promise.resolve();
  inflight = iptvGet<unknown>("v1/iptv/reminders")
    .then((a) => {
      if (a.status === 404) unavailable = true;
      else if (a.status === 200) state = parseReminders(a.data);
      schedule();
      emit();
    })
    .finally(() => (inflight = null));
  return inflight;
}

window.addEventListener("jellytip-session", () => {
  state = null;
  unavailable = false;
  schedule();
  emit();
});

/** Set a reminder for an event; returns an error text, or null when it worked. */
export async function addReminder(e: UpEvent): Promise<string | null> {
  const best = e.channels[0];
  const a = await iptvPost<{ id?: number | string }>("v1/iptv/reminders", { key: e.key, channelId: best.id, start: e.start, stop: e.stop, title: e.title, league: e.league ?? undefined });
  if (a.status === 404) {
    unavailable = true;
    emit();
    return "Reminders aren't available on this server yet.";
  }
  if (a.status !== 200 && a.status !== 201) return a.data?.error ?? `Server said ${a.status}`;
  await refreshReminders(true);
  return null;
}

export async function removeReminder(id: string | number): Promise<string | null> {
  const a = await iptvPost<{ ok?: boolean }>(`v1/iptv/reminders/${id}/delete`);
  if (a.status !== 200 && a.status !== 204 && a.status !== 404) return a.data?.error ?? `Server said ${a.status}`;
  await refreshReminders(true);
  return null;
}

/** items: null until known or when the server has no route; available: false once it answered 404. */
export function useReminders(): { items: Reminder[] | null; available: boolean } {
  const [, tick] = useState(0);
  useEffect(() => {
    let alive = true;
    const on = () => tick((n) => n + 1);
    window.addEventListener(EVENT, on);
    void afterStartup().then(() => (alive ? refreshReminders() : undefined));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
  }, []);
  return { items: unavailable ? null : state, available: !unavailable };
}

/** Set a reminder from just the basics (the player knows the event key, start and channel but has no upcoming item). */
export const addReminderFor = (o: { key: string; title: string; start: number; stop: number; channelId: string; channelName: string }) =>
  addReminder({
    key: o.key,
    title: o.title,
    league: null,
    start: o.start,
    stop: o.stop,
    channels: [{ id: o.channelId, name: o.channelName, num: null, logo: null, quality: null }],
    favourite: false,
    kind: null,
    art: null,
    recordable: false,
    recordingId: null,
  });
