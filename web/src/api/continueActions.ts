// Continue Watching actions against the servers the client already talks to. "Hide from Continue Watching" is local only
// (per-user list in lib/continueRow); nothing here edits server-side watch data except "Mark as watched".
import { setPlayed } from "./jellyfin";
import { saveProgress } from "./iptv";
import type { RowEntry } from "../lib/continueRow";

/** Jellyfin: POST /Users/{uid}/PlayedItems/{id}. IPTV: the companion progress write with played:true (needs a known length). */
export async function markWatched(e: RowEntry): Promise<void> {
  if (e.source === "lib" && e.lib) return setPlayed(e.lib.Id, true);
  if (e.iptv && e.dur > 0) {
    const r = await saveProgress(e.iptv.id, e.dur, e.dur, e.iptv.seriesId ?? undefined, true);
    if (r.status < 200 || r.status >= 300) throw new Error(`progress ${r.status}`);
  }
}
