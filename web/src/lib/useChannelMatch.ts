import { useState } from "react";
import { useFixtures } from "../api/fixtures";
import { resolveChannelMatch, type ChannelMatch } from "./channelMatch";

const CACHE_MS = 60_000;
const cache = new Map<string, { at: number; m: ChannelMatch | null }>();

/** Match behind a directly opened live channel; inert (null) when `enabled` is false. */
export function useChannelMatch(channel: string | null | undefined, enabled: boolean): ChannelMatch | null {
  const { data } = useFixtures(enabled && !!channel);
  const [, tick] = useState(0);
  if (!enabled || !channel) return null;
  const hit = cache.get(channel);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.m;
  if (!data) return null;
  const m = resolveChannelMatch(data.items, channel, Date.now() / 1000);
  cache.set(channel, { at: Date.now(), m });
  // expire the entry so a fixture that goes live later is picked up
  window.setTimeout(() => tick((x) => x + 1), CACHE_MS + 50);
  return m;
}
