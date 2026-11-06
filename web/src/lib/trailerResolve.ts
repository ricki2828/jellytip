// Resolves a PreviewTarget to a playable URL through the session cache. Fetchers are injected so the cache,
// transient-vs-final handling, session change and the stale-start guard can be tested without a network.
import { iptvPlayable, targetKey, type PreviewTarget, TrailerCache } from "./trailerPreview.ts";

export interface Fetchers {
  /** The first local trailer's playable URL, or null when the item has none. THROWS on any failure (so it is not mistaken for "no trailer"). */
  jf(id: string): Promise<string | null>;
  /** The companion's detail answer: HTTP-ish status plus the trailer field of a 200. Throws on network failure. */
  iptv(id: string): Promise<{ status: number; trailer?: string | null }>;
  /** Companion-relative path to absolute URL. */
  rel(path: string): string;
}

export function createResolver(f: Fetchers, cache = new TrailerCache(2), now: () => number = Date.now) {
  let gen = 0;

  async function resolve(t: Exclude<PreviewTarget, { kind: "direct" }>): Promise<string | null> {
    const key = targetKey(t);
    if (!cache.shouldFetch(key, now())) return cache.get(key);
    const started = gen;
    // A session change while we waited: the answer belongs to the old user (and may carry their token); drop it
    const current = () => started === gen;
    try {
      if (t.kind === "jf") {
        const url = await f.jf(t.id);
        if (current()) cache.record(key, url, true); // the library answered: final
        return current() ? url : null;
      }
      const a = await f.iptv(t.id);
      if (!current()) return null;
      if (a.status !== 200) {
        cache.transient(key, now()); // 429 / 5xx / 404 hiccups do not spend the re-check budget
        return null;
      }
      const raw = a.trailer;
      const url = iptvPlayable(raw, f.rel);
      // 200 without a trailer yet: counts as a miss (one re-check). A web link never plays inline: final.
      cache.record(key, url, !!raw && !url);
      return url;
    } catch {
      if (current()) cache.transient(key, now());
      return null;
    }
  }

  return {
    resolve,
    /** Session changed: forget everything and orphan in-flight asks. */
    reset() {
      gen++;
      cache.clear();
    },
    /** Resolve and hand the URL to `onUrl` unless cancelled first (a late answer never starts a stale trailer). Returns cancel. */
    start(t: Exclude<PreviewTarget, { kind: "direct" }>, onUrl: (u: string | null) => void): () => void {
      let alive = true;
      void resolve(t).then((u) => alive && onUrl(u));
      return () => {
        alive = false;
      };
    },
  };
}
