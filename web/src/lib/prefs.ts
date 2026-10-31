// Personalisation shapes (pure; tested in web/test/prefs.test.ts). Contract "Personalisation API" in
// ~/ai/plans/jellytip-10x.md: prefs live on the server and follow the person across TV, web and phone.

export interface Prefs {
  home: { pinned: string[]; hidden: string[]; order: string[]; specialsPerDay: number };
  hideWatched: boolean;
  playback: { intro: boolean; trailerAutoplay: boolean; trailerSound: boolean; subtitleLangs: string[]; audioLangs: string[] };
  letterboxd: { username: string | null; status: "none" | "pending" | "linked" | "error" };
  /** Favourite teams and leagues: Live TV puts what is on now first (the server does the ranking). */
  sport: { teams: string[]; leagues: string[]; /** Followed sports (the server's sportName vocabulary). */ sports: string[]; muted: Muted; setup: boolean; /** Show scores (spoilers) in match info. */ spoilers: boolean };
  /** The server returns `sport.sports` (an array, even empty): only then can followed sports and the first-run sheet be saved. */
  sportsSupported: boolean;
  version: number | null;
}

export interface Muted {
  sports: string[];
  leagues: string[];
  tags: string[];
}

export const SPECIALS_MAX = 6;

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const strs = (v: unknown) => [...new Set((Array.isArray(v) ? v : []).filter((x): x is string => typeof x === "string" && !!x))];
const bool = (v: unknown, d: boolean) => (typeof v === "boolean" ? v : d);
const LB = ["none", "pending", "linked", "error"] as const;

/** Server prefs → complete prefs (defaults fill anything missing or malformed). */
export function parsePrefs(raw: unknown): Prefs {
  const r = obj(raw);
  const h = obj(r.home);
  const p = obj(r.playback);
  const l = obj(r.letterboxd);
  const sp = obj(r.sport);
  const spd = typeof h.specialsPerDay === "number" && isFinite(h.specialsPerDay) ? Math.max(0, Math.min(SPECIALS_MAX, Math.round(h.specialsPerDay))) : 3;
  return {
    home: { pinned: strs(h.pinned), hidden: strs(h.hidden), order: strs(h.order), specialsPerDay: spd },
    hideWatched: bool(r.hideWatched, false),
    playback: {
      intro: bool(p.intro, true),
      trailerAutoplay: bool(p.trailerAutoplay, true),
      trailerSound: bool(p.trailerSound, false),
      subtitleLangs: strs(p.subtitleLangs),
      audioLangs: strs(p.audioLangs),
    },
    letterboxd: { username: typeof l.username === "string" && l.username ? l.username : null, status: LB.includes(l.status as never) ? (l.status as Prefs["letterboxd"]["status"]) : "none" },
    sport: { teams: strs(sp.teams), leagues: strs(sp.leagues), sports: strs(sp.sports), muted: { sports: strs(obj(sp.muted).sports), leagues: strs(obj(sp.muted).leagues), tags: strs(obj(sp.muted).tags) }, setup: sp.setup === true, spoilers: sp.spoilers === true },
    sportsSupported: Array.isArray(sp.sports),
    version: typeof r.version === "number" ? r.version : null,
  };
}

// ---------- Home edit ----------

export interface EditRow {
  id: string;
  title: string;
  pinned: boolean;
  hidden: boolean;
}

/** Rows for the edit list: today's rows in the person's order (pinned first), then hidden ones (titles remembered). */
export function editRows(rows: { id: string; title: string }[], home: Prefs["home"], titles: Record<string, string> = {}): EditRow[] {
  const seen = new Set<string>();
  const out: EditRow[] = [];
  const add = (id: string, title: string) => {
    if (seen.has(id)) return;
    seen.add(id);
    out.push({ id, title, pinned: home.pinned.includes(id), hidden: home.hidden.includes(id) });
  };
  const byId = new Map(rows.map((r) => [r.id, r.title]));
  for (const id of home.pinned) if (byId.has(id) && !home.hidden.includes(id)) add(id, byId.get(id)!);
  for (const r of rows) if (!home.hidden.includes(r.id)) add(r.id, r.title);
  for (const id of home.hidden) add(id, byId.get(id) ?? titles[id] ?? rowLabel(id));
  return out;
}

/** Edit list → the `home` prefs to save (order = the visible rows as arranged). */
export function homeFrom(list: EditRow[], specialsPerDay: number): Prefs["home"] {
  return {
    pinned: list.filter((r) => r.pinned && !r.hidden).map((r) => r.id),
    hidden: list.filter((r) => r.hidden).map((r) => r.id),
    order: list.filter((r) => !r.hidden).map((r) => r.id),
    specialsPerDay: Math.max(0, Math.min(SPECIALS_MAX, specialsPerDay)),
  };
}

/** Move a visible row up (-1) or down (+1) among the visible rows. */
export function move(list: EditRow[], id: string, by: -1 | 1): EditRow[] {
  const vis = list.filter((r) => !r.hidden);
  const i = vis.findIndex((r) => r.id === id);
  const j = i + by;
  if (i < 0 || j < 0 || j >= vis.length) return list;
  [vis[i], vis[j]] = [vis[j], vis[i]];
  return [...vis, ...list.filter((r) => r.hidden)];
}

/** A readable label for a row id we have no title for ("actor:4003bb8c…" → "Actor row"). */
export function rowLabel(id: string): string {
  const kind = id.split(":")[0];
  return kind && kind !== id ? `${kind[0].toUpperCase()}${kind.slice(1).replace(/[-_]/g, " ")} row` : "A row";
}

// ---------- taste ----------

export interface Share {
  name: string;
  share: number;
}
export interface Taste {
  genres: Share[];
  directors: Share[];
  decades: Share[];
  countries: Share[];
  topRated: string[];
  watchedCount: number;
  hours: number;
}

const shares = (v: unknown): Share[] =>
  (Array.isArray(v) ? v : [])
    .map(obj)
    .filter((x) => typeof x.name === "string" && x.name && typeof x.share === "number" && isFinite(x.share) && x.share > 0)
    .map((x) => ({ name: x.name as string, share: Math.min(1, x.share as number) }))
    .sort((a, b) => b.share - a.share);

export function parseTaste(raw: unknown): Taste | null {
  const r = obj(raw);
  if (!Object.keys(r).length) return null;
  const n = (v: unknown) => (typeof v === "number" && isFinite(v) && v > 0 ? v : 0);
  return {
    genres: shares(r.genres),
    directors: shares(r.directors),
    decades: shares(r.decades),
    countries: shares(r.countries),
    topRated: strs(r.topRated).filter((id) => /^[0-9a-f]{32}$/i.test(id)),
    watchedCount: Math.round(n(r.watchedCount)),
    hours: n(r.hours),
  };
}

/** GET /v1/me/follows → person ids (accepts ["id"], [{personId}], {follows:[…]}). */
export function parseFollows(raw: unknown): string[] {
  const list = Array.isArray(raw) ? raw : Array.isArray(obj(raw).follows) ? (obj(raw).follows as unknown[]) : [];
  return [...new Set(list.map((x) => (typeof x === "string" ? x : obj(x).personId)).filter((x): x is string => typeof x === "string" && /^[0-9a-f]{32}$/i.test(x)))];
}

/** GET /v1/me/reactions → thumbs per item id and the not-interested ids (library ids only; tmdb:… keys are kept). */
export function parseReactions(raw: unknown): { thumbs: Record<string, 1 | -1>; notInterested: string[] } {
  const r = obj(raw);
  const thumbs: Record<string, 1 | -1> = {};
  for (const [id, v] of Object.entries(obj(r.thumbs))) if (v === 1 || v === -1) thumbs[id] = v;
  return { thumbs, notInterested: strs(r.notInterested) };
}
