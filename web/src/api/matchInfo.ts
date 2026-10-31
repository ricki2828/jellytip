// GET /v1/sport/match/{id}. A 404 (no such route) hides every match-info icon.
import { companionCall } from "./companionCall";
import { parseMatchDetail, type MatchDetail } from "../lib/matchInfo";
import { parseLive, type LiveStats } from "../lib/liveStats";

const EVENT = "jellytip-match-info-availability";
let unavailable = false;

export const matchInfoAvailable = () => !unavailable;
export const onMatchInfoAvailability = (cb: () => void) => {
  window.addEventListener(EVENT, cb);
  return () => window.removeEventListener(EVENT, cb);
};
window.addEventListener("jellytip-session", () => {
  unavailable = false;
  window.dispatchEvent(new Event(EVENT));
});

const memo = new Map<string, { at: number; p: Promise<MatchDetail | "missing" | null> }>();
/** The same detail for a few callers at once (player chip, flip names, drawer): one request, kept 30 s. */
export function matchDetailCached(id: string): Promise<MatchDetail | "missing" | null> {
  const m = memo.get(id);
  if (m && Date.now() - m.at < 30_000) return m.p;
  const p = matchDetail(id);
  memo.set(id, { at: Date.now(), p });
  return p;
}

/** `score`: ask for the score too (it is left out unless the person's spoilers setting is on). */
export async function matchDetail(id: string, score = false): Promise<MatchDetail | "missing" | null> {
  const a = await companionCall("GET", `v1/sport/match/${encodeURIComponent(id)}${score ? "?score=1" : ""}`);
  if (a.status === 404 && !score) {
    // Only the route being absent hides the icons; a match the server does not know is an ordinary miss
    const e = a.data && typeof a.data === "object" ? (a.data as { code?: unknown; error?: unknown }) : null;
    if (!e || (!e.code && !e.error)) {
      unavailable = true;
      window.dispatchEvent(new Event(EVENT));
    }
    return "missing";
  }
  return a.status === 200 ? parseMatchDetail(a.data) : null;
}

/** GET /v1/sport/match/{id}/live: "missing" without the route, null when it failed. */
export async function liveStats(id: string): Promise<LiveStats | "missing" | null> {
  const a = await companionCall("GET", `v1/sport/match/${encodeURIComponent(id)}/live`);
  if (a.status === 404) return "missing";
  return a.status === 200 ? parseLive(a.data) : null;
}
