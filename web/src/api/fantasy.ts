// Fantasy (Sleeper) live scoring. Hidden when the routes are missing (404) or no account is linked (empty leagues).
import { useEffect, useState } from "react";
import { companionCall } from "./companionCall";
import { prefsNow, savePrefs, usePrefs } from "./me";
import { parseFantasy, parseStarters, type FantasyLive, type Starter } from "../lib/fantasy";

const EVENT = "jellytip-fantasy";
const FRESH_MS = 45_000;
let state: FantasyLive | null = null;
let unavailable = false;
let at = 0;
let inflight: Promise<FantasyLive | null> | null = null;

/** The live scoring, cached 45 s; null when there is none (no route, no account, nothing yet). */
export function fantasyLive(force = false): Promise<FantasyLive | null> {
  if (unavailable) return Promise.resolve(null);
  if (!force && state && Date.now() - at < FRESH_MS) return Promise.resolve(state);
  if (inflight) return inflight;
  inflight = companionCall("GET", "v1/fantasy/live")
    .then((a) => {
      if (a.status === 404) unavailable = true;
      else if (a.status === 200) {
        state = parseFantasy(a.data);
        at = Date.now();
      }
      window.dispatchEvent(new Event(EVENT));
      return state;
    })
    .finally(() => (inflight = null));
  return inflight;
}

window.addEventListener("jellytip-session", () => {
  state = null;
  unavailable = false;
  at = 0;
});

export const fantasyMissing = () => unavailable;

/** My and the opponent's starters in one NFL game, per league; [] when none. `matchId` is the matchInfo.id, used as is. */
export async function fantasyGame(matchId: string): Promise<{ id: string; league: string; starters: Starter[] }[]> {
  const a = await companionCall("GET", `v1/fantasy/game/${encodeURIComponent(matchId)}`);
  if (a.status !== 200 || !a.data || typeof a.data !== "object") return [];
  const leagues = (a.data as { leagues?: unknown }).leagues;
  return (Array.isArray(leagues) ? leagues : []).flatMap((x) => {
    const o = x as { name?: unknown; id?: unknown; starters?: unknown };
    const starters = parseStarters(o?.starters);
    return starters.length ? [{ id: String(o.id ?? ""), league: typeof o.name === "string" ? o.name : String(o.id ?? ""), starters }] : [];
  });
}

/** Whether the person linked a Sleeper account (the server only fetches after that). */
export const sleeperUsername = () => prefsNow()?.fantasy.sleeperUsername ?? null;

/** Live fantasy data for a component: null until known, when no account is linked, or without the route. */
export function useFantasy(active = true): FantasyLive | null {
  const { prefs } = usePrefs({ defer: true });
  const linked = !!prefs?.fantasy.sleeperUsername;
  const [live, setLive] = useState<FantasyLive | null>(state);
  useEffect(() => {
    if (!active || !linked) return setLive(null);
    let alive = true;
    const on = () => alive && setLive(state);
    window.addEventListener(EVENT, on);
    void fantasyLive().then((d) => alive && setLive(d));
    return () => {
      alive = false;
      window.removeEventListener(EVENT, on);
    };
  }, [active, linked]);
  return active && linked ? live : null;
}

export const saveSleeperUsername = (username: string | null) => savePrefs({ fantasy: { sleeper: { username } } });
export const saveFantasyAlerts = (alerts: boolean) => savePrefs({ fantasy: { alerts } });
export const saveAlertMin = (alertMin: number) => savePrefs({ fantasy: { alertMin } });
