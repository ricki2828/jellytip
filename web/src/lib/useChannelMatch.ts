import { useEffect, useState } from "react";
import { refreshFixtures, useFixtures } from "../api/fixtures";
import { resolveChannelMatch, type ChannelMatch } from "./channelMatch";

const CACHE_MS = 60_000;
const cache = new Map<string, { at: number; m: ChannelMatch | null }>();

/** Match behind a directly opened live channel; inert (null) when `enabled` is false. Re-resolved every 60 s from a refreshed list (refreshFixtures is deduped and cached 25 s). */
export function useChannelMatch(channel: string | null | undefined, enabled: boolean): ChannelMatch | null {
  const on = enabled && !!channel;
  const { data } = useFixtures(on);
  const [, tick] = useState(0);
  useEffect(() => {
    if (!on) return;
    const id = window.setInterval(() => {
      void refreshFixtures().then(() => tick((x) => x + 1));
    }, CACHE_MS + 50);
    return () => window.clearInterval(id);
  }, [on, channel]);
  if (!on || !channel) return null;
  const hit = cache.get(channel);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.m;
  if (!data) return null;
  const m = resolveChannelMatch(data.items, channel, Date.now() / 1000);
  cache.set(channel, { at: Date.now(), m });
  return m;
}
