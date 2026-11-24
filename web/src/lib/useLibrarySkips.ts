// Skip intro / recap / credits for the library player (the TV library player's SkipSegmentButton): Jellyfin MediaSegments
// first; with none, the item's chapter names (lib/skipParts chapterSegments). A failed ask retries with backoff while the item
// plays (never remembered); only a 404 (no segments API on this server) stops.
import { useEffect, useMemo, useState } from "react";
import { getMediaSegments } from "../api/seekMeta";
import { chapterSegments, parseMediaSegments, segmentsRetryMs, type SkipSeg } from "./skipParts";

export function useLibrarySkips(id: string, marks: { at: number; name: string }[], duration: number): SkipSeg[] {
  const [segs, setSegs] = useState<{ id: string; segs: SkipSeg[] } | null>(null);
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const ask = async (attempt: number) => {
      const a = await getMediaSegments(id);
      if (!alive) return;
      if (a.status === 200) return setSegs({ id, segs: parseMediaSegments(a.data) });
      const wait = segmentsRetryMs(a.status, attempt);
      if (wait == null) return setSegs({ id, segs: [] });
      timer = setTimeout(() => void ask(attempt + 1), wait);
    };
    void ask(0);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id]);
  const fromServer = segs && segs.id === id ? segs.segs : null;
  const markKey = marks.map((m) => `${m.at}~${m.name}`).join("|");
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const fromChapters = useMemo(() => (duration > 0 ? chapterSegments(marks, duration) : []), [markKey, duration > 0 ? Math.round(duration) : 0]);
  return useMemo(() => (fromServer && fromServer.length ? fromServer : fromServer ? fromChapters : []), [fromServer, fromChapters]);
}
