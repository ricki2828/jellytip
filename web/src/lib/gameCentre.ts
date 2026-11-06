// Game Centre (N9, spoiler-safe game destination). Pure; tested in web/test/gameCentre.test.ts.
// Followed teams' games from the upcoming-sport feed and the person's recordings, each with its ways to watch, and a
// title sanitiser so no score or result leaks through EPG strings. Scores are shown only after a reveal (or when the
// person's "Show scores (spoilers)" setting is on).
import { targetPath, liveExtrasQuery, type LiveExtras } from "./iptvPlay.ts";
import type { Fixture } from "./matchInfo.ts";
import type { Rec } from "./recs.ts";
import type { UpEvent } from "./upcoming.ts";

// ---------- title sanitiser ----------

const VERBS = "beats?|beating|beaten by|defeated|defeats?|bt\\.?|def\\.|d\\.|thrash(?:es|ed)?|stuns?|stunned|crush(?:es|ed)?|rout(?:s|ed)?|upsets?|edges? past|edged|edge out|hammer(?:s|ed)?|overcomes?|overcame|outlast(?:s|ed)?|sink(?:s)?|sunk|oust(?:s|ed)?|dispatch(?:es|ed)?|see off|sees off|saw off|hold off|holds off";
const BEAT_RE = new RegExp(`^(.{2,50}?)\\s+(?:${VERBS})\\s+(.{2,50}?)$`, "i");
const NUM_SCORE = /(?<![\d:.\/-])\d{1,3}\s*[-–—]\s*\d{1,3}(?![\d:.\/-])/g;
const BRACKETED = /[(\[][^)\]]*(?:\d+\s*[-–—:]\s*\d+|\bFT\b|\bHT\b|\bAET\b|\bpens?\b|\bwon\b|\bwin\b|\bbeat\b|\bfinal score\b|\bresult)[^)\]]*[)\]]/gi;
const MARGIN = /\bby\s+\d+\s+(?:points?|pts|goals?|tries|runs?|wickets?|games?|sets?|lengths?|strokes?|seconds?)\b/gi;
const NIL = /\b(?:\w+[-\s])?(?:nil|zip)(?:[-\s]\w+)?\b(?=\s*(?:draw|win|victory|result|$|[)\]:,.]))/gi;
const WORDS = /\b(?:full[-\s]?time(?:\s+score)?|final\s+score|half[-\s]?time\s+score|ft|ht|aet|won|wins?|winning|winners?|victory|victories|triumph|triumphs|loses|lost|losses|defeat|result|results|scoreline|highlights?|recap|goals? of the (?:game|match)|man of the match|player ratings)\b/gi;

/** Programmes that are a digest of a game, not the game: kept off the screen altogether (they carry the result). */
export const isHighlightProgramme = (title: string, kind?: string | null): boolean =>
  kind === "highlights" || /\b(highlights?|recap|match of the day|the review|round[-\s]?up|best bits|goals? of the)\b/i.test(title);

const tidy = (s: string) =>
  s
    .replace(/\(\s*\)|\[\s*\]/g, "")
    .replace(/\s{2,}/g, " ")
    .replace(/\s+([,.:;)])/g, "$1")
    .replace(/([:;])\s*[-–—,:;]+\s*/g, "$1 ")
    .replace(/\s*[-–—|:,]\s*([-–—|:,]\s*)+/g, " - ")
    .replace(/^[\s:–—|,-]+|[\s:–—|,-]+$/g, "")
    .trim();

/** A title safe to show: scores, results, winners and "highlights" taken out. "A beat B" becomes "A v B" in alphabetical order (the order must not name the winner). */
export function sanitizeTitle(raw: string): string {
  let t = raw.normalize("NFKC");
  t = t.replace(BRACKETED, " ").replace(NUM_SCORE, " ").replace(MARGIN, " ").replace(NIL, " ");
  t = tidy(t);
  // "League: A beat B" keeps its prefix
  const colon = t.match(/^([^:]{2,40}:\s*)(.+)$/);
  const [prefix, body] = colon && BEAT_RE.test(colon[2]) ? [colon[1], colon[2]] : ["", t];
  const m = body.match(BEAT_RE);
  if (m) {
    const [a, b] = [tidy(m[1].replace(WORDS, " ")), tidy(m[2].replace(WORDS, " "))].sort((x, y) => x.localeCompare(y));
    t = `${prefix}${a} v ${b}`;
  }
  t = tidy(t.replace(WORDS, " "));
  return t || "Game";
}

/** Whether sanitising changed the title (it carried a score, result or highlights marker). */
export const leaksResult = (raw: string): boolean => sanitizeTitle(raw) !== tidy(raw.normalize("NFKC"));

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

function gameTitle(raw: string, f: Fixture | null | undefined): string {
  if (f && f.kind === "match" && f.home.name && f.away.name) return `${f.home.name} v ${f.away.name}`;
  return sanitizeTitle(raw);
}

const SAME_SLOT_S = 15 * 60;
export const WINDOW_DAYS = 4;

/** Followed teams' games (events from the guide, plus recordings the guide no longer lists), newest day order is the caller's. */
export function buildGames(events: UpEvent[] | null, recs: Rec[] | null, teams: string[], now: number): Game[] {
  const out: Game[] = [];
  const usedRecs = new Set<string>();
  const from = now - WINDOW_DAYS * 86400;
  const eligible = (events ?? []).filter((e) => e.stop >= from && !isHighlightProgramme(e.title, e.kind) && (e.favourite || mentionsTeam(eventText(e), teams)));
  // A recording belongs to the event that names it; failing that, to one that airs in the same slot on the same channel (first come)
  const claimed = new Map<string, Rec>();
  for (const e of eligible) {
    const r = (recs ?? []).find((x) => e.recordingId != null && String(x.id) === String(e.recordingId) && ![...claimed.values()].includes(x));
    if (r) claimed.set(e.key, r);
  }
  for (const e of eligible) {
    if (claimed.has(e.key)) continue;
    const r = (recs ?? []).find((x) => x.channelId === e.channels[0]?.id && Math.abs(x.start - e.start) <= SAME_SLOT_S && ![...claimed.values()].includes(x));
    if (r) claimed.set(e.key, r);
  }
  for (const e of eligible) {
    const rec = claimed.get(e.key) ?? null;
    if (rec) usedRecs.add(String(rec.id));
    out.push({ key: `e:${e.key}`, title: gameTitle(e.title, e.fixture), league: e.league ?? e.fixture?.league ?? null, start: e.start, stop: e.stop, channel: e.channels[0]?.name ?? null, event: e, rec, fixture: e.fixture ?? null });
  }
  for (const r of recs ?? []) {
    if (usedRecs.has(String(r.id)) || (r.state !== "done" && r.state !== "recording")) continue;
    if (r.stop < from || isHighlightProgramme(r.title) || !mentionsTeam(r.title, teams)) continue;
    out.push({ key: `r:${r.id}`, title: sanitizeTitle(r.title), league: null, start: r.start, stop: r.stop, channel: r.channel || null, event: null, rec: r, fixture: null });
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

const extrasOf = (g: Game, via: string): LiveExtras => ({ eventKey: g.event?.key ?? null, matchId: g.fixture?.id ?? null, replay: !!g.fixture?.replay, alternates: (g.event?.channels ?? []).filter((c) => c.id !== via).map((c) => ({ id: c.id, name: c.name })) });

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
    ways.push({ kind: "recording", label: "Your recording, from the start", path: `${targetPath({ recording: String(g.rec.id) })}&t=${t}` });
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
