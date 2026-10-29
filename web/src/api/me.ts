// Personalisation on the companion (/v1/me/*; contract "Personalisation API" in ~/ai/plans/jellytip-10x.md). The
// prefs are read once per session and shared by every screen through usePrefs(); a server without the routes (404)
// leaves `available` false and every personalisation control stays hidden.
import { useEffect, useState } from "react";
import { companionCall } from "./companionCall";
import { parseFollows, parsePrefs, parseReactions, parseTaste, type Prefs, type Taste } from "../lib/prefs";

export interface PrefsState {
  /** undefined = still asking; false = no personalisation on this server (or signed out). */
  available: boolean | undefined;
  prefs: Prefs | null;
}

let state: PrefsState = { available: undefined, prefs: null };
let loading: Promise<void> | null = null;
const EVENT = "jellytip-prefs";

function set(s: PrefsState) {
  state = s;
  window.dispatchEvent(new Event(EVENT));
}

function load(): Promise<void> {
  return (loading ??= companionCall("GET", "v1/me/prefs").then((a) => {
    if (a.status === 200) {
      set({ available: true, prefs: parsePrefs(a.data) });
      void loadReactions();
    }
    else set({ available: a.status === 0 ? undefined : false, prefs: null });
    if (a.status === 0) loading = null; // offline: ask again next time
  }));
}

window.addEventListener("jellytip-session", () => {
  loading = null;
  follows = null;
  set({ available: undefined, prefs: null });
});

export function usePrefs(): PrefsState {
  const [s, setS] = useState(state);
  useEffect(() => {
    const on = () => setS(state);
    window.addEventListener(EVENT, on);
    void load();
    on();
    return () => window.removeEventListener(EVENT, on);
  }, []);
  return s;
}

export const prefsNow = () => state.prefs;
/** Prefs, waiting at most `ms` for the first answer (the player must not stall on a slow server). */
export const prefsSoon = (ms = 1500): Promise<Prefs | null> =>
  Promise.race([load(), new Promise((r) => setTimeout(r, ms))]).then(() => state.prefs);
/** Personalisation is on (sync; for many tiles at once without a hook each). Asks the server the first time. */
export const personalOn = () => {
  void load();
  return state.available === true;
};

/** Deep-merged update; returns an error text or null. A 409 (changed elsewhere) reloads and asks to try again. */
export async function savePrefs(patch: Record<string, unknown>): Promise<string | null> {
  const v = state.prefs?.version;
  const a = await companionCall("PATCH", "v1/me/prefs", patch, v != null ? { headers: { "If-Match": String(v) } } : {});
  if (a.status === 200) {
    set({ available: true, prefs: parsePrefs(a.data) });
    window.dispatchEvent(new Event("jellytip-prefs-saved"));
    return null;
  }
  if (a.status === 409) {
    // The server sends the current prefs with the conflict ({code: "version", prefs})
    const cur = a.data && typeof a.data === "object" ? (a.data as { prefs?: unknown }).prefs : undefined;
    if (cur) set({ available: true, prefs: parsePrefs(cur) });
    else {
      loading = null;
      await load();
    }
    return "These settings changed on another device: they're reloaded, try again.";
  }
  return errText(a.status, a.data);
}

function errText(status: number, data: unknown): string {
  const e = data && typeof data === "object" ? (data as { error?: unknown }).error : null;
  if (status === 403 && e === "pin") return "That PIN isn't right.";
  if (status === 429) {
    const ra = data && typeof data === "object" ? Number((data as { retryAfter?: unknown }).retryAfter) : NaN;
    return `Too many tries: wait ${ra > 0 ? Math.max(1, Math.ceil(ra / 60)) : 5} minute${ra > 0 && ra <= 60 ? "" : "s"}.`;
  }
  if (typeof e === "string" && e) return e;
  return status === 0 ? "The server can't be reached." : `Server said ${status}`;
}

// ---------- signals on titles ----------

/** Thumbs and "not interested" have no read route: what this browser said is remembered locally for the buttons. */
const SIGNALS_KEY = "jellytip.signals";
type Signal = { thumb?: 1 | -1; notInterested?: boolean };
const signals: Record<string, Signal> = (() => {
  try {
    return JSON.parse(localStorage.getItem(SIGNALS_KEY) ?? "{}") as Record<string, Signal>;
  } catch {
    return {};
  }
})();
const keepSignals = () => {
  const keys = Object.keys(signals);
  for (const k of keys.slice(0, Math.max(0, keys.length - 2000))) delete signals[k]; // bounded
  localStorage.setItem(SIGNALS_KEY, JSON.stringify(signals));
  window.dispatchEvent(new Event("jellytip-signals"));
};
export const signalOf = (itemId: string): Signal => signals[itemId] ?? {};

/** The person's reactions from the server (they follow the person); this device's cache stays as the fallback. */
async function loadReactions() {
  const a = await companionCall("GET", "v1/me/reactions");
  if (a.status !== 200) return;
  const r = parseReactions(a.data);
  for (const k of Object.keys(signals)) delete signals[k];
  for (const [id, v] of Object.entries(r.thumbs)) signals[id] = { thumb: v };
  for (const id of r.notInterested) signals[id] = { ...signals[id], notInterested: true };
  keepSignals();
}

export function useSignal(itemId: string): Signal {
  const [, bump] = useState(0);
  useEffect(() => {
    const on = () => bump((n) => n + 1);
    window.addEventListener("jellytip-signals", on);
    return () => window.removeEventListener("jellytip-signals", on);
  }, []);
  return signalOf(itemId);
}

export async function setThumb(itemId: string, value: 1 | -1 | 0): Promise<string | null> {
  const a = await companionCall("POST", "v1/me/thumbs", { itemId, value });
  if (a.status !== 200 && a.status !== 201 && a.status !== 204) return errText(a.status, a.data);
  signals[itemId] = { ...signals[itemId], thumb: value === 0 ? undefined : value };
  keepSignals();
  return null;
}

export async function setNotInterested(itemId: string, on: boolean): Promise<string | null> {
  const a = await companionCall(on ? "POST" : "DELETE", "v1/me/not-interested", { itemId });
  if (a.status !== 200 && a.status !== 201 && a.status !== 204) return errText(a.status, a.data);
  signals[itemId] = { ...signals[itemId], notInterested: on || undefined };
  keepSignals();
  return null;
}

// ---------- follows ----------

let follows: Promise<Set<string> | null> | null = null;
export function followed(): Promise<Set<string> | null> {
  return (follows ??= companionCall("GET", "v1/me/follows").then((a) => (a.status === 200 ? new Set(parseFollows(a.data)) : null)));
}

export async function setFollow(personId: string, on: boolean): Promise<string | null> {
  const a = await companionCall(on ? "POST" : "DELETE", "v1/me/follow", { personId });
  if (a.status !== 200 && a.status !== 201 && a.status !== 204) return errText(a.status, a.data);
  const s = await followed();
  if (s) on ? s.add(personId) : s.delete(personId);
  return null;
}

// ---------- taste / kids ----------

export async function taste(): Promise<{ status: number; taste: Taste | null }> {
  const a = await companionCall("GET", "v1/me/taste");
  return { status: a.status, taste: a.status === 200 ? parseTaste(a.data) : null };
}

export async function setKidsPin(pin: string): Promise<string | null> {
  const a = await companionCall("POST", "v1/me/kids/pin", { pin });
  if (a.status === 200 || a.status === 201 || a.status === 204) {
    if (state.prefs) set({ ...state, prefs: { ...state.prefs, kids: { ...state.prefs.kids, pinSet: true } } });
    return null;
  }
  return errText(a.status, a.data);
}

/** The kids ceiling (max age) while a kids profile is on; null otherwise (or before prefs are known). */
export function useKids(): number | null {
  const { prefs } = usePrefs();
  return prefs?.kids.enabled ? prefs.kids.maxAge : null;
}
export const kidsNow = (): number | null => (state.prefs?.kids.enabled ? state.prefs.kids.maxAge : null);
