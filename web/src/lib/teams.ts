// Favourite teams from the catalogue (plan: ~/ai/plans/jellytip-live-sport.md "Favourites: every team", pinned 1-Nov).
// GET /v1/sport/teams?q= → {teams: [{id, name, short, league, leagueKey, logo, color, sport}]} (at most 20, best first). A saved
// favourite keeps its name in sport.teams and adds {teamId, leagueKey, name} to sport.teamRefs, in the same PATCH. Pure; tested in
// web/test/iptv.test.ts.
import { hex } from "./matchInfo.ts";

export interface TeamHit {
  id: string;
  name: string;
  short: string | null;
  league: string | null;
  leagueKey: string | null;
  logo: string | null;
  color: string | null;
  sport: string | null;
}

export interface TeamRef {
  teamId: string;
  leagueKey: string;
  name: string;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v ? v : typeof v === "number" ? String(v) : null);

export function parseTeams(raw: unknown): TeamHit[] {
  const o0 = obj(raw);
  // The server as built answers {items: [...]}; the plan also said {teams: [...]}
  const list = Array.isArray(raw) ? raw : Array.isArray(o0?.items) ? (o0!.items as unknown[]) : Array.isArray(o0?.teams) ? (o0!.teams as unknown[]) : [];
  const out: TeamHit[] = [];
  const seen = new Set<string>();
  for (const x of list) {
    const o = obj(x);
    const id = str(o?.id);
    const name = str(o?.name);
    const key = `${str(o?.leagueKey) ?? ""}:${id}`;
    if (!o || !id || !name || seen.has(key)) continue;
    seen.add(key);
    out.push({ id, name, short: str(o.short), league: str(o.league), leagueKey: str(o.leagueKey), logo: str(o.logo), color: hex(o.color), sport: str(o.sport) });
  }
  return out.slice(0, 20);
}

export function parseTeamRefs(raw: unknown): TeamRef[] {
  const out: TeamRef[] = [];
  for (const x of Array.isArray(raw) ? raw : []) {
    const o = obj(x);
    const teamId = str(o?.teamId);
    const leagueKey = str(o?.leagueKey);
    const name = str(o?.name);
    if (o && teamId && leagueKey && name) out.push({ teamId, leagueKey, name });
  }
  return out;
}

/** "UCLA Bruins · College Football". */
export const teamLine = (t: Pick<TeamHit, "name" | "league">) => [t.name, t.league].filter(Boolean).join(" · ");

const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();

/** Following this team: by id and league when a ref exists, else by name. */
export const isFollowing = (t: TeamHit, names: string[], refs: TeamRef[]): boolean =>
  refs.some((r) => r.teamId === t.id && (!t.leagueKey || r.leagueKey === t.leagueKey)) || (!t.leagueKey && names.some((n) => same(n, t.name)));

/** The two lists after following a team: its name joins sport.teams and its {teamId, leagueKey, name} joins sport.teamRefs. */
export function follow(t: TeamHit, names: string[], refs: TeamRef[]): { teams: string[]; teamRefs: TeamRef[] } {
  const teams = names.some((n) => same(n, t.name)) ? names : [...names, t.name];
  const teamRefs = t.leagueKey ? [...refs.filter((r) => !(r.teamId === t.id && r.leagueKey === t.leagueKey)), { teamId: t.id, leagueKey: t.leagueKey, name: t.name }] : refs;
  return { teams, teamRefs };
}

/** Unfollow: the ref goes, and the name goes unless another ref still uses it (two teams may share a name across sports). */
export function unfollow(t: Pick<TeamHit, "id" | "name" | "leagueKey">, names: string[], refs: TeamRef[]): { teams: string[]; teamRefs: TeamRef[] } {
  const teamRefs = refs.filter((r) => !(r.teamId === t.id && (!t.leagueKey || r.leagueKey === t.leagueKey)));
  const stillUsed = teamRefs.some((r) => same(r.name, t.name));
  return { teams: stillUsed ? names : names.filter((n) => !same(n, t.name)), teamRefs };
}

/** Remove a favourite by its name alone (the "your teams" chip): its refs go with it. */
export const removeByName = (name: string, names: string[], refs: TeamRef[]): { teams: string[]; teamRefs: TeamRef[] } => ({
  teams: names.filter((n) => !same(n, name)),
  teamRefs: refs.filter((r) => !same(r.name, name)),
});

/** sport.teams as the server stores it: plain names (the old form) and {name, teamId, leagueKey} entries together. Names and refs out. */
export function splitTeams(raw: unknown): { names: string[]; refs: TeamRef[] } {
  const names: string[] = [];
  const refs: TeamRef[] = [];
  for (const x of Array.isArray(raw) ? raw : []) {
    if (typeof x === "string") {
      if (x && !names.some((n) => same(n, x))) names.push(x);
      continue;
    }
    const o = obj(x);
    const name = str(o?.name);
    if (!name) continue;
    if (!names.some((n) => same(n, name))) names.push(name);
    const teamId = str(o?.teamId);
    const leagueKey = str(o?.leagueKey);
    if (teamId && leagueKey && !refs.some((r) => r.teamId === teamId && r.leagueKey === leagueKey)) refs.push({ teamId, leagueKey, name });
  }
  return { names, refs };
}

/** The list to PATCH as sport.teams: every ref as an object, and each name with no ref as a plain string. */
export function teamsPayload(names: string[], refs: TeamRef[]): (string | { name: string; teamId: string; leagueKey: string })[] {
  return [...refs.map((r) => ({ name: r.name, teamId: r.teamId, leagueKey: r.leagueKey })), ...names.filter((n) => !refs.some((r) => same(r.name, n)))];
}
