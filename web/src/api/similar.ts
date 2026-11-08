// "More like this": GET /jellytip-api/similar (contract: ~/ai/state/jellytip-similar-contract.md). Library and IPTV titles
// mixed, parsed into a ServerRow so the existing row/tile code draws it. Any failure or empty answer = null (callers
// fall back or hide the row).
import { companionCall } from "./companionCall";
import { parseSimilar, similarPath, type SimilarQuery } from "../lib/similar";
import type { ServerRow } from "../lib/rows";
import { getSession } from "./jellyfin";

// Short-lived: a title just finished or thumbed down must drop out soon. Cleared on a thumb / not-interested and on session change.
const TTL_MS = 2 * 60_000;
const cache = new Map<string, { at: number; p: Promise<ServerRow | null> }>();
if (typeof window !== "undefined") {
  window.addEventListener("jellytip-session", () => cache.clear());
  window.addEventListener("jellytip-signals", () => cache.clear());
}

/** Similar titles for this one; null on error, no id to ask with, or an empty answer. */
export function similarRow(q: SimilarQuery): Promise<ServerRow | null> {
  const path = similarPath(q);
  const user = getSession()?.userId;
  if (!path || !user) return Promise.resolve(null);
  const key = `${user}|${path}`;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.p;
  const p = companionCall("GET", path).then((r) => (r.status === 200 ? parseSimilar(r.data, q.kind) : null)).catch(() => null);
  cache.set(key, { at: Date.now(), p });
  return p;
}
