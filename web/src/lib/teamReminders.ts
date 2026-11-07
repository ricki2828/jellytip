// "Remind me for every <team> game" (GET v1/iptv/reminders/teams; contract: ~/ai/state/jellytip-fixtures-contract.md "Team reminders").
// Pure; tested in web/test/teamReminders.test.ts.
export interface TeamReminder {
  id: string;
  teamId: string;
  teamName: string;
  sport: string | null;
  league: string | null;
  until: number | null;
}

/** One side of a match, as the actions need it. A side without a teamId (F1, golf, tennis, a guessed matchup) offers nothing. */
export interface TeamSide {
  teamId: string | null;
  name: string;
  short: string | null;
  sport: string | null;
  league: string | null;
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : null);

export function parseTeamReminders(raw: unknown): TeamReminder[] {
  const list = Array.isArray(obj(raw)?.items) ? (obj(raw)!.items as unknown[]) : [];
  const out: TeamReminder[] = [];
  for (const x of list) {
    const o = obj(x);
    const id = str(o?.id);
    const teamId = str(o?.teamId);
    if (!o || !id || !teamId) continue;
    out.push({ id, teamId, teamName: str(o.teamName) ?? teamId, sport: str(o.sport), league: str(o.league), until: typeof o.until === "number" ? o.until : null });
  }
  return out;
}

/** Short team name for button text: the server's `short` when it is shorter, else the last word of "Boston Celtics" style names. */
export function shortTeam(side: Pick<TeamSide, "name" | "short">): string {
  if (side.short && side.short.length <= side.name.length) return side.short;
  return side.name;
}

/** The sides worth offering: has a team id, no duplicates, in the given order. */
export function offerSides(sides: TeamSide[]): (TeamSide & { teamId: string })[] {
  const seen = new Set<string>();
  const out: (TeamSide & { teamId: string })[] = [];
  for (const s of sides) {
    if (!s.teamId || !s.name || seen.has(s.teamId)) continue;
    seen.add(s.teamId);
    out.push({ ...s, teamId: s.teamId });
  }
  return out;
}

export const remindEveryText = (name: string) => `Remind me for every ${name} game`;
export const remindingText = (name: string) => `Reminding for every ${name} game`;

interface SideLike {
  name: string;
  short: string | null;
  teamId: string | null;
}
/** Sides of a matchInfo fixture ("match" kind only: events have no teams). */
export function fixtureSides(f: { kind: string; home: SideLike; away: SideLike; league: string | null }, sport: string | null): TeamSide[] {
  if (f.kind !== "match") return [];
  return [f.home, f.away].map((s) => ({ teamId: s.teamId, name: s.name, short: s.short, sport, league: f.league }));
}
