// Game Centre (N9, spoiler-safe game destination). Pure; tested in web/test/gameCentre.test.ts.
// Followed teams' games from the upcoming-sport feed and the person's recordings, each with its ways to watch, and a
// title sanitiser so no score or result leaks through EPG strings. Scores are shown only after a reveal (or when the
// person's "Show scores (spoilers)" setting is on).
import { targetPath, liveExtrasQuery, type LiveExtras } from "./iptvPlay.ts";
import type { Fixture } from "./matchInfo.ts";
import type { Rec } from "./recs.ts";
import { parseQuality, type Quality } from "./sport.ts";
import type { UpEvent } from "./upcoming.ts";

// ---------- title sanitiser ----------

const VERBS = "beats?|beating|beaten(?: by)?|defeated|defeats?|bt\\.?|def\\.|d\\.|thrash(?:es|ed)?|stuns?|stunned|crush(?:es|ed)?|rout(?:s|ed)?|upsets?|edges?|edged|hammer(?:s|ed)?|overcomes?|overcame|outlast(?:s|ed)?|sink(?:s)?|sunk|oust(?:s|ed)?|dispatch(?:es|ed)?|sees? off|saw off|holds? off|lose|loses|lost|loss|falls?|fell|go(?:es)? down|went down";
const VERB_RE = new RegExp(`\\b(?:${VERBS})\\b`, "i");
const BRACKETED = /[(\[][^)\]]*(?:\d|\bFT\b|\bHT\b|\bAET\b|\bpens?\b|\bwon\b|\bwin\b|\bbeat\b|\bfinal score\b|\bresult)[^)\]]*[)\]]/i;
const RESULT_WORDS = /\b(?:full[-\s]?time|final\s+score|half[-\s]?time|ft|ht|aet|pens|penalties|won|wins?|winning|winners?|victory|triumph|result|results|scoreline|nil|zip|points?|goals?|wickets?|tries|runs|highlights?|recap)\b/i;
const STANDALONE_NUM = /(?<![\p{L}\p{N}])\d+(?![\p{L}\p{N}])/gu;

/** Programmes that are a digest, reaction or build-up around a game, not the game: kept off the screen (they carry or invite the result). */
export const isHighlightProgramme = (title: string, kind?: string | null): boolean =>
  kind === "highlights" || /\b(highlights?|recap|match of the day|the review|round[-\s]?up|best bits|goals? of the|post[-\s]?match|reactions?|press conference|analysis|reviews?|classic|re[-\s]?live|previews?|build[-\s]?up|studio)\b/i.test(title);

const tidy = (s: string) => s.replace(/\s{2,}/g, " ").trim();

const dedupe = (names: string[]) => [...new Map(names.map((n) => [norm(n), n])).values()];
const numbersIn = (names: string[]) => new Set(names.flatMap((n) => n.match(/\d+/g) ?? []));

/**
 * A title safe to show. Allow-list: a title shows as written only when it has no number outside the followed teams' own
 * names ("Schalke 04"), no result verb or word and no bracketed note. Otherwise it is rebuilt from the followed teams it
 * names alone: "Arsenal v Chelsea" (alphabetical, so the order never names the winner), "Arsenal game", or "Game".
 */
export function sanitizeTitle(raw: string, teams: string[] = []): string {
  const t = tidy(raw.normalize("NFKC"));
  const allow = numbersIn(teams);
  const extra = (t.match(STANDALONE_NUM) ?? []).some((n) => !allow.has(n));
  if (t && !extra && !VERB_RE.test(t) && !RESULT_WORDS.test(t) && !BRACKETED.test(t) && !/[-–—]\s*\d|\d\s*[-–—:]/.test(t.replace(/\b\d{4}\b/g, ""))) return t;
  const named = dedupe(teams.filter((n) => mentionsTeam(t, [n]))).sort((x, y) => x.localeCompare(y));
  if (named.length >= 2) return `${named[0]} v ${named[1]}`;
  return named.length === 1 ? `${named[0]} game` : "Game";
}

/** Whether sanitising changed the title (it carried a score, result or highlights marker). */
export const leaksResult = (raw: string, teams: string[] = []): boolean => sanitizeTitle(raw, teams) !== tidy(raw.normalize("NFKC"));

// ---------- the spoiler pref and the reveal gate ----------

/** Scores show only when the person turned "Show scores (spoilers)" on; the default (pref missing or false) is safe. */
export const spoilersOn = (prefs: { sport?: { spoilers?: boolean } } | null | undefined): boolean => prefs?.sport?.spoilers === true;

export const CONFIRM_MS = 4000;
export interface Gate {
  armedAt: number | null;
}
export const gateStart: Gate = { armedAt: null };

/** One press of "Reveal score": the first arms the gate, a second within CONFIRM_MS reveals; later presses re-arm. */
export function gatePress(g: Gate, now: number): { gate: Gate; revealed: boolean } {
  if (g.armedAt != null && now - g.armedAt <= CONFIRM_MS && now >= g.armedAt) return { gate: { armedAt: null }, revealed: true };
  return { gate: { armedAt: now }, revealed: false };
}
export const gateArmed = (g: Gate, now: number): boolean => g.armedAt != null && now - g.armedAt <= CONFIRM_MS;

/** Whether a game's score may show. */
export const scoreVisible = (pref: boolean, revealed: ReadonlySet<string>, key: string): boolean => pref || revealed.has(key);

/** "Warriors 24–18 Panthers" from a fixture, null when it carries no score. Called only after a reveal. */
export function scoreText(f: Fixture | null | undefined): string | null {
  const s = f?.score;
  if (!f || !s) return null;
  return `${f.home.short ?? f.home.name} ${s.home}–${s.away} ${f.away.short ?? f.away.name}`;
}

// ---------- games ----------

export type Phase = "upcoming" | "live" | "finished";
export type WayKind = "recording" | "catchup" | "live";
export interface Way {
  kind: WayKind;
  label: string;
  path: string;
}

export interface Game {
  key: string;
  /** Already sanitised. */
  title: string;
  league: string | null;
  start: number;
  stop: number;
  channel: string | null;
  event: UpEvent | null;
  rec: Rec | null;
  fixture: Fixture | null;
}

const norm = (s: string) => s.normalize("NFKC").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();

/** Does the text mention any followed team (whole-word, case-insensitive)? */
export function mentionsTeam(text: string, teams: string[]): boolean {
  const t = ` ${norm(text)} `;
  return teams.some((n) => {
    const x = norm(n);
    return x.length >= 3 && t.includes(` ${x} `);
  });
}

const eventText = (e: UpEvent) => [e.title, e.fixture?.home.name, e.fixture?.away.name, ...(e.fixture?.participants ?? [])].filter(Boolean).join(" | ");

function gameTitle(raw: string, f: Fixture | null | undefined, teams: string[]): string {
  if (f && f.kind === "match" && f.home.name && f.away.name) return `${f.home.name} v ${f.away.name}`;
  return sanitizeTitle(raw, teams);
}

/** A recording title that reads as a fixture: "A v B", "A vs B", "A at B". */
const GAME_SEP = /\s(?:v|vs\.?|versus|at|@)\s/i;
const SAME_SLOT_S = 15 * 60;
export const WINDOW_DAYS = 4;

/** Followed teams' games (events from the guide, plus recordings the guide no longer lists), newest day order is the caller's. */
export function buildGames(events: UpEvent[] | null, recs: Rec[] | null, teams: string[], now: number): Game[] {
  const out: Game[] = [];
  const usedRecs = new Set<string>();
  const from = now - WINDOW_DAYS * 86400;
  const leagues = [...new Set((events ?? []).map((e) => e.fixture?.league ?? e.league).filter((l): l is string => !!l))];
  const eligible = (events ?? []).filter((e) => e.stop >= from && !isHighlightProgramme(e.title, e.kind) && (e.favourite || mentionsTeam(eventText(e), teams)));
  // A recording belongs to the event that names it; failing that, to one that airs in the same slot on the same channel (first come)
  const claimed = new Map<string, Rec>();
  for (const e of eligible) {
    const r = (recs ?? []).find((x) => e.recordingId != null && String(x.id) === String(e.recordingId) && ![...claimed.values()].includes(x));
    if (r) claimed.set(e.key, r);
  }
  for (const e of eligible) {
    if (claimed.has(e.key)) continue;
    const r = (recs ?? []).find((x) => x.channelId === e.channels[0]?.id && Math.abs(x.start - e.start) <= SAME_SLOT_S && mentionsTeam(x.title, teams) && ![...claimed.values()].includes(x));
    if (r) claimed.set(e.key, r);
  }
  for (const e of eligible) {
    const rec = claimed.get(e.key) ?? null;
    if (rec) usedRecs.add(String(rec.id));
    out.push({ key: `e:${e.key}`, title: gameTitle(e.title, e.fixture, teams), league: e.league ?? e.fixture?.league ?? null, start: e.start, stop: e.stop, channel: e.channels[0]?.name ?? null, event: e, rec, fixture: e.fixture ?? null });
  }
  for (const r of recs ?? []) {
    if (usedRecs.has(String(r.id)) || (r.state !== "done" && r.state !== "recording")) continue;
    if (r.stop < from || isHighlightProgramme(r.title) || !mentionsTeam(r.title, teams) || !(GAME_SEP.test(r.title) || leagues.some((l) => mentionsTeam(r.title, [l])))) continue;
    out.push({ key: `r:${r.id}`, title: sanitizeTitle(r.title, teams), league: null, start: r.start, stop: r.stop, channel: r.channel || null, event: null, rec: r, fixture: null });
  }
  return out;
}

export function phaseOf(g: Pick<Game, "start" | "stop" | "rec" | "event">, now: number): Phase {
  const startsAt = g.event?.startsAt && g.event.phase === "pre" ? g.event.startsAt : null;
  if (now < (startsAt ?? g.start)) return "upcoming";
  if (now < g.stop) return "live";
  return g.rec?.state === "recording" ? "live" : "finished";
}

const hm = (t: number) => new Date(t * 1000).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();

/** "Starts 19:30" today, "Starts Sat 19:30" otherwise. */
export function startsText(start: number, now: number): string {
  const d = new Date(start * 1000);
  const day = sameDay(d, new Date(now * 1000)) ? "" : `${d.toLocaleDateString("en-GB", { weekday: "short" })} `;
  return `Starts ${day}${hm(start)}`;
}

/** "40 min ago", "1 h 5 min ago". */
export function agoText(secs: number): string {
  const m = Math.max(1, Math.round(secs / 60));
  return m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ""} ago` : `${m} min ago`;
}

const extrasOf = (g: Game, via: string): LiveExtras => ({ nospoil: true, alternates: (g.event?.channels ?? []).filter((c) => c.id !== via).map((c) => ({ id: c.id, name: c.name })) });

export interface Ways {
  phase: Phase;
  /** Best first; every "from the start" way opens at position 0. */
  ways: Way[];
  /** The status line under the title. Never a score. */
  note: string;
}

/** The ways to watch a game and the line that says so. Only what the APIs returned. */
export function waysFor(g: Game, now: number): Ways {
  const phase = phaseOf(g, now);
  const t = encodeURIComponent(g.title);
  const best = g.event?.channels[0] ?? null;
  const ways: Way[] = [];
  if (g.rec && g.rec.playable !== false && (g.rec.state === "done" || g.rec.state === "recording"))
    ways.push({ kind: "recording", label: "Your recording, from the start", path: `${targetPath({ recording: String(g.rec.id) })}&t=${t}${liveExtrasQuery({ nospoil: true })}` });
  if (g.event?.startOver && best && phase !== "upcoming")
    ways.push({ kind: "catchup", label: `Catch-up on ${best.name}, from the start`, path: `${targetPath({ channel: best.id, start: g.start, stop: g.stop })}&t=${t}${liveExtrasQuery(extrasOf(g, best.id))}` });
  if (phase === "live" && best) ways.push({ kind: "live", label: `Live on ${best.name}`, path: `${targetPath({ channel: best.id })}&t=${t}${liveExtrasQuery(extrasOf(g, best.id))}` });
  const fromStart = ways.some((w) => w.kind !== "live");
  let note: string;
  if (phase === "upcoming") note = startsText(g.event?.startsAt && g.event.phase === "pre" ? g.event.startsAt : g.start, now);
  else if (phase === "live") note = fromStart ? `Live${best ? ` on ${best.name}` : ""} — watch from the start` : `Live only — started ${agoText(now - g.start)}`;
  else note = fromStart ? "Finished — watch from the start" : "Finished — no replay available";
  return { phase, ways, note };
}

/** Day sections: today first, then later days (soonest first), then earlier days (newest first). Inside a day: live, upcoming by start, finished by start. */
export function sectionGames(games: Game[], now: number): { day: string; label: string; games: Game[] }[] {
  const dayStart = (t: number) => new Date(new Date(t * 1000).setHours(0, 0, 0, 0)).getTime();
  const base = dayStart(now);
  const days = new Map<number, Game[]>();
  for (const g of games) {
    const k = dayStart(g.start);
    days.set(k, [...(days.get(k) ?? []), g]);
  }
  const rank = (g: Game) => ({ live: 0, upcoming: 1, finished: 2 })[phaseOf(g, now)];
  const label = (k: number) => {
    const d = new Date(k);
    const diff = Math.round((k - base) / 86_400_000);
    if (diff === 0) return "Today";
    if (diff === 1) return "Tomorrow";
    if (diff === -1) return "Yesterday";
    return d.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "short" });
  };
  const order = (k: number) => (k === base ? -Infinity : k > base ? k : Infinity);
  return [...days.entries()]
    .sort((a, b) => {
      const [oa, ob] = [order(a[0]), order(b[0])];
      if (oa !== ob) return oa < ob ? -1 : 1;
      return b[0] - a[0];
    })
    .map(([k, gs]) => ({ day: String(k), label: label(k), games: [...gs].sort((a, b) => rank(a) - rank(b) || a.start - b.start) }));
}

// ---------- fixture-first Game Centre (plan: ~/ai/plans/jellytip-sport-companion.md §2.5, T0.2) ----------
//
// CONTRACT coded against: GET v1/iptv/sport/fixtures?from=<epoch s>&to=<epoch s>&limit=<n>
//   -> { items: FixtureItem[], degraded?: boolean }     (404 = server without the route -> legacy path)
// FixtureItem (everything not marked required is optional; unusable items are dropped, never guessed at):
//   fid         string, required, stable fixture id
//   league      string|null display name ("NBA"); leagueKey string|null ("nba"); sport string|null
//   home, away  required: string | { name, short?, logo?, teamId? }   (an event with no sides may send `title` instead)
//   kickoff     required: epoch SECONDS (number); an ISO-8601 string is tolerated
//   status      required: "pre" | "in" | "post"
//   stage       string|null ("Semi-final")
//   score       { home, away, clock?, period? } | null     null unless the person's spoilers pref is on; shown only past the gate
//   interest, significance, rank   numbers (rank 1 = first); the server's order is `rank`
//   favourite   boolean, decided by the server from teamRef ids; the client never matches titles or names for it
//   autoRecord  boolean, the server will record this fixture by itself (default false); a channel's own autoRecord overrides it
//   channels    [{ id, name, num?, logo?, quality?, confidence 0-1, why?, region?, lang?, maybe?, dormant?, start?, stop?, startOver?, autoRecord? }]
//               maybe = "may carry this" (server's call; absent -> confidence < 0.8); dormant = league feed "may be live";
//               start/stop = the programme's epoch s; startOver = catch-up exists, plays from `start` (needs start and stop)
//   best        channel id | null (default: channels[0])
//   reminderId?, recordingId?, matchId?
// Order on screen: live first, then favourites, then significance; finished last.

export interface FxChannel {
  id: string;
  name: string;
  num: number | null;
  logo: string | null;
  quality: Quality | null;
  confidence: number;
  why: string | null;
  region: string | null;
  lang: string | null;
  maybe: boolean;
  dormant: boolean;
  start: number | null;
  stop: number | null;
  startOver: boolean;
  autoRecord: boolean | null;
}

export interface FxItem {
  fid: string;
  league: string | null;
  leagueKey: string | null;
  sport: string | null;
  home: string;
  away: string;
  homeId: string | null;
  awayId: string | null;
  homeShort: string | null;
  awayShort: string | null;
  homeLogo: string | null;
  awayLogo: string | null;
  kickoff: number;
  status: "pre" | "in" | "post";
  stage: string | null;
  score: { home: number; away: number; clock: string | null; period: string | null } | null;
  interest: number;
  significance: number;
  rank: number | null;
  favourite: boolean;
  autoRecord: boolean;
  channels: FxChannel[];
  best: string | null;
  reminderId: string | number | null;
  recordingId: string | number | null;
  matchId: string | null;
}

/** Fallback when the server sends no `maybe`: a link below this confidence is a guess. */
export const MAYBE_BELOW = 0.8;
const o = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const s = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : null);
const n = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : null);
const idOf = (v: unknown) => (typeof v === "string" && v ? v : typeof v === "number" && isFinite(v) ? String(v) : null);

function fxSide(v: unknown): { name: string; id: string | null; logo: string | null; short: string | null } | null {
  if (typeof v === "string") return s(v) ? { name: s(v)!, id: null, logo: null, short: null } : null;
  const x = o(v);
  const name = s(x?.name) ?? s(x?.short);
  return x && name ? { name, id: idOf(x.teamId), logo: s(x.logo), short: s(x.short) } : null;
}

function fxChannel(raw: unknown): FxChannel | null {
  const c = o(raw);
  const id = idOf(c?.id);
  if (!c || !id) return null;
  const conf = n(c.confidence);
  return {
    id,
    name: s(c.name) ?? "Channel",
    num: n(c.num),
    logo: s(c.logo),
    quality: parseQuality(c.quality),
    // A link with no stated confidence is not trusted as a certain one
    confidence: conf == null ? 0.5 : Math.min(1, Math.max(0, conf)),
    why: s(c.why),
    region: s(c.region),
    lang: s(c.lang),
    maybe: typeof c.maybe === "boolean" ? c.maybe : (conf == null ? 0.5 : conf) < MAYBE_BELOW,
    dormant: c.dormant === true,
    start: n(c.start),
    stop: n(c.stop),
    startOver: c.startOver === true,
    autoRecord: typeof c.autoRecord === "boolean" ? c.autoRecord : null,
  };
}

function kickoffOf(v: unknown): number | null {
  const x = n(v);
  if (x != null) return x > 1e11 ? Math.floor(x / 1000) : x; // tolerate milliseconds
  const t = typeof v === "string" ? Date.parse(v) : NaN;
  return isFinite(t) ? Math.floor(t / 1000) : null;
}

/** Answer -> fixture items. Items without an id, sides, kickoff or status are dropped; duplicate ids keep the first. */
export function parseFixtureList(raw: unknown): { items: FxItem[]; degraded: boolean } {
  const r = o(raw);
  const list = Array.isArray(raw) ? raw : Array.isArray(r?.items) ? (r!.items as unknown[]) : [];
  const out: FxItem[] = [];
  const seen = new Set<string>();
  for (const x of list) {
    const e = o(x);
    const fid = idOf(e?.fid);
    const kickoff = kickoffOf(e?.kickoff);
    const status = e?.status === "pre" || e?.status === "in" || e?.status === "post" ? e.status : null;
    const title = s(e?.title);
    const home = fxSide(e?.home) ?? (title ? { name: title, id: null, logo: null, short: null } : null);
    const away = fxSide(e?.away) ?? (title ? { name: "", id: null, logo: null, short: null } : null);
    if (!e || !fid || kickoff == null || !status || !home || !away || seen.has(fid)) continue;
    seen.add(fid);
    const sc = o(e.score);
    const channels = (Array.isArray(e.channels) ? e.channels : []).map(fxChannel).filter((c): c is FxChannel => !!c);
    out.push({
      fid,
      league: s(e.league),
      leagueKey: s(e.leagueKey),
      sport: s(e.sport),
      home: home.name,
      away: away.name,
      homeId: home.id,
      awayId: away.id,
      homeShort: home.short,
      awayShort: away.short,
      homeLogo: home.logo,
      awayLogo: away.logo,
      kickoff,
      status,
      stage: s(e.stage),
      score: sc && n(sc.home) != null && n(sc.away) != null ? { home: n(sc.home)!, away: n(sc.away)!, clock: s(sc.clock), period: s(sc.period) } : null,
      interest: n(e.interest) ?? 0,
      significance: n(e.significance) ?? 0,
      rank: n(e.rank),
      favourite: e.favourite === true,
      autoRecord: e.autoRecord === true,
      channels,
      best: idOf(e.best),
      reminderId: idOf(e.reminderId),
      recordingId: idOf(e.recordingId),
      matchId: idOf(e.matchId),
    });
  }
  return { items: out, degraded: r?.degraded === true };
}

export const fxTitle = (f: Pick<FxItem, "home" | "away">): string => (f.away ? `${f.home} v ${f.away}` : f.home);

/**
 * Favourite = the server's flag, or one of the sides' team ids is a followed teamRef (leagues compared when both sides of
 * the comparison state one). Never title or name text: "Warriors" must not make a Golden State fixture an NRL favourite.
 */
export function isFavourite(f: Pick<FxItem, "favourite" | "homeId" | "awayId" | "leagueKey">, refs: { teamId: string; leagueKey?: string | null }[]): boolean {
  if (f.favourite) return true;
  return refs.some((r) => [f.homeId, f.awayId].includes(r.teamId) && (!r.leagueKey || !f.leagueKey || r.leagueKey === f.leagueKey));
}

export type FxGroupId = "live" | "yours" | "big" | "finished";
export interface FxGroup {
  id: FxGroupId;
  label: string;
  items: FxItem[];
}

/** Live first, then favourites, then the server rank, then significance (then kickoff); finished games last. */
export function groupFixtures(items: FxItem[], refs: { teamId: string; leagueKey?: string | null }[] = []): FxGroup[] {
  const fav = new Map(items.map((f) => [f.fid, isFavourite(f, refs)]));
  const cmp = (a: FxItem, b: FxItem) =>
    Number(fav.get(b.fid)) - Number(fav.get(a.fid)) ||
    (a.rank ?? Infinity) - (b.rank ?? Infinity) ||
    b.significance - a.significance ||
    a.kickoff - b.kickoff ||
    a.fid.localeCompare(b.fid);
  const pick = (f: (x: FxItem) => boolean) => items.filter(f).sort(cmp);
  const groups: FxGroup[] = [
    { id: "live", label: "Live now", items: pick((x) => x.status === "in") },
    { id: "yours", label: "Your teams", items: pick((x) => x.status === "pre" && !!fav.get(x.fid)) },
    { id: "big", label: "More games", items: pick((x) => x.status === "pre" && !fav.get(x.fid)) },
    { id: "finished", label: "Finished", items: pick((x) => x.status === "post").sort((a, b) => b.kickoff - a.kickoff) },
  ];
  return groups.filter((g) => g.items.length > 0);
}

/** A real link: not a "may carry this" guess, not a dormant league feed. */
export const isSolid = (c: FxChannel): boolean => !c.maybe && !c.dormant;
const BUCKET = ["uhd", "fhd50", "fhd", "hd", "unknown", "sd", "slow", "dead"];
const bucketRank = (c: FxChannel) => {
  const i = BUCKET.indexOf(c.quality?.bucket ?? "unknown");
  return i < 0 ? 4 : i;
};
/** The channel to open: the server's `best` when it is a solid link, else solid links first, then quality bucket, dormant last. */
export function bestChannel(f: Pick<FxItem, "channels" | "best">): FxChannel | null {
  const given = f.channels.find((c) => c.id === f.best);
  if (given && isSolid(given)) return given;
  const ranked = f.channels
    .map((c, i) => ({ c, i }))
    .sort((a, b) => Number(a.c.dormant) - Number(b.c.dormant) || Number(a.c.maybe) - Number(b.c.maybe) || bucketRank(a.c) - bucketRank(b.c) || a.i - b.i);
  return ranked[0]?.c ?? null;
}

export interface FxWays {
  phase: Phase;
  ways: Way[];
  /** The status line under the title. Never a score. */
  note: string;
  /** No usable link: the card says so instead of hiding. */
  noStream: boolean;
  /** "Record on X": a not-finished fixture, no recording, the server not auto-recording it, and a channel with a known programme start that is not dormant. */
  canRecord: boolean;
  /** The channel that button records (null when canRecord is false). */
  recordOn: FxChannel | null;
}

/** The ways to watch a fixture and the line that says so. Only what the API returned; every path is spoiler-free. */
export function waysForFixture(f: FxItem, rec: Rec | null, now: number): FxWays {
  const phase: Phase = f.status === "in" ? "live" : f.status === "post" ? "finished" : "upcoming";
  const t = encodeURIComponent(fxTitle(f));
  const best = bestChannel(f);
  const solid = best ? isSolid(best) : false;
  const ways: Way[] = [];
  if (rec && rec.playable !== false && (rec.state === "done" || rec.state === "recording"))
    ways.push({ kind: "recording", label: "Your recording, from the start", path: `${targetPath({ recording: String(rec.id) })}&t=${t}${liveExtrasQuery({ nospoil: true })}` });
  if (best?.startOver && best.start != null && best.stop != null && best.stop > best.start && phase !== "upcoming" && !best.dormant)
    ways.push({ kind: "catchup", label: `Catch-up on ${best.name}, from the start`, path: `${targetPath({ channel: best.id, start: best.start, stop: best.stop })}&t=${t}${liveExtrasQuery({ nospoil: true, matchId: f.matchId ?? f.fid, alternates: f.channels.filter((c) => c.id !== best.id).map((c) => ({ id: c.id, name: c.name })) })}` });
  if (phase === "live" && best) {
    const alternates = f.channels.filter((c) => c.id !== best.id).map((c) => ({ id: c.id, name: c.name }));
    ways.push({ kind: "live", label: `${solid ? "Live on" : "May be live on"} ${best.name}`, path: `${targetPath({ channel: best.id })}&t=${t}${liveExtrasQuery({ matchId: f.matchId ?? f.fid, alternates })}` });
  }
  const noStream = phase !== "finished" && !best;
  let note: string;
  if (phase === "upcoming") note = best ? `${startsText(f.kickoff, now)} · ${solid ? best.name : `may be on ${best.name}`}` : `${startsText(f.kickoff, now)} · No stream found yet`;
  else if (phase === "live") note = !best ? "Live · No stream found yet" : solid ? `Live on ${best.name}` : `Live · may be on ${best.name}`;
  else note = ways.length ? "Finished — watch from the start" : "Finished — no replay available";
  const auto = best?.autoRecord ?? f.autoRecord;
  const recordOn = phase !== "finished" && best && best.start != null && !best.dormant && !auto && f.recordingId == null && !rec ? best : null;
  return { phase, ways, note, noStream, canRecord: !!recordOn, recordOn };
}

/** "Warriors 24–18 Panthers"-style line for a fixture score; null when none. Called only after the gate. */
export function fxScoreText(f: Pick<FxItem, "home" | "away" | "score">): string | null {
  return f.score ? `${f.home} ${f.score.home}–${f.score.away} ${f.away}` : null;
}

/** The legacy (upcoming-feed) Game Centre stays one release behind localStorage jt.gcLegacy=1; a 404 from the new route also falls back. */
export const legacyForced = (get: (k: string) => string | null): boolean => get("jt.gcLegacy") === "1";
