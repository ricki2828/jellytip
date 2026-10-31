// Match info (plan: ~/ai/plans/jellytip-live-sport.md "Match info"). A programme / event may carry `matchInfo` ({id, league, home,
// away, startsAt, venue?, status, source, inferred, score?}); GET /v1/sport/match/{id} adds line-ups, odds, form, head to head,
// broadcasts and the caller's channels. Everything is optional. Pure; tested in web/test/iptv.test.ts.
import { parseStartsAt } from "./preGame.ts";

export interface Side {
  name: string;
  short: string | null;
  logo: string | null;
  record: string | null;
  /** Team colours (hex) when the server has them: the match modal's split hero and tints. */
  color: string | null;
  altColor: string | null;
  /** Tennis: the player's country (a flag instead of a crest). */
  country: string | null;
}

export interface Fixture {
  id: string;
  /** "event": F1 and golf have no home / away; `title` is the hero and `home`/`away` are placeholders (home carries the title). */
  kind: "match" | "event" | "multi";
  title: string | null;
  participants: string[];
  /** Events: the session ("Race", "Qualifying") and round ("Round 3"), and the F1 country flag. */
  session: string | null;
  round: string | null;
  flag: string | null;
  league: string | null;
  /** The league's key ("nfl", "nba"…): what code decides on; `league` is for display. */
  leagueKey: string | null;
  home: Side;
  away: Side;
  /** Epoch seconds. */
  startsAt: number | null;
  venue: { name: string | null; city: string | null; image: string | null } | null;
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

/** A hex colour ("#aabbcc", "aabbcc" or "#abc") as "#aabbcc"; null for anything else. */
export function hex(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const m = /^#?([0-9a-f]{6}|[0-9a-f]{3})$/i.exec(v.trim());
  if (!m) return null;
  const h = m[1].length === 3 ? m[1].split("").map((c) => c + c).join("") : m[1];
  return `#${h.toLowerCase()}`;
}

function side(raw: unknown): Side | null {
  const o = obj(raw);
  const name = str(o?.name) ?? str(o?.short);
  return o && name ? { name, short: str(o.short), logo: str(o.logo), record: str(o.record), color: hex(o.color), altColor: hex(o.altColor), country: str(o.country) } : null;
}

/** `matchInfo` on a programme / item: a fixture, or null when it has no id or either side. (`match` is the favourite marker and is not read here.) */
export function parseFixture(raw: unknown): Fixture | null {
  const o = obj(raw);
  const id = str(o?.id);
  if (!o || !id) return null;
  const event = o.kind === "event" || o.kind === "multi"; // no home / away: the title is the hero (a multi is a whip-around like NFL RedZone)
  const title = str(o.title);
  let home = side(o.home);
  let away = side(o.away);
  if (event) {
    // F1 and golf: no home / away, the title is the hero
    if (!title) return null;
    home = home ?? { name: title, short: null, logo: null, record: null, color: null, altColor: null, country: null };
    away = away ?? { name: "", short: null, logo: null, record: null, color: null, altColor: null, country: null };
  } else if (!home || !away) return null;
  const sc = obj(o.score);
  const status = o.status === "pre" || o.status === "in" || o.status === "post" ? o.status : null;
  const v = obj(o.venue);
  return {
    id,
    kind: o.kind === "multi" ? "multi" : event ? "event" : "match",
    title,
    session: str(o.session),
    round: str(o.round),
    flag: str(o.flag),
    participants: arr(o.participants).flatMap((p) => (typeof p === "string" ? [p] : str(obj(p)?.name) ? [str(obj(p)?.name)!] : [])),
    league: str(o.league),
    leagueKey: str(o.leagueKey)?.toLowerCase() ?? null,
    home: home!,
    away: away!,
    startsAt: parseStartsAt(o.startsAt),
    venue: v ? { name: str(v.name), city: str(v.city), image: str(v.image) } : null,
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

/** "Home v Away"; an event (F1, golf) is its title. */
export const matchupTitle = (f: Pick<Fixture, "home" | "away"> & { kind?: "match" | "event" | "multi"; title?: string | null }) => (f.kind !== "match" && f.kind && f.title ? f.title : `${f.home.name} v ${f.away.name}`);

/** The tile title: the matchup when Jellytip identified it, else the listing's own title. */
export const displayTitle = (title: string, f: Fixture | null | undefined) => (f?.inferred || (f && f.kind !== "match") ? matchupTitle(f) : title);

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

// ---------- match modal helpers ----------

const NEUTRAL = ["#3b3f46", "#2a2d33"];

/** The two team colours for the split hero; neutral greys where a side has none. */
export const heroColors = (f: Pick<Fixture, "home" | "away">): [string, string] => [f.home.color ?? NEUTRAL[0], f.away.color ?? NEUTRAL[1]];

/** "Starts in 2h 14m" / "Starts in 35 min" / "Starting now". */
export function startsIn(startsAt: number, now: number): string {
  const m = Math.ceil((startsAt - now) / 60);
  if (m <= 0) return "Starting now";
  return m >= 60 ? `Starts in ${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `Starts in ${m} min`;
}

/** American moneyline ("-150", "+130") → implied probability 0..1; null when it isn't one. */
export function impliedProb(line: string | null | undefined): number | null {
  const m = /^\s*([+-]?\d{2,5})/.exec(line ?? "");
  if (!m) return null;
  const n = Number(m[1]);
  if (!n) return null;
  return n > 0 ? 100 / (n + 100) : -n / (-n + 100);
}

/** Home / away shares (0..1, summing to 1) for the odds bar, from the two moneylines; null without both. */
export function oddsSplit(home: string | null | undefined, away: string | null | undefined): { home: number; away: number } | null {
  const h = impliedProb(home);
  const a = impliedProb(away);
  if (h == null || a == null) return null;
  return { home: h / (h + a), away: a / (h + a) };
}

/** W / L / D chip class for a form letter. */
export const formTone = (c: string): "w" | "l" | "d" => (/^w/i.test(c) ? "w" : /^l/i.test(c) ? "l" : "d");

/** "#aabbcc" + alpha → "rgba(170, 187, 204, 0.2)". */
export function tint(color: string, alpha: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(color);
  if (!m) return `rgba(255, 255, 255, ${alpha})`;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** A team colour that reads on the dark panel: a dark colour is lightened towards white (luminance under 0.35 → blended up). */
export function readable(color: string): string {
  const m = /^#([0-9a-f]{6})$/i.exec(color);
  if (!m) return color;
  const n = parseInt(m[1], 16);
  const c = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  const lin = c.map((v) => ((v / 255) ** 2.2));
  const lum = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
  if (lum >= 0.1) return color;
  const mix = c.map((v) => Math.round(v + (255 - v) * 0.55));
  return `#${mix.map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

/** A two-letter country code as a flag emoji ("NZ" → 🇳🇿); anything else is returned as the upper-case code. */
export function flagOf(code: string | null | undefined): string | null {
  if (!code) return null;
  const c = code.trim().toUpperCase();
  return /^[A-Z]{2}$/.test(c) ? String.fromCodePoint(...[...c].map((ch) => 0x1f1e6 + ch.charCodeAt(0) - 65)) : c || null;
}
