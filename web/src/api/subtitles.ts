// Jellyfin remote subtitle search / download for one item (the same native API the TV app uses). The path and
// result logic is pure and lives in lib/remoteSubtitles.ts.
import { getItem, getSession, jf, setSession, type Item, type MediaStream } from "./jellyfin";
import { DOWNLOAD_TIMEOUT_MS, SEARCH_TIMEOUT_MS, downloadPath, mapResults, needsPolicyLookup, searchPath, waitForNewSubtitle, withDeadline, type RemoteSubtitle } from "../lib/remoteSubtitles";

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function searchSubtitles(itemId: string, language: string, outer?: AbortSignal, timeoutMs = SEARCH_TIMEOUT_MS): Promise<RemoteSubtitle[]> {
  return withDeadline(async (signal) => mapResults(await jf(searchPath(itemId, language), { signal })), timeoutMs, outer);
}

export function downloadSubtitle(itemId: string, subtitleId: string, outer?: AbortSignal): Promise<void> {
  return withDeadline(async (signal) => {
    await jf(downloadPath(itemId, subtitleId), { method: "POST", signal });
  }, DOWNLOAD_TIMEOUT_MS, outer);
}

const sourceStreams = (it: Item, sourceId?: string): MediaStream[] => (it.MediaSources?.find((m) => m.Id === sourceId) ?? it.MediaSources?.[0])?.MediaStreams ?? [];

/** The item's streams before a download (so a new track can be spotted afterwards). */
export async function currentStreams(itemId: string, sourceId?: string): Promise<MediaStream[]> {
  return sourceStreams(await getItem(itemId), sourceId);
}

/** After a download: poll the item until the new track is listed. `item` is the refreshed item either way. */
export async function refreshAfterDownload(itemId: string, before: readonly MediaStream[], sourceId?: string): Promise<{ item: Item | null; listed: boolean }> {
  let item: Item | null = null;
  const r = await waitForNewSubtitle(async () => {
    item = await getItem(itemId);
    return sourceStreams(item, sourceId);
  }, before, wait);
  return { item, listed: !!r.added };
}

/** Sessions stored before the subtitle permission was recorded: read the policy once and keep it on the session. */
export async function loadSubtitlePermission(): Promise<void> {
  const s = getSession();
  if (!s || !needsPolicyLookup(s)) return;
  try {
    const u = await jf<{ Policy?: { IsAdministrator?: boolean; EnableSubtitleManagement?: boolean } }>(`/Users/${encodeURIComponent(s.userId)}`);
    const cur = getSession();
    // The session may have been replaced (sign-out, another account) while the request ran: only write to the same one
    if (!cur || cur.token !== s.token || cur.userId !== s.userId) return;
    setSession({ ...cur, isAdmin: cur.isAdmin || !!u.Policy?.IsAdministrator, canManageSubtitles: !!u.Policy?.EnableSubtitleManagement });
  } catch {
    /* unknown stays hidden; the next visit asks again */
  }
}
