// Fantasy live scoring for NFL (plan: ~/ai/plans/jellytip-live-sport.md "Fantasy live scoring for NFL"). Sleeper first.
// GET /v1/fantasy/live → {week, leagues:[{id, name, me:{team, points, projected?}, opp:{team, points}, starters:[{side, name, pos, nflTeam,
// points, gameId?, gameState}]}], events:[{at, league, name, delta, side}], updatedAt}. Pure; tested in web/test/iptv.test.ts.

export interface Starter {
  side: "me" | "opp";
  name: string;
  pos: string | null;
  nflTeam: string | null;
  points: number;
  gameId: string | null;
  gameState: "pre" | "in" | "post" | null;
}

export interface FantasyLeague {
  id: string;
  name: string;
  me: { team: string; points: number; projected: number | null };
  opp: { team: string; points: number };
  starters: Starter[];
}

export interface FantasyEvent {
  at: number;
  league: string;
  name: string;
  delta: number;
  side: "me" | "opp";
}

export interface FantasyLive {
  week: number | null;
  leagues: FantasyLeague[];
  events: FantasyEvent[];
  updatedAt: number | null;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : typeof v === "number" ? String(v) : null);
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export function parseStarters(raw: unknown): Starter[] {
  return arr(raw).flatMap((x) => {
    const o = obj(x);
    const name = str(o?.name);
    if (!o || !name) return [];
    return [
      {
        side: o.side === "opp" ? "opp" : "me",
        name,
        pos: str(o.pos),
        nflTeam: str(o.nflTeam),
        points: num(o.points) ?? 0,
        gameId: str(o.gameId),
        gameState: o.gameState === "pre" || o.gameState === "in" || o.gameState === "post" ? o.gameState : null,
      } as Starter,
    ];
  });
}

export function parseFantasy(raw: unknown): FantasyLive | null {
  const r = obj(raw);
  if (!r || !Array.isArray(r.leagues)) return null;
  const leagues: FantasyLeague[] = [];
  for (const x of r.leagues) {
    const o = obj(x);
    const id = str(o?.id);
    const me = obj(o?.me);
    const opp = obj(o?.opp);
    if (!o || !id || !me || !opp) continue;
    leagues.push({
      id,
      name: str(o.name) ?? id,
      me: { team: str(me.team) ?? "You", points: num(me.points) ?? 0, projected: num(me.projected) },
      opp: { team: str(opp.team) ?? "Opponent", points: num(opp.points) ?? 0 },
      starters: parseStarters(o.starters),
    });
  }
  const events = arr(r.events).flatMap((x) => {
    const o = obj(x);
    const at = num(o?.at);
    const name = str(o?.name);
    const delta = num(o?.delta);
    return o && at != null && name && delta != null ? [{ at, league: str(o.league) ?? "", name, delta, side: o.side === "opp" ? "opp" : "me" } as FantasyEvent] : [];
  });
  return { week: num(r.week), leagues, events, updatedAt: num(r.updatedAt) };
}

/** "CCP Dynasty" → "CCP"; "Fantasy" → "Fantasy". */
export const shortLeague = (name: string) => name.split(/\s+/)[0] || name;

const pts = (n: number) => (Math.round(n * 10) / 10).toFixed(1);

/** "🏈 CCP 87.4 – 79.2" for each league (at most two), mine first. */
export function chipText(live: FantasyLive): string | null {
  if (!live.leagues.length) return null;
  return live.leagues
    .slice(0, 2)
    .map((l) => `🏈 ${shortLeague(l.name)} ${pts(l.me.points)} – ${pts(l.opp.points)}`)
    .join("   ");
}

/** "🏈 +6.4 Gibbs (CCP Dynasty) · you lead 93.8 – 79.2". */
export function eventText(e: FantasyEvent, live: FantasyLive): string {
  const l = live.leagues.find((x) => x.name === e.league || x.id === e.league);
  const who = e.side === "opp" ? "opponent's " : "";
  const head = `🏈 ${e.delta >= 0 ? "+" : ""}${pts(e.delta)} ${who}${e.name}${e.league ? ` (${e.league})` : ""}`;
  if (!l) return head;
  const d = l.me.points - l.opp.points;
  const state = Math.abs(d) < 0.05 ? "tied" : d > 0 ? "you lead" : "you trail";
  return `${head} · ${state} ${pts(Math.max(l.me.points, l.opp.points))} – ${pts(Math.min(l.me.points, l.opp.points))}`;
}

/** The ESPN game id inside a fixture id ("nfl:401772" → "401772"). */
export const gameIdOf = (fixtureId: string) => fixtureId.match(/(\d{5,})\s*$/)?.[1] ?? fixtureId;

/** My starters playing in this game across leagues (the "3 of yours" pill). */
export function mineInGame(live: FantasyLive | null, fixtureId: string | null | undefined): number {
  if (!live || !fixtureId) return 0;
  const g = gameIdOf(fixtureId);
  return live.leagues.reduce((n, l) => n + l.starters.filter((s) => s.side === "me" && s.gameId != null && (s.gameId === g || s.gameId === fixtureId)).length, 0);
}

export const isNfl = (league: string | null | undefined, fixtureId?: string | null) => /\bnfl\b/i.test(league ?? "") || /^nfl\b/i.test(fixtureId ?? "");
