// GET /v1/sport/match/{id}. A 404 (no such route) hides every match-info icon.
import { companionCall } from "./companionCall";
import { matchOutcome, parseMatchDetail, type MatchDetail } from "../lib/matchInfo";
import { parseLive, type LiveStats } from "../lib/liveStats";

const EVENT = "jellytip-match-info-availability";
const RETRY_MS = 10 * 60_000;
// Status codes (plan "match routes' status codes"): 404 = the route is missing (hide every icon, but look again after 10 min);
// 403 = no IPTV for this person (hide the match UI until they sign in again); 200 {unknown: true} = this match only.
let hiddenUntil = 0;
let forbidden = false;
const unknown = new Set<string>();

const changed = () => window.dispatchEvent(new Event(EVENT));

/** Whether match UI shows: not forbidden, not in a 404 pause, and (with an id) not a match the server does not know. */
export const matchInfoAvailable = (id?: string | null) => !forbidden && Date.now() >= hiddenUntil && !(id && unknown.has(id));
export const onMatchInfoAvailability = (cb: () => void) => {
  window.addEventListener(EVENT, cb);
  return () => window.removeEventListener(EVENT, cb);
};
window.addEventListener("jellytip-session", () => {
  hiddenUntil = 0;
  forbidden = false;
  unknown.clear();
  changed();
});

/** Read a match route's answer: null means "go on and parse it"; otherwise the outcome that ends it. */
function gate(id: string, status: number, data: unknown): "missing" | null {
  const o = matchOutcome(status, data);
  if (!o) return null;
  if (o === "forbidden") forbidden = true;
  else if (o === "route-missing") {
    hiddenUntil = Date.now() + RETRY_MS;
    window.setTimeout(changed, RETRY_MS + 200); // the icons come back by themselves
  } else unknown.add(id);
  changed();
  return "missing";
}

const memo = new Map<string, { at: number; p: Promise<MatchDetail | "missing" | null> }>();
/** The same detail for a few callers at once (player chip, flip names, drawer): one request, kept 30 s. */
export function matchDetailCached(id: string): Promise<MatchDetail | "missing" | null> {
  const m = memo.get(id);
  if (m && Date.now() - m.at < 30_000) return m.p;
  const p = matchDetail(id);
  memo.set(id, { at: Date.now(), p });
  return p;
}

/** `score`: ask for the score too (it is left out unless the person's spoilers setting is on). "missing": no match info to show. */
export async function matchDetail(id: string, score = false): Promise<MatchDetail | "missing" | null> {
  const a = await companionCall("GET", `v1/sport/match/${encodeURIComponent(id)}${score ? "?score=1" : ""}`);
  const g = gate(id, a.status, a.data);
  if (g) return g;
  return a.status === 200 ? parseMatchDetail(a.data) : null;
}

/** GET /v1/sport/match/{id}/live: "missing" when there is nothing (route, access or an unknown match), null when it failed. */
export async function liveStats(id: string): Promise<LiveStats | "missing" | null> {
  const a = await companionCall("GET", `v1/sport/match/${encodeURIComponent(id)}/live`);
  const g = gate(id, a.status, a.data);
  if (g) return g;
  return a.status === 200 ? parseLive(a.data) : null;
}
