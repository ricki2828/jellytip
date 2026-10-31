// Match info (plan: ~/ai/plans/jellytip-live-sport.md "Match info"). A programme / event may carry `matchInfo` ({id, league, home,
// away, startsAt, venue?, status, source, inferred, score?}); GET /v1/sport/match/{id} adds line-ups, odds, form, head to head,
// broadcasts and the caller's channels. Everything is optional. Pure; tested in web/test/iptv.test.ts.
import { parseStartsAt } from "./preGame.ts";

export interface Side {
  name: string;
  short: string | null;
  logo: string | null;
  record: string | null;
}

export interface Fixture {
  id: string;
  league: string | null;
  /** The league's key ("nfl", "nba"…): what code decides on; `league` is for display. */
  leagueKey: string | null;
  home: Side;
  away: Side;
  /** Epoch seconds. */
  startsAt: number | null;
  venue: { name: string | null; city: string | null } | null;
  status: "pre" | "in" | "post" | null;
  source: string | null;
  /** The matchup was worked out by Jellytip (the listing did not name it). */
  inferred: boolean;
  score: { home: number; away: number; clock: string | null; period: string | null } | null;
}

export interface Player {
  name: string;
  pos: string | null;
  num: string | null;
  starter: boolean;
}

export interface Odds {
  provider: string | null;
  home: string | null;
  away: string | null;
  draw: string | null;
  overUnder: string | null;
  updatedAt: number | null;
}

export interface MatchChannel {
  id: string;
  name: string;
  quality: string | null;
}

export interface MatchDetail {
  fixture: Fixture;
  lineups: { announced: boolean; home: Player[]; away: Player[] } | null;
  odds: Odds | null;
  form: { home: string[]; away: string[] } | null;
  h2h: string[];
  injuries: string[];
  broadcasts: string[];
  channels: MatchChannel[];
  pending: boolean;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : v != null && typeof v === "number" ? String(v) : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

function side(raw: unknown): Side | null {
  const o = obj(raw);
  const name = str(o?.name) ?? str(o?.short);
  return o && name ? { name, short: str(o.short), logo: str(o.logo), record: str(o.record) } : null;
}

/** `matchInfo` on a programme / item: a fixture, or null when it has no id or either side. (`match` is the favourite marker and is not read here.) */
export function parseFixture(raw: unknown): Fixture | null {
  const o = obj(raw);
  const id = str(o?.id);
  const home = side(o?.home);
  const away = side(o?.away);
  if (!o || !id || !home || !away) return null;
  const sc = obj(o.score);
  const status = o.status === "pre" || o.status === "in" || o.status === "post" ? o.status : null;
  const v = obj(o.venue);
  return {
    id,
    league: str(o.league),
    leagueKey: str(o.leagueKey)?.toLowerCase() ?? null,
    home,
    away,
    startsAt: parseStartsAt(o.startsAt),
    venue: v ? { name: str(v.name), city: str(v.city) } : null,
    status,
    source: str(o.source),
    inferred: o.inferred === true,
    score: sc && num(sc.home) != null && num(sc.away) != null ? { home: num(sc.home)!, away: num(sc.away)!, clock: str(sc.clock), period: str(sc.period) } : null,
  };
}

const players = (v: unknown): Player[] =>
  arr(v).flatMap((x) => {
    const p = obj(x);
    const name = str(p?.name);
    return p && name ? [{ name, pos: str(p.pos), num: str(p.num), starter: p.starter !== false }] : [];
  });

const money = (v: unknown): string | null => {
  const o = obj(v);
  if (!o) return str(v);
  return [str(o.moneyline), str(o.spread)].filter(Boolean).join(" · ") || null;
};

export function parseMatchDetail(raw: unknown): MatchDetail | null {
  const r = obj(raw);
  const fixture = parseFixture(r?.matchInfo ?? r);
  if (!r || !fixture) return null;
  const l = obj(r.lineups);
  const od = obj(r.odds);
  const fm = obj(r.form);
  const lineups = l ? { announced: l.announced === true, home: players(l.home), away: players(l.away) } : null;
  return {
    fixture,
    lineups: lineups && (lineups.announced || lineups.home.length || lineups.away.length) ? lineups : lineups ? { ...lineups, announced: false } : null,
    odds: od
      ? {
          provider: str(od.provider),
          home: money(od.home),
          away: money(od.away),
          draw: money(od.draw),
          overUnder: str(od.overUnder),
          updatedAt: parseStartsAt(od.updatedAt),
        }
      : null,
    form: fm ? { home: arr(fm.home).filter((x): x is string => typeof x === "string"), away: arr(fm.away).filter((x): x is string => typeof x === "string") } : null,
    h2h: arr(r.h2h).map((x) => (typeof x === "string" ? x : str(obj(x)?.summary) ?? [str(obj(x)?.date), str(obj(x)?.result)].filter(Boolean).join(" · "))).filter(Boolean) as string[],
    injuries: arr(r.injuries).map((x) => (typeof x === "string" ? x : [str(obj(x)?.name), str(obj(x)?.status)].filter(Boolean).join(" · "))).filter(Boolean),
    broadcasts: arr(r.broadcasts).filter((x): x is string => typeof x === "string"),
    channels: arr(r.channels).flatMap((x) => {
      const c = obj(x);
      const id = str(c?.id);
      return c && id ? [{ id, name: str(c.name) ?? "Channel", quality: str(obj(c.quality)?.bucket) }] : [];
    }),
    pending: r.pending === true,
  };
}

/** "Home v Away". */
export const matchupTitle = (f: Pick<Fixture, "home" | "away">) => `${f.home.name} v ${f.away.name}`;

/** The tile title: the matchup when Jellytip identified it, else the listing's own title. */
export const displayTitle = (title: string, f: Fixture | null | undefined) => (f?.inferred ? matchupTitle(f) : title);

/** "LIVE" / "FT" / "Today 19:30"-style time for the header. */
export function statusLabel(f: Fixture, now: number): string {
  if (f.status === "in") return f.score?.clock ? `LIVE · ${f.score.clock}` : "LIVE";
  if (f.status === "post") return "FT";
  if (!f.startsAt) return "";
  const d = new Date(f.startsAt * 1000);
  const same = new Date(now * 1000).toDateString() === d.toDateString();
  const t = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  return same ? `Today ${t}` : `${d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })} ${t}`;
}

export const venueLine = (f: Pick<Fixture, "venue">) => [f.venue?.name, f.venue?.city].filter(Boolean).join(", ");

/** What a match route's answer means (plan "match routes' status codes"): 404 = route missing, 403 = no IPTV for this person,
 * 200 {unknown: true} = this match only; anything else is an ordinary answer (null). */
export function matchOutcome(status: number, data: unknown): "route-missing" | "forbidden" | "unknown" | null {
  if (status === 404) return "route-missing";
  if (status === 403) return "forbidden";
  if (status === 200 && data && typeof data === "object" && (data as { unknown?: unknown }).unknown === true) return "unknown";
  return null;
}
