// "Pick for tonight" (#/tonight): three things to watch, each with one factual reason. Pure and deterministic; tested in
// web/test/tonight.test.ts. Candidates come in priority order (resume with 10+ min left, next episode of a show in
// progress, unwatched films from the companion's rows, unwatched highly rated films, IPTV films only when the rest is
// thin). Constraint chips reuse lib/askParse.ts (parseAsk + filterItems + the kids rule); nothing is invented.
import type { Item } from "../api/jellyfin.ts";
import type { RowEntry } from "./continueRow.ts";
import type { VodItem } from "./iptvVod.ts";
import { continuePath } from "./continue.ts";
import { filterItems, parseAsk, type Chip } from "./askParse.ts";

export const PICKS = 3;
export const MIN_LEFT_MIN = 10;
export const HIGH_RATING = 7.5;
/** IPTV titles are added only when fewer than this many candidates remain from the library and Continue Watching. */
export const THIN = PICKS * 2;

export type Source = "resume" | "nextup" | "recs" | "rated" | "iptv";
const TIER: Record<Source, number> = { resume: 0, nextup: 1, recs: 2, rated: 3, iptv: 4 };

export interface Cand {
  /** Undashed library id, or the IPTV id. */
  key: string;
  source: Source;
  title: string;
  /** "S2 E5 · Name" for an episode. */
  sub: string | null;
  reason: string;
  /** Route to play it (resumes where it was). */
  play: string;
  playLabel: "Play" | "Resume";
  /** Library id for the poster, or an IPTV poster url. */
  artId: string | null;
  poster: string | null;
  /** Minutes the sitting takes (time left for a resume); null = unknown. */
  minutes: number | null;
  /** Pseudo-item for askParse's filters (genres, length, rating). */
  probe: Item;
  /** Already begun (resume / next episode of a show in progress). */
  started: boolean;
  /** Undashed series id of an episode (a blocked series blocks its episodes). */
  series?: string;
  /** Order within its tier. */
  rank: number;
}

const undash = (s: string) => s.replace(/-/g, "");
const TICKS_MIN = 600_000_000;
const rt = (m: number | null) => (m == null ? undefined : Math.round(m * TICKS_MIN));
const se = (s?: number, e?: number) => (s != null && e != null ? `S${s} E${e}` : e != null ? `E${e}` : null);
const mins = (s: number) => Math.max(1, Math.round(s / 60));
/** Under two minutes in is not worth quoting. */
const into = (pos: number) => (pos < 120 ? "Started recently" : `You're ${mins(pos)} min into this`);

/** Where the client-side ranking gets its pool (all already loaded; see screens/Tonight.tsx). */
export interface Pool {
  entries: RowEntry[];
  /** Companion recommendation rows: films with the row they came from. */
  recs: { item: Item; from: { title: string; list?: string | null } }[];
  rated: Item[];
  iptv: VodItem[];
  /** Keys hidden in Continue Watching ("lib:<id>" / "iptv:<kind>:<id>") and not-interested ids (undashed). */
  hidden: Set<string>;
  blocked: Set<string>;
}

const itemProbe = (it: Item, minutes: number | null): Item => ({ ...it, RunTimeTicks: rt(minutes) ?? it.RunTimeTicks });

function fromEntry(e: RowEntry): Cand | null {
  if (e.lib) {
    const it = e.lib;
    const ep = it.Type === "Episode";
    const left = e.dur > 0 ? Math.max(0, e.dur - e.pos) / 60 : null;
    if (!e.next && (left == null || left < MIN_LEFT_MIN)) return null;
    const label = ep ? se(it.ParentIndexNumber, it.IndexNumber) : null;
    return {
      key: undash(it.Id),
      source: e.next ? "nextup" : "resume",
      title: ep ? (it.SeriesName ?? it.Name) : it.Name,
      sub: ep ? [label, it.Name].filter(Boolean).join(" · ") : null,
      reason: e.next ? `Next episode of ${it.SeriesName ?? it.Name}` : into(e.pos),
      play: `/play/${it.Id}`,
      playLabel: e.next ? "Play" : "Resume",
      artId: ep ? (it.SeriesId ?? it.Id) : it.Id,
      poster: null,
      minutes: e.next ? (it.RunTimeTicks ? it.RunTimeTicks / TICKS_MIN : null) : left,
      probe: itemProbe(it, e.next ? (it.RunTimeTicks ? it.RunTimeTicks / TICKS_MIN : null) : left),
      started: true,
      series: ep && it.SeriesId ? undash(it.SeriesId) : undefined,
      rank: 0,
    };
  }
  const v = e.iptv;
  if (!v || v.kind === "recording") return null;
  const left = v.dur > 0 ? Math.max(0, v.dur - v.pos) / 60 : null;
  if (!e.next && (left == null || left < MIN_LEFT_MIN)) return null;
  return {
    key: `iptv:${v.kind}:${v.id}`,
    source: e.next ? "nextup" : "resume",
    title: v.seriesTitle ?? v.title,
    sub: v.kind === "episode" ? [se(v.season ?? undefined, v.episode ?? undefined), v.title].filter(Boolean).join(" · ") : null,
    reason: e.next ? `Next episode of ${v.seriesTitle ?? v.title}` : into(v.pos),
    play: continuePath(v),
    playLabel: e.next ? "Play" : "Resume",
    artId: null,
    poster: v.poster,
    minutes: e.next ? (v.dur > 0 ? v.dur / 60 : null) : left,
    probe: { Id: v.id, Name: v.title, Type: v.kind === "movie" ? "Movie" : "Episode", RunTimeTicks: rt(e.next ? (v.dur > 0 ? v.dur / 60 : null) : left) } as Item,
    started: true,
    rank: 0,
  };
}

/** Every candidate from the pool, unfiltered by chips, each title once, nothing watched, hidden or blocked. */
export function candidates(pool: Pool): Cand[] {
  const out: Cand[] = [];
  const seen = new Set<string>();
  const add = (c: Cand | null) => {
    if (!c || seen.has(c.key) || pool.blocked.has(c.key) || (c.series && pool.blocked.has(c.series))) return;
    seen.add(c.key);
    out.push(c);
  };
  // Continue Watching already left out hidden and finished tiles; entries keep their row order (newest played first)
  pool.entries.forEach((e, i) => {
    const c = fromEntry(e);
    if (c) add({ ...c, rank: i });
  });
  const hiddenLib = (it: Item) => pool.hidden.has(`lib:${undash(it.Id)}`);
  const fresh = (it: Item) => it.Type === "Movie" && it.UserData?.Played !== true && !(it.UserData?.PlaybackPositionTicks ?? 0) && !hiddenLib(it);
  const film = (it: Item, source: "recs" | "rated", reason: string, rank: number): Cand => {
    const m = it.RunTimeTicks ? it.RunTimeTicks / TICKS_MIN : null;
    return { key: undash(it.Id), source, title: it.Name, sub: it.ProductionYear ? String(it.ProductionYear) : null, reason, play: `/play/${it.Id}`, playLabel: "Play", artId: it.Id, poster: null, minutes: m, probe: it, started: false, rank };
  };
  pool.recs.forEach((r, i) => {
    if (fresh(r.item)) add(film(r.item, "recs", r.from.list ? `On your Letterboxd list "${r.from.list}"` : `In your "${r.from.title}" row`, i));
  });
  [...pool.rated]
    .filter((it) => (it.CommunityRating ?? 0) >= HIGH_RATING)
    .sort((a, b) => (b.CommunityRating ?? 0) - (a.CommunityRating ?? 0) || a.Name.localeCompare(b.Name))
    .forEach((it, i) => fresh(it) && add(film(it, "rated", `Rated ${(it.CommunityRating as number).toFixed(1)}, unwatched`, i)));
  const iptvOpen = out.length < THIN;
  if (iptvOpen) {
    pool.iptv
      .filter((v) => v.kind === "movie" && !v.played && !(v.progress && v.progress.position > 30) && !pool.hidden.has(`iptv:movie:${v.id}`))
      .forEach((v, i) =>
        add({
          key: `iptv:movie:${v.id}`,
          source: "iptv",
          title: v.title,
          sub: v.year ? String(v.year) : null,
          reason: v.rating ? `Rated ${v.rating.toFixed(1)} on ${v.providerName ?? "your IPTV"}` : `On ${v.providerName ?? "your IPTV"}`,
          play: `/iptv/play?vod=${encodeURIComponent(v.id)}&t=${encodeURIComponent(v.title)}`,
          playLabel: "Play",
          artId: null,
          poster: v.poster ?? v.backdrop ?? null,
          minutes: null,
          probe: { Id: v.id, Name: v.title, Type: "Movie", Genres: v.category ? [v.category] : [] } as Item,
          started: false,
          rank: i,
        }),
      );
  }
  return out;
}

// ---------- constraints ----------

export type ConstraintId = "short" | "light" | "new" | "kids";
export const CONSTRAINTS: { id: ConstraintId; label: string; ask: string }[] = [
  { id: "short", label: "Under 2 hours", ask: "under 2 hours" },
  { id: "light", label: "Something light", ask: "feel-good" },
  { id: "new", label: "Something new to me", ask: "I haven't seen" },
  { id: "kids", label: "Kids around", ask: "for kids" },
];

/** The askParse chips for the chosen constraints (same phrases the Ask page reads). */
export function chipsFor(ids: ConstraintId[]): Chip[] {
  return CONSTRAINTS.filter((c) => ids.includes(c.id)).flatMap((c) => parseAsk(c.ask).chips);
}

/** Candidates that fit every chosen constraint. Unknown length / genre / rating never passes a constraint that needs it. */
export function applyConstraints(cands: Cand[], ids: ConstraintId[]): Cand[] {
  if (!ids.length) return cands;
  const chips = chipsFor(ids);
  const kids = chips.find((c) => c.data.t === "kids");
  const wantNew = chips.some((c) => c.data.t === "unplayed");
  const fits = new Set(filterItems(cands.map((c) => c.probe), chips.filter((c) => c.data.t === "genre" || c.data.t === "maxRuntime")).map((i) => i.Id));
  return cands.filter((c) => {
    if (wantNew && c.started) return false;
    if (!fits.has(c.probe.Id)) return false;
    if (kids && kids.data.t === "kids") {
      const r = c.probe.OfficialRating;
      if (!r || !kids.data.ratings.includes(r)) return false;
      // Always a Family / Animation / Kids genre as well: a PG comedy alone is not kids-safe
      const have = new Set((c.probe.Genres ?? []).map((g) => g.toLowerCase()));
      if (!kids.data.genres.some((g) => have.has(g.toLowerCase()))) return false;
    }
    return true;
  });
}

/** Priority order: tier, then the rank inside it; ties keep input order (stable). */
export const ranked = (cands: Cand[]): Cand[] => cands.map((c, i) => ({ c, i })).sort((a, b) => TIER[a.c.source] - TIER[b.c.source] || a.c.rank - b.c.rank || a.i - b.i).map((x) => x.c);

/**
 * The next three: the best-ranked candidates that fit, none of which was shown before for this set of constraints
 * (`shown`, updated here). Fewer than three come back when that is all there is.
 */
export function nextThree(cands: Cand[], ids: ConstraintId[], shown: Set<string>): Cand[] {
  const fit = ranked(applyConstraints(cands, ids)).filter((c) => !shown.has(c.key));
  const out = fit.slice(0, PICKS);
  out.forEach((c) => shown.add(c.key));
  return out;
}

/** Session memory of what was shown, per set of constraints (a different set starts fresh). */
export function shownFor(store: Map<string, Set<string>>, ids: ConstraintId[]): Set<string> {
  const k = [...ids].sort().join("+");
  let s = store.get(k);
  if (!s) store.set(k, (s = new Set()));
  return s;
}
