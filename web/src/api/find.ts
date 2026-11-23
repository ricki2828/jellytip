// The one box's router (GET /v1/find) and its feedback (POST /v1/find/feedback); contract ~/ai/state/fleet/jellytip-find-v1-spec.md.
// Through companionCall (native HTTP in the phone app). Decisions are lib/oneBox.ts's.
import { companionCall as call } from "./companionCall";
import { parseSearch, type SearchResult } from "../lib/search";
import { registerLogoStyles } from "../lib/logoStyle";
import { registerTechs } from "../lib/techBadges";
import { findOutcome, NoRouteMemory, type FindOutcome, type FindSwitch } from "../lib/oneBox";
import type { Call } from "../lib/askChat";

const TYPES = "films,shows,people,collections,lists,iptv,discover,live,channels";
/** The find itself is the search (~190 ms); past this it is a failure (Try again), never a hang. */
const FIND_TIMEOUT_MS = 10_000;
/** A chat turn: the server allows the AI 6 s; the phone's native timeout must not cut it at its 8 s default. */
export const CHAT_TIMEOUT_MS = 12_000;

/** A 404 from /v1/find (an older server), remembered in memory for a while; a new sign-in forgets it. */
export const noRoute = new NoRouteMemory();
if (typeof window !== "undefined") window.addEventListener("jellytip-session", () => noRoute.clear());

const parse = (raw: unknown): SearchResult | null => {
  const r = parseSearch(raw);
  if (r) {
    registerTechs(r.techs);
    registerLogoStyles(r.logoStyles);
  }
  return r;
};

export async function find(q: string, signal?: AbortSignal): Promise<FindOutcome<SearchResult>> {
  const ctl = new AbortController();
  const stop = () => ctl.abort();
  signal?.addEventListener("abort", stop);
  // a setTimeout, not AbortSignal.timeout: older WebViews lack it
  let timer: ReturnType<typeof setTimeout> | undefined;
  const dead = new Promise<{ status: number; data: unknown }>((res) => {
    timer = setTimeout(() => {
      ctl.abort();
      res({ status: 0, data: null });
    }, FIND_TIMEOUT_MS);
  });
  try {
    const a = await Promise.race([call("GET", `v1/find?q=${encodeURIComponent(q)}&limit=8&types=${TYPES}`, undefined, { signal: ctl.signal, timeoutMs: FIND_TIMEOUT_MS }), dead]);
    const out = findOutcome(a.status, a.data, parse);
    if (out.kind === "noRoute") noRoute.mark(Date.now());
    return out;
  } finally {
    if (timer) clearTimeout(timer);
    signal?.removeEventListener("abort", stop);
  }
}

/** Best effort, fire and forget (the screen sends it at most once per decision id). */
export function findFeedback(id: string, switched: FindSwitch): void {
  void call("POST", "v1/find/feedback", { id, switched }).catch(() => undefined);
}

/** The chat sender's call, with the chat's longer timeout on the phone. */
export const chatCall: Call = (method, path, body, opts) => call(method, path, body, { ...opts, timeoutMs: CHAT_TIMEOUT_MS });
