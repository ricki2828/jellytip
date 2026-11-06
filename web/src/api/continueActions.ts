// Continue Watching actions against the servers the client already talks to.
import { jf, setPlayed, uid } from "./jellyfin";
import { saveProgress } from "./iptv";
import type { RowEntry } from "../lib/continueRow";

const sid = (e: RowEntry) => e.iptv?.seriesId ?? undefined;

/**
 * Jellyfin: Remove = reset the saved position to 0 (POST /Users/{uid}/Items/{id}/UserData, PlaybackPositionTicks 0), which
 * takes it out of Resume on every client. Next-up tiles have no position, so Remove for them is the local list only.
 * (Jellyfin has no hide-from-resume field; none is used.)
 */
export async function removeFromContinue(e: RowEntry): Promise<void> {
  if (e.source === "lib" && e.lib && !e.next) await jf(`/Users/${uid()}/Items/${e.lib.Id}/UserData`, { method: "POST", json: { PlaybackPositionTicks: 0 } });
}

/** Jellyfin: POST /Users/{uid}/PlayedItems/{id}. IPTV: the companion progress write, finished (position = length). */
export async function markWatched(e: RowEntry): Promise<void> {
  if (e.source === "lib" && e.lib) return setPlayed(e.lib.Id, true);
  if (e.iptv && e.dur > 0) {
    const r = await saveProgress(e.iptv.id, e.dur, e.dur, sid(e));
    if (r.status !== 200) throw new Error(`progress ${r.status}`);
  }
}
