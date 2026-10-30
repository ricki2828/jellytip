// Not interested in a sport / league, and the server's learned sport taste.
import { prefsNow, savePrefs } from "./me";
import { companionCall } from "./companionCall";
import { parseLearned, withMuted, type Learned } from "../lib/sportMute";
import type { Muted } from "../lib/prefs";

export const MUTED_EVENT = "jellytip-sport-muted";

const EMPTY: Muted = { sports: [], leagues: [], tags: [] };

/** Mute or unmute by field; returns an error text or null. Tells the live and upcoming views to look again. */
export async function setMuted(field: keyof Muted, name: string, on: boolean): Promise<string | null> {
  const cur = prefsNow()?.sport.muted ?? EMPTY;
  const err = await savePrefs({ sport: { muted: withMuted(cur, field, name, on) } });
  if (!err) window.dispatchEvent(new Event(MUTED_EVENT));
  return err;
}

/** What the server learned from viewing; null when it has no such route (404) or can't be reached. */
export async function learnedTaste(): Promise<Learned[] | null> {
  const a = await companionCall("GET", "v1/me/sport/taste");
  return a.status === 200 ? parseLearned(a.data) : null;
}

/** Forget one learned key (e.g. "sport:cricket"); returns an error text or null. */
export async function forgetLearned(key: string): Promise<string | null> {
  const a = await companionCall("POST", "v1/me/sport/taste/forget", { key });
  if (a.status === 200 || a.status === 204) {
    window.dispatchEvent(new Event(MUTED_EVENT));
    return null;
  }
  return a.status === 404 ? "This server can't forget that yet." : `Server said ${a.status}`;
}
