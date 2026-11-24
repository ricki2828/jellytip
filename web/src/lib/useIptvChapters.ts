// The chapters of the IPTV film / episode playing (lib/skipParts): asked at 2 s, 25 s, 60 s and 150 s after open while the
// server says "not probed yet" or fails; a 404 (no such route / title) stops asking for this title only. Never remembered.
import { useEffect, useRef, useState } from "react";
import { iptvChapters } from "../api/iptv";
import { chaptersOutcome, chaptersQuery, nativeChapters, POLL_DELAYS_MS, type IptvChapters } from "./skipParts";
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

/** Phone: the same chapters, pushed to the open native player (PlayerPlugin.chapters); again every 2 s (5 tries) while none is open yet. */
export function useNativeChapters(target: PlayTarget, enabled: boolean, push: (p: ReturnType<typeof nativeChapters>) => Promise<{ shown: boolean }>) {
  const c = useIptvChapters(target, enabled);
  const pushRef = useRef(push);
  pushRef.current = push;
  useEffect(() => {
    if (!c) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const send = (tries: number) => {
      void pushRef.current(nativeChapters(c)).then(
        (r) => {
          if (alive && !r.shown && tries > 1) timer = setTimeout(() => send(tries - 1), 2_000);
        },
        () => undefined,
      );
    };
    send(5);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [c]);
}
