/** Keys that are new since the previous distinct batch. The first batch is never new; `seen` accumulates (a line that drops out of a capped window and returns is not new again). */
export interface NewKeysState {
  joined: string | null;
  seen: Set<string> | null;
  fresh: Set<string>;
}

export function newKeysInit(): NewKeysState {
  return { joined: null, seen: null, fresh: new Set() };
}

/** Idempotent for the same batch (safe under double render): recomputes only when the key list changed. */
export function advanceNewKeys(s: NewKeysState, keys: string[]): Set<string> {
  const joined = keys.join("\u0000");
  if (s.joined === joined) return s.fresh;
  const fresh = new Set<string>();
  if (s.seen && keys.length) for (const k of keys) if (!s.seen.has(k)) fresh.add(k);
  s.joined = joined;
  s.seen = new Set([...(s.seen ?? []), ...keys]);
  s.fresh = fresh;
  return fresh;
}
