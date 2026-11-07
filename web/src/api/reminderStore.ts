// The person's reminders for upcoming sport, shared by the overlay toggle, the Home row and the nudge toast. A 404
// (server without the route) marks it unavailable and everything stays hidden. Polled every minute, but only while
// there are any.
import { useEffect, useState } from "react";
import { iptvGet, iptvPost } from "./iptv";
import { afterStartup } from "../lib/startup";
import { hasStream, parseReminders, sameGame, type Reminder } from "../lib/reminders";
import { isNative, NativeReminders } from "../lib/native";
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

/** After a reminder was set: on the phone, ask for notification permission once (Android 13+) and sync; says so if it was refused earlier. */
async function afterAdd(): Promise<string | null> {
  if (!isNative) return null;
  const n = await NativeReminders.ensureNotifications().catch(() => null);
  void NativeReminders.sync().catch(() => undefined);
  return n?.denied ? "Reminder set, but phone notifications are off. Turn them on in Android Settings > Apps > Jellytip > Notifications." : null;
}

/** Set a reminder for an event; returns an error text, or null when it worked. A game with a fixture (fixture id or match id, from
 * `o` or the event itself) is ALWAYS a fixture reminder: the server picks the best channel when it fires. The channel path is only
 * for events with no fixture at all (a non-sport programme), or when the server refused the fixture one. */
export async function addReminder(e: UpEvent, o: { fid?: string | null; matchId?: string | null } = {}): Promise<string | null> {
  const fid = o.fid ?? e.fid ?? null;
  const matchId = o.matchId ?? e.fixture?.id ?? null;
  if (fid || matchId) {
    const r = await addFx({ fid, matchId });
    if (r.ok || !hasStream(e)) return r.msg;
  }
  const best = e.channels[0];
  const a = await iptvPost<{ id?: number | string }>("v1/iptv/reminders", { key: e.key, channelId: best.id, start: e.start, stop: e.stop, title: e.title, league: e.league ?? undefined });
  if (a.status === 404) {
    unavailable = true;
    emit();
    return "Reminders aren't available on this server yet.";
  }
  if (a.status !== 200 && a.status !== 201) return a.data?.error ?? `Server said ${a.status}`;
  await refreshReminders(true);
  return afterAdd();
}

/** Remind me for a fixture / legacy card: {fid} first (no channel needed); an older server answers 400/404, then {matchId} when known. */
export async function addFixtureReminder(o: { fid?: string | null; matchId?: string | null }): Promise<string | null> {
  return (await addFx(o)).msg;
}

/** ok: the server has the reminder (msg may still carry the phone-notifications hint); !ok: msg is the error. */
async function addFx(o: { fid?: string | null; matchId?: string | null }): Promise<{ ok: boolean; msg: string | null }> {
  const fail = (msg: string) => ({ ok: false, msg });
  let a = o.fid ? await iptvPost<{ id?: number | string; error?: string }>("v1/iptv/reminders", { fid: o.fid }) : null;
  if ((!a || a.status === 400 || a.status === 404) && o.matchId) a = await iptvPost<{ id?: number | string; error?: string }>("v1/iptv/reminders", { matchId: o.matchId });
  if (!a) return fail("Can't set a reminder for this one.");
  if (a.status === 404 && !o.matchId && !a.data?.error) {
    unavailable = true;
    emit();
    return fail("Reminders aren't available on this server yet.");
  }
  if (a.status !== 200 && a.status !== 201) return fail(a.data?.error ?? `Server said ${a.status}`);
  await refreshReminders(true);
  return { ok: true, msg: await afterAdd() };
}

/** Switch a reminder off: every row for the same game goes (key, fixture id or match id), so no twin keeps notifying. */
export async function removeReminder(id: string | number): Promise<string | null> {
  const me = state?.find((r) => String(r.id) === String(id));
  const ids = new Set<string | number>([id, ...(me ? sameGame(state, me).map((r) => r.id) : [])]);
  let err: string | null = null;
  for (const x of ids) {
    const a = await iptvPost<{ ok?: boolean }>(`v1/iptv/reminders/${x}/delete`);
    if (a.status !== 200 && a.status !== 204 && a.status !== 404) err = a.data?.error ?? `Server said ${a.status}`;
  }
  await refreshReminders(true);
  return err;
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
export const addReminderFor = (o: { key: string; title: string; start: number; stop: number; channelId: string; channelName: string; fid?: string | null; matchId?: string | null }) =>
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
  }, { fid: o.fid, matchId: o.matchId });
