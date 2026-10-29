// "On now" (GET v1/iptv/live/onnow): channels with something airing now, in the server's order (favourite region
// first, then group order). Pure; tested in web/test/onNow.test.ts. Tolerant of the exact shape until it's pinned:
// `channels[]` or `items[]`, each a channel with `now` (or `programme`) = {start, stop, title}.

export interface OnNow {
  channelId: string;
  channel: string;
  logo: string | null;
  title: string;
  start: number;
  stop: number;
  sport: boolean;
}

const SPORT_RE = /\b(sports?|football|soccer|rugby|cricket|nrl|afl|nfl|nba|nhl|mlb|ncaa[fb]?|f1|formula|golf|tennis|boxing|ufc|racing|motogp|super ?rugby|premier league|kayo|espn|bein|tnt sports|fox sports|sky sport)\b/i;

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);

export function parseOnNow(raw: unknown, now: number): OnNow[] {
  const r = obj(raw);
  const list = Array.isArray(raw) ? raw : Array.isArray(r?.channels) ? (r!.channels as unknown[]) : Array.isArray(r?.items) ? (r!.items as unknown[]) : [];
  const out: OnNow[] = [];
  const seen = new Set<string>();
  for (const x of list) {
    const c = obj(x);
    if (!c) continue;
    const ch = obj(c.channel) ?? c;
    const p = obj(c.now) ?? obj(c.programme) ?? obj(c.program);
    const id = typeof ch.id === "string" ? ch.id : null;
    if (!id || !/^[A-Za-z0-9_-]{1,40}:[A-Za-z0-9_.-]{1,64}$/.test(id) || seen.has(id) || !p) continue;
    const start = Number(p.start);
    const stop = Number(p.stop);
    const title = typeof p.title === "string" ? p.title : "";
    if (!title || !(start <= now && now < stop)) continue;
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
      sport: c.sport === true || ch.sport === true || (c.sport !== false && ch.sport !== false && SPORT_RE.test(hint)),
    });
  }
  // Live sport first, otherwise the server's order
  return [...out.filter((o) => o.sport), ...out.filter((o) => !o.sport)];
}

/** 0..1 through the programme. */
export const progressOf = (o: Pick<OnNow, "start" | "stop">, now: number) => Math.max(0, Math.min(1, (now - o.start) / Math.max(1, o.stop - o.start)));
