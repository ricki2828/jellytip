// The chapters of the IPTV film / episode playing (lib/skipParts): asked at 2 s, 25 s, 60 s and 150 s after open while the
// server says "not probed yet" or fails; a 404 (no such route / title) stops asking for this title only. Never remembered.
import { useEffect, useState } from "react";
import { iptvChapters } from "../api/iptv";
import { chaptersOutcome, chaptersQuery, POLL_DELAYS_MS, type IptvChapters } from "./skipParts";
import type { PlayTarget } from "./iptvPlay";

export function useIptvChapters(target: PlayTarget, enabled = true): IptvChapters | null {
  const q = enabled ? chaptersQuery(target) : null;
  const [got, setGot] = useState<{ q: string; c: IptvChapters } | null>(null);
  useEffect(() => {
    if (!q) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const ask = (i: number) => {
      if (i >= POLL_DELAYS_MS.length) return;
      timer = setTimeout(async () => {
        const a = await iptvChapters(q);
        if (!alive) return;
        const o = chaptersOutcome(a.status, a.data);
        if (o.kind === "ok") setGot({ q, c: o.value });
        else if (o.kind === "again") ask(i + 1);
      }, POLL_DELAYS_MS[i] - (i > 0 ? POLL_DELAYS_MS[i - 1] : 0));
    };
    ask(0);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [q]);
  return got && got.q === q ? got.c : null;
}
