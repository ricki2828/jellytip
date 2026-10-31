// "On now" (GET v1/iptv/live/onnow): channels with something airing now, in the server's order (favourite region
// first, then group order). Pure; tested in web/test/onNow.test.ts. Shape: {now, channels:[Channel + {sport, now, next}]};
// the server's `sport` flag decides, keywords only when a server leaves it out.

import { parseFixture } from "./matchInfo.ts";
import { hasServerRanking, parseSport, type SportInfo } from "./sport.ts";

export interface OnNow {
  channelId: string;
  channel: string;
  logo: string | null;
  title: string;
  start: number;
  stop: number;
  sport: boolean;
  /** The programme's description and what is on next (for the phone player's panel). */
  desc: string | null;
  next: { title: string; start: number }[];
  /** Live/replay, stream quality and other channels with the same event (all optional on older servers). */
  info: SportInfo;
}

const SPORT_RE = /\b(sports?|football|soccer|rugby|cricket|nrl|afl|nfl|nba|nhl|mlb|ncaa[fb]?|f1|formula|golf|tennis|boxing|ufc|racing|motogp|super ?rugby|premier league|kayo|espn|bein|tnt sports|fox sports|sky sport)\b/i;

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);

const nextList = (c: Record<string, unknown>): { title: string; start: number }[] => {
  const raw = Array.isArray(c.next) ? c.next : c.next ? [c.next] : [];
  return raw.flatMap((x) => {
    const o = obj(x);
    return o && typeof o.title === "string" && o.title && typeof o.start === "number" ? [{ title: o.title, start: o.start }] : [];
  });
};

export function parseOnNow(raw: unknown, now: number): OnNow[] {
  const r = obj(raw);
  const list = Array.isArray(raw) ? raw : Array.isArray(r?.channels) ? (r!.channels as unknown[]) : Array.isArray(r?.items) ? (r!.items as unknown[]) : [];
  const out: OnNow[] = [];
  const seen = new Set<string>();
  const events = new Set<string>();
  for (const x of list) {
    const c = obj(x);
    if (!c) continue;
    const ch = obj(c.channel) ?? c;
    let p = obj(c.now) ?? obj(c.programme) ?? obj(c.program);
    // A channel with no guide (RedZone) carries its matchInfo on the tile itself: that is its "programme" (title, and on now)
    if (!p) {
      const mi = parseFixture(c.matchInfo ?? obj(c.now_next)?.matchInfo);
      if (mi && mi.kind !== "match" && mi.title) p = { title: mi.title, start: Math.min(mi.startsAt ?? now - 60, now - 60), stop: now + 3 * 3600, kind: "live", sport: true, matchInfo: c.matchInfo ?? obj(c.now_next)?.matchInfo };
    }
    const id = typeof ch.id === "string" ? ch.id : null;
    if (!id || !/^[A-Za-z0-9_-]{1,40}:[A-Za-z0-9_.-]{1,64}$/.test(id) || seen.has(id) || !p) continue;
    const start = Number(p.start);
    const stop = Number(p.stop);
    const title = typeof p.title === "string" ? p.title : "";
    if (!title || !(start <= now && now < stop)) continue;
    const info = parseSport(c, p);
    if (!info.quality) info.quality = parseSport(ch).quality;
    // One card per event: the server lists the best channel first and the rest as alternates
    if (info.eventKey && events.has(info.eventKey)) continue;
    if (info.eventKey) events.add(info.eventKey);
    seen.add(id);
    const name = typeof ch.name === "string" ? ch.name : "";
    const hint = [c.genre, c.category, ch.groupName, ch.group, name, title].filter((v) => typeof v === "string").join(" ");
    out.push({
      channelId: id,
      channel: name,
      logo: typeof ch.logo === "string" ? ch.logo : null,
      title,
      start,
      stop,
      info,
      desc: typeof p.desc === "string" && p.desc ? p.desc : typeof p.description === "string" && p.description ? p.description : null,
      next: nextList(c),
      sport: info.sport || c.sport === true || ch.sport === true || (c.sport !== false && ch.sport !== false && SPORT_RE.test(hint)),
    });
  }
  // A server that ranks (live-sport smarts) has the final say; an older one gets sport first, then its own order
  if (out.some((o) => hasServerRanking(o.info))) return out;
  return [...out.filter((o) => o.sport), ...out.filter((o) => !o.sport)];
}

/** 0..1 through the programme. */
export const progressOf = (o: Pick<OnNow, "start" | "stop">, now: number) => Math.max(0, Math.min(1, (now - o.start) / Math.max(1, o.stop - o.start)));
