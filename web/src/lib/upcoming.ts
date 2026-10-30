// Upcoming sport (GET v1/iptv/sport/upcoming; contract: ~/ai/plans/jellytip-live-sport.md "Upcoming sport"). Events in
// the server's order (favourite teams, favourite leagues, the rest; by start within a tier). Pure; tested in
// web/test/upcoming.test.ts.
import { parseArt, parseQuality, type Kind, type Quality, type SportArt } from "./sport.ts";

export interface UpChannel {
  id: string;
  name: string;
  num: number | null;
  logo: string | null;
  quality: Quality | null;
}

export interface UpEvent {
  key: string;
  title: string;
  league: string | null;
  sportName?: string | null;
  /** The person's team or league that matched (the ★). */
  favourite: boolean;
  start: number;
  stop: number;
  kind: Kind | null;
  /** Best channel first, then the alternates. */
  channels: UpChannel[];
  art: SportArt | null;
  recordable: boolean;
  recordingId: string | number | null;
  /** The person's reminder for this event, when they have one. */
  reminderId?: string | number | null;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);

function channel(raw: unknown): UpChannel | null {
  const c = obj(raw);
  const id = str(c?.id);
  if (!c || !id) return null;
  return { id, name: str(c.name) ?? "Channel", num: num(c.num), logo: str(c.logo), quality: parseQuality(c.quality) };
}

/** Answer → events; unusable items (no title, time or channel) are dropped. */
export function parseUpcoming(raw: unknown): UpEvent[] {
  const r = obj(raw);
  const list = Array.isArray(raw) ? raw : Array.isArray(r?.events) ? r!.events : Array.isArray(r?.items) ? r!.items : [];
  const out: UpEvent[] = [];
  const seen = new Set<string>();
  for (const x of list as unknown[]) {
    const e = obj(x);
    const title = str(e?.title);
    const start = num(e?.start);
    const stop = num(e?.stop);
    const best = channel(e?.channel);
    if (!e || !title || start == null || stop == null || !best) continue;
    const key = str(e.key) ?? `${title}|${start}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const m = obj(e.match);
    const alts = (Array.isArray(e.alternateChannels) ? e.alternateChannels : []).map(channel).filter((c): c is UpChannel => !!c && c.id !== best.id);
    const kinds = ["live", "replay", "highlights", "other"];
    out.push({
      key,
      title,
      league: str(e.league) ?? str(m?.league),
      sportName: str(e.sportName) ?? str(e.sport),
      favourite: !!(str(m?.team) || str(m?.league)),
      start,
      stop,
      kind: kinds.includes(e.kind as string) ? (e.kind as Kind) : null,
      channels: [best, ...alts],
      art: parseArt(e.art),
      recordable: e.recordable !== false,
      recordingId: typeof e.recordingId === "string" || typeof e.recordingId === "number" ? e.recordingId : null,
      reminderId: typeof e.reminderId === "string" || typeof e.reminderId === "number" ? e.reminderId : null,
    });
  }
  return out;
}

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();
const hm = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

/** "Today"/"Tomorrow"/"Sat 1 Nov" for a day heading. */
export function dayLabel(d: Date, now: Date = new Date()): string {
  if (sameDay(d, now)) return "Today";
  if (sameDay(d, new Date(now.getTime() + 86_400_000))) return "Tomorrow";
  return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
}

/** Tile chip in local time: "Live now", "Today 19:30", "Sat 02:00". */
export function timeChip(e: Pick<UpEvent, "start" | "stop">, now: Date = new Date()): string {
  const t = now.getTime() / 1000;
  if (e.start <= t && t < e.stop) return "Live now";
  const s = new Date(e.start * 1000);
  const day = sameDay(s, now) ? "Today" : sameDay(s, new Date(now.getTime() + 86_400_000)) ? "Tomorrow" : s.toLocaleDateString("en-GB", { weekday: "short" });
  return `${day} ${hm(s)}`;
}

/** Days in date order, events inside a day in the server's order (favourites first). Finished events drop out. */
export function groupByDay(events: UpEvent[], now: Date = new Date()): { day: string; label: string; events: UpEvent[] }[] {
  const t = now.getTime() / 1000;
  const days = new Map<string, { at: number; events: UpEvent[] }>();
  for (const e of events) {
    if (e.stop <= t) continue;
    const d = new Date(Math.max(e.start, t) * 1000);
    const k = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    const g = days.get(k) ?? { at: new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(), events: [] };
    g.events.push(e);
    days.set(k, g);
  }
  return [...days.entries()].sort((a, b) => a[1].at - b[1].at).map(([day, g]) => ({ day, label: dayLabel(new Date(g.at), now), events: g.events }));
}

export const isLive = (e: Pick<UpEvent, "start" | "stop">, now = Date.now() / 1000) => e.start <= now && now < e.stop;
