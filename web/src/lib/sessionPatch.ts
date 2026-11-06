// Metadata updates to the CURRENT session (pure; the storage and the event are the caller's).
export const SESSION_META_EVENT = "jellytip-session-meta";

type Sess = { token: string; userId: string };

/** The patched session, or null when `current` is not the session `expect` names (signed out / another account). */
export function applySessionPatch<S extends Sess, P extends Partial<S>>(current: S | null, expect: Sess, patch: P): S | null {
  if (!current || current.token !== expect.token || current.userId !== expect.userId) return null;
  return { ...current, ...patch, token: current.token, userId: current.userId };
}
