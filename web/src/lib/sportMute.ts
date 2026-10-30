// "Not interested" in a sport or league, and what the server learned from viewing (pure; tested in web/test/iptv.test.ts).
import type { Muted } from "./prefs.ts";

export type MuteKind = "leagues" | "sports";

export interface MuteChoice {
  kind: MuteKind;
  name: string;
  label: string;
}

const has = (list: string[], name: string) => list.some((x) => x.toLowerCase() === name.toLowerCase());

/** What a tile can be muted by: its league first, then its sport; names already muted are left out. */
export function muteChoices(t: { league?: string | null; sportName?: string | null }, muted: Muted): MuteChoice[] {
  const out: MuteChoice[] = [];
  if (t.league && !has(muted.leagues, t.league)) out.push({ kind: "leagues", name: t.league, label: `Not interested in ${t.league}` });
  if (t.sportName && !has(muted.sports, t.sportName) && t.sportName.toLowerCase() !== (t.league ?? "").toLowerCase()) out.push({ kind: "sports", name: t.sportName, label: `Not interested in ${t.sportName}` });
  return out;
}

/** The muted lists after adding (on) or removing a name (case-insensitive); the other lists are untouched. */
export function withMuted(muted: Muted, field: keyof Muted, name: string, on: boolean): Muted {
  const rest = muted[field].filter((x) => x.toLowerCase() !== name.toLowerCase());
  return { ...muted, [field]: on ? [...rest, name] : rest };
}

export interface Learned {
  key: string;
  label: string;
  score: number;
}

const KEY_LABEL = /^(sport|league|team|tag):(.+)$/i;

/** GET /v1/me/sport/taste → the learned affinities, strongest first (keys like "sport:cricket"). Accepts a list of
 * {key, score} under `affinities` / `top` / `items`, or a {key: score} map. Negative scores are not shown. */
export function parseLearned(raw: unknown): Learned[] {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : null;
  const src = r ? (r.affinities ?? r.top ?? r.items ?? r.taste ?? r) : null;
  const rows: { key: unknown; score: unknown }[] = Array.isArray(src)
    ? (src as { key?: unknown; score?: unknown }[]).map((x) => ({ key: x?.key, score: x?.score }))
    : src && typeof src === "object"
      ? Object.entries(src as Record<string, unknown>).map(([key, score]) => ({ key, score }))
      : [];
  const out: Learned[] = [];
  for (const x of rows) {
    if (typeof x.key !== "string" || typeof x.score !== "number" || !isFinite(x.score) || x.score <= 0) continue;
    const m = KEY_LABEL.exec(x.key);
    if (!m) continue;
    const name = m[2].replace(/[_-]+/g, " ");
    const kind = m[1].toLowerCase();
    out.push({ key: x.key, label: `${name[0].toUpperCase()}${name.slice(1)}${kind === "sport" ? "" : ` (${kind})`}`, score: x.score });
  }
  return out.sort((a, b) => b.score - a.score);
}

// ---------- "Sports I follow" ----------

/** Sports offered in the picker: the server's sportName vocabulary (what is saved) and a display label. */
export const SPORTS: { value: string; label: string }[] = [
  { value: "rugby league", label: "Rugby league" },
  { value: "rugby union", label: "Rugby union" },
  { value: "cricket", label: "Cricket" },
  { value: "nfl", label: "NFL" },
  { value: "college football", label: "College football" },
  { value: "nba", label: "NBA" },
  { value: "football", label: "Football / soccer" },
  { value: "afl", label: "AFL" },
  { value: "motorsport", label: "Motorsport" },
  { value: "tennis", label: "Tennis" },
  { value: "golf", label: "Golf" },
  { value: "horse racing", label: "Horse racing" },
  { value: "combat", label: "Combat sports" },
];

export type SportState = "follow" | "mute" | "neutral";

const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();

/** Followed sports live in sport.sports, muted ones in sport.muted.sports. */
export function sportState(name: string, follows: string[], muted: Muted): SportState {
  if (follows.some((x) => same(x, name))) return "follow";
  if (muted.sports.some((x) => same(x, name))) return "mute";
  return "neutral";
}

/** Tap cycle: neutral → follow → mute → neutral. */
export const nextSportState = (s: SportState): SportState => (s === "neutral" ? "follow" : s === "follow" ? "mute" : "neutral");

/** The new followed-sports list and muted lists after setting a sport's state (a sport is never in both). */
export function applySportState(name: string, state: SportState, follows: string[], muted: Muted): { sports: string[]; muted: Muted } {
  const kept = follows.filter((x) => !same(x, name));
  const sports = muted.sports.filter((x) => !same(x, name));
  return { sports: state === "follow" ? [...kept, name] : kept, muted: { ...muted, sports: state === "mute" ? [...sports, name] : sports } };
}

/** Women's competitions and replays/highlights are muted by tag. */
export const TAG_WOMENS = "womens";
export const TAG_REPLAYS = "replays";
export const hasTag = (muted: Muted, tag: string) => muted.tags.some((t) => same(t, tag));

/** muted.tags with a tag added or removed. */
export const withMutedTag = (muted: Muted, tag: string, on: boolean): Muted => withMuted(muted, "tags", tag, on);

/** The person has already told us something about sport (so the first-run prompt isn't for them). */
export const hasSportPrefs = (s: { teams: string[]; leagues: string[]; sports: string[]; muted: Muted; setup: boolean }) =>
  s.setup || s.teams.length + s.leagues.length + s.sports.length + s.muted.sports.length + s.muted.leagues.length + s.muted.tags.length > 0;
