import type { FantasyEvent, FantasyLeague, FantasyLive } from "./fantasy";

export function sourceAgeSeconds(updatedAt: number | null, nowMs: number): number | null {
  return updatedAt == null ? null : Math.max(0, Math.floor(nowMs / 1000 - updatedAt));
}

export function snapshotStale(live: FantasyLive | null, failed: boolean, nowMs: number): boolean {
  if (failed) return true;
  if (!live || !live.leagues.some(l => l.starters.some(s => s.gameState === "in"))) return false;
  const age = sourceAgeSeconds(live.updatedAt, nowMs);
  return age == null || age > 90;
}

export function leagueEvents(events: FantasyEvent[], league: FantasyLeague): FantasyEvent[] {
  const seen = new Set<string>();
  return events.filter(e => e.league === league.id || e.league === league.name)
    .sort((a, b) => b.at - a.at)
    .filter(e => {
      const key = [e.at, e.side, e.name, e.delta, e.reason ?? ""].join("|");
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }).slice(0, 3);
}

export function scoreState(league: FantasyLeague): string {
  if (!league.starters.some(s => s.gameState === "in" || s.gameState === "post")) return "Awaiting player games";
  const difference = league.me.points - league.opp.points;
  if (Math.abs(difference) < 0.05) return "Tied";
  return (difference > 0 ? "You lead" : "You trail") + " by " + Math.abs(difference).toFixed(1);
}
