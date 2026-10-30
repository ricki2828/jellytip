// "Remind me" for upcoming sport (GET v1/iptv/reminders; contract: ~/ai/plans/jellytip-live-sport.md "Remind me").
// Pure; tested in web/test/iptv.test.ts. The state is worked out from the clock here too, so chips stay right between polls.
import { parseUpcoming, type UpEvent } from "./upcoming.ts";

export type RemState = "upcoming" | "soon" | "live";
export const SOON_S = 15 * 60;

export interface Reminder {
  id: number | string;
  event: UpEvent;
}

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
    const ev = parseUpcoming({ items: [o] })[0];
    if (ev) out.push({ id, event: { ...ev, reminderId: id } });
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
