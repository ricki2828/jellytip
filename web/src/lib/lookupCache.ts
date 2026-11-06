// Per-user TTL cache with a short negative cache (pure; tested in web/test/lookupCache.test.ts).
export const HIT_TTL_MS = 10 * 60_000;
export const MISS_TTL_MS = 2 * 60_000;

export function createLookupCache<T>(now: () => number = Date.now) {
  const m = new Map<
    string,
    { at: number; p: Promise<T | null>; hit: boolean }
  >();
  return {
    /** `user` is part of the key, so one person's result is never served to another. */
    get(
      user: string,
      key: string,
      load: () => Promise<T | null>,
    ): Promise<T | null> {
      const k = `${user}|${key}`;
      const e = m.get(k);
      if (e && now() - e.at < (e.hit ? HIT_TTL_MS : MISS_TTL_MS)) return e.p;
      const p = load().catch(() => null);
      const ent = { at: now(), p, hit: false };
      m.set(k, ent);
      void p.then((v) => {
        ent.hit = v != null;
        ent.at = now();
      });
      return p;
    },
    clear: () => m.clear(),
  };
}
