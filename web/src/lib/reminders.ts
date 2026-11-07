// "Remind me" for upcoming sport (GET v1/iptv/reminders; contract: ~/ai/plans/jellytip-live-sport.md "Remind me").
// Pure; tested in web/test/iptv.test.ts. The state is worked out from the clock here too, so chips stay right between polls.
import { parseUpcoming, type UpEvent } from "./upcoming.ts";

export type RemState = "upcoming" | "soon" | "live";
export const SOON_S = 15 * 60;

export interface Reminder {
  id: number | string;
  event: UpEvent;
  /** Fixture id when this is a fixture reminder. */
  fid: string | null;
  /** The fixture's match-info id (what the match sheet, player and Game Centre cards know), when the server says. */
  matchId: string | null;
}

/** The person's reminder for an event, by whichever id the caller has: the event key, the fixture id, or the match-info id. */
export function findReminders(items: Reminder[] | null | undefined, o: { key?: string | null; fid?: string | null; matchId?: string | null }): Reminder[] {
  return (items ?? []).filter((r) => (!!o.key && r.event.key === o.key) || (!!o.fid && r.fid === o.fid) || (!!o.matchId && r.matchId === o.matchId));
}

/** Every row that is the same game as `r` (same key, fixture id or match id): switching a reminder off must remove all of them. */
export const sameGame = (items: Reminder[] | null | undefined, r: Reminder): Reminder[] => findReminders(items, { key: r.event.key, fid: r.fid, matchId: r.matchId });

export function findReminder(items: Reminder[] | null | undefined, o: { key?: string | null; fid?: string | null; matchId?: string | null }): Reminder | null {
  return items?.find((r) => (!!o.key && r.event.key === o.key) || (!!o.fid && r.fid === o.fid) || (!!o.matchId && r.matchId === o.matchId)) ?? null;
}

/** A reminder set before any stream is linked carries a channel with an empty id ("No stream yet"). */
export const NO_STREAM = "No stream yet";
export const hasStream = (e: Pick<UpEvent, "channels">): boolean => !!e.channels[0]?.id;

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);

/** Answer → reminders, soonest first. Items the event parser can't use (no title, time or channel) are dropped. */
export function parseReminders(raw: unknown): Reminder[] {
  const r = obj(raw);
  const list = Array.isArray(r?.items) ? (r!.items as unknown[]) : [];
  const out: Reminder[] = [];
  for (const x of list) {
    const o = obj(x);
    const id = o?.id;
    if (!o || (typeof id !== "string" && typeof id !== "number")) continue;
    const noChan = !obj(o.channel) || typeof obj(o.channel)!.id !== "string" || !obj(o.channel)!.id;
    // Fixture reminders may have no channel yet: keep them, with an empty-id placeholder channel
    const ev = parseUpcoming({ items: [noChan && typeof o.fid === "string" && o.fid ? { ...o, channel: { id: "_", name: NO_STREAM } } : o] })[0];
    if (!ev) continue;
    const event = noChan ? { ...ev, channels: [{ ...ev.channels[0], id: "" }] } : ev;
    out.push({ id, fid: typeof o.fid === "string" && o.fid ? o.fid : null, matchId: typeof o.matchId === "string" && o.matchId ? o.matchId : null, event: { ...event, reminderId: id } });
  }
  return out.sort((a, b) => a.event.start - b.event.start);
}

export function stateOf(e: Pick<UpEvent, "start" | "stop">, now: number): RemState | "ended" {
  if (now >= e.stop) return "ended";
  if (now >= e.start) return "live";
  return e.start - now <= SOON_S ? "soon" : "upcoming";
}

/** "in 2h 10m" / "in 35m" / "Starting soon" / "LIVE". */
export function countdown(e: Pick<UpEvent, "start" | "stop">, now: number): { text: string; tone: "time" | "soon" | "rec" } {
  const s = stateOf(e, now);
  if (s === "live") return { text: "LIVE", tone: "rec" };
  if (s === "soon") return { text: "Starting soon", tone: "soon" };
  const m = Math.max(1, Math.round((e.start - now) / 60));
  if (m >= 48 * 60) return { text: `in ${Math.round(m / 1440)} days`, tone: "time" };
  return { text: m >= 60 ? `in ${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `in ${m}m`, tone: "time" };
}

export interface Nudge {
  id: number | string;
  at: "soon" | "live";
  event: UpEvent;
}

/** Reminders that reached soon or live and haven't been announced yet (`seen` holds "id:state" and is updated). */
export function dueNudges(items: Reminder[], seen: Set<string>, now: number): Nudge[] {
  const out: Nudge[] = [];
  for (const r of items) {
    const s = stateOf(r.event, now);
    if (s !== "soon" && s !== "live") continue;
    // Live also counts as having announced "soon": nobody wants two toasts in one go
    const k = `${r.id}:${s}`;
    if (seen.has(k)) continue;
    seen.add(k);
    if (s === "live") seen.add(`${r.id}:soon`);
    out.push({ id: r.id, at: s, event: r.event });
  }
  return out;
}

export function nudgeText(n: Nudge, now: number): string {
  if (n.at === "live") return `${n.event.title} is on now`;
  const m = Math.max(1, Math.round((n.event.start - now) / 60));
  return `${n.event.title} starts in ${m} min`;
}
