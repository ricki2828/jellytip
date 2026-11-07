// A channel opened directly (not from a match tile) carries no matchId. Resolve it from the fixtures list: a live (or about to
// start) fixture whose channels include this one. Pure (the hook is in useChannelMatch.ts).
import type { FxItem } from "./gameCentre";

export const SOON_S = 15 * 60;
export interface ChannelMatch { matchId: string; maybe: boolean; title: string }

const norm = (s: string) => s.trim().toLowerCase();

export function resolveChannelMatch(items: FxItem[], channel: string | null | undefined, nowSec: number): ChannelMatch | null {
  if (!channel) return null;
  const want = norm(channel);
  let best: { m: ChannelMatch; conf: number; live: boolean } | null = null;
  for (const f of items) {
    const mid = f.matchId ?? f.fid;
    if (!mid) continue;
    const live = f.status === "in";
    if (!live && !(f.status === "pre" && f.kickoff - nowSec <= SOON_S && f.kickoff - nowSec > -SOON_S)) continue;
    const c = f.channels.find((x) => norm(x.id) === want);
    if (!c) continue;
    if (!best || (live && !best.live) || (live === best.live && c.confidence > best.conf)) {
      best = { m: { matchId: mid, maybe: c.maybe || c.dormant, title: f.home && f.away ? `${f.home} v ${f.away}` : f.league ?? "" }, conf: c.confidence, live };
    }
  }
  return best?.m ?? null;
}
