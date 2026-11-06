// Reads the account's subtitle permission once per session and keeps it on the stored session (pure core; the
// session plumbing is injected so it can be tested). It must never announce a session change.
import { shouldLookupPolicy, type SubtitleAccessSession } from "./remoteSubtitles.ts";

type Who = { token: string; userId: string };
type Current = (Who & NonNullable<SubtitleAccessSession>) | null;
export type Policy = { IsAdministrator?: boolean; EnableSubtitleManagement?: boolean } | undefined;

export function createPermissionLookup(d: {
  current: () => Current;
  fetchPolicy: (userId: string) => Promise<Policy>;
  patch: (who: Who, patch: { isAdmin?: boolean; canManageSubtitles: boolean }) => boolean;
}): () => Promise<void> {
  const inflight = new Map<string, Promise<void>>();
  const refreshed = new Set<string>();
  return () => {
    const s = d.current();
    if (!s) return Promise.resolve();
    const key = `${s.token}:${s.userId}`;
    const running = inflight.get(key);
    if (running) return running;
    if (!shouldLookupPolicy(s, refreshed.has(key))) return Promise.resolve();
    const p = (async () => {
      try {
        const policy = await d.fetchPolicy(s.userId);
        // The session may have been replaced while the request ran: patch only if it is still the same one
        if (d.patch({ token: s.token, userId: s.userId }, { isAdmin: s.isAdmin === true || !!policy?.IsAdministrator, canManageSubtitles: !!policy?.EnableSubtitleManagement })) refreshed.add(key); // a failure is retried on the next visit; unknown stays hidden meanwhile
      } catch {
        /* keep what we had */
      } finally {
        inflight.delete(key);
      }
    })();
    inflight.set(key, p);
    return p;
  };
}
