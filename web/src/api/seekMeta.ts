// Trickplay + chapter metadata for the seek bar. A separate, optional request: an older server that rejects the extra
// Fields just yields no thumbnails/ticks and playback is unaffected.
import { getSession, JF_BASE, jf, qs, uid } from "./jellyfin";
import type { Chapter } from "../lib/chapters";
import { pickTrickplay, tileUrl, type TrickplayMap } from "../lib/trickplay";

export interface SeekMeta {
  chapters: Chapter[];
  trickplay: TrickplayMap;
}

export async function getSeekMeta(id: string): Promise<SeekMeta> {
  try {
    const r = await jf<{ Chapters?: Chapter[]; Trickplay?: TrickplayMap }>(`/Users/${uid()}/Items/${id}${qs({ Fields: "Chapters,Trickplay" })}`);
    return { chapters: r.Chapters ?? [], trickplay: r.Trickplay ?? null };
  } catch {
    return { chapters: [], trickplay: null };
  }
}

/** Builds the Scrubber's thumbnail source for one item/media source, or null when there is no trickplay. */
export function trickplaySource(itemId: string, sourceId: string, map: TrickplayMap) {
  const pick = pickTrickplay(map, sourceId);
  const token = getSession()?.token;
  if (!pick || !token) return null;
  return { info: pick.info, url: (sheet: number) => tileUrl(JF_BASE, itemId, pick.width, sheet, sourceId, token) };
}
