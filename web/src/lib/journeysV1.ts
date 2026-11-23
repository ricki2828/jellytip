// Journeys v1 (contract ~/ai/state/fleet/jellytip-journeys-v1-spec.md; plan ~/ai/plans/jellytip-journeys-100x.md): server-built
// journeys per person (GET /v1/journeys, GET /v1/journeys/{id}), already filtered by the kids / limited policy. Pure: parsing,
// tonight's step, the path strip, the See the path buttons, Get it feedback, row order that does not re-rank on Back, and the
// load policy (only a 404 is "missing"). A port of the TV's JourneysV1.kt (41b3eb51); tested in web/test/journeysV1.test.ts.

import { targetPath } from "./iptvPlay.ts";
import { vodPath } from "./iptvVod.ts";

export type JvSource = "library" | "iptv" | "gettable" | "missing";
export type JvStatus = "active" | "paused" | "none" | "dropped" | "done";

export const isPlayable = (s: JvSource): boolean => s === "library" || s === "iptv";

export interface JvSummary {
  id: string;
  /** curated | list | director | franchise */
  kind: string;
  title: string;
  curated: boolean;
  status: string;
  done: number;
  total: number;
  playable: number;
  poster: string | null;
  backdrop: string | null;
  /** 0-based index of tonight's step; null = nothing unseen is playable. */
  nowIndex: number | null;
  nowTitle: string | null;
  touchedAt: number | null;
}

/** A step's IPTV copy: only what playing / opening it needs. The live payload can be just {id, kind}. */
export interface JvIptv {
  id: string;
  kind: "movie" | "series";
  backdrop: string | null;
  poster: string | null;
  /** Seconds to resume from (0 = from the start). */
  resume: number;
}

export interface JvStep {
  n: number;
  tmdbId: number | null;
  title: string;
  year: number | null;
  runtimeMin: number | null;
  rating: number | null;
  why: string | null;
  source: JvSource;
  /** Jellyfin id, dashless lower case; only on a library step. */
  itemId: string | null;
  iptv: JvIptv | null;
  seen: boolean;
  rewatch: boolean;
  skipped: boolean;
  poster: string | null;
  backdrop: string | null;
}

export interface JvJourney {
  summary: JvSummary;
  premise: string | null;
  maxRating: string | null;
  steps: JvStep[];
}

export interface JvHome {
  active: string | null;
  journeys: Record<string, JvSummary>;
  yours: string[];
  finish: string[];
  start: string[];
  builtAt: number | null;
}

export type JvStepAction = "play" | "get" | "none";
export type JvBtn = "play" | "remove" | "addBack" | "drop" | "dropConfirm" | "dropCancel";
export type JvDot = "done" | "now" | "next" | "gap" | "skipped";

/** A "Get it" for one step. `error` = the ask didn't get through (network / 5xx): tap again, never shown as Blocked. */
export interface JvAsk {
  state: "asking" | "queued" | "blocked" | "error";
  reason?: string;
}

// ---------- parsing (lenient: a bad entry is dropped, never the whole answer) ----------

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Obj) : null);
const str = (v: unknown): string | null => (typeof v === "string" && v.trim() ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const int = (v: unknown): number | null => {
  const n = num(v);
  return n == null ? null : Math.trunc(n);
};
const ids = (v: unknown): string[] => (Array.isArray(v) ? [...new Set(v.filter((x): x is string => typeof x === "string" && !!x.trim()))] : []);

/** TMDB art is absolute http(s); anything else is relative to the companion; other schemes and "//" are dropped. */
export function art(base: string, raw: unknown): string | null {
  const s = str(raw);
  if (!s) return null;
  if (s.startsWith("https://") || s.startsWith("http://")) return s;
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(s) || s.startsWith("//")) return null;
  return `${base}/${s.replace(/^\/+/, "")}`;
}

export function parseSummary(raw: unknown, base: string): JvSummary | null {
  const o = obj(raw);
  if (!o) return null;
  const id = str(o.id);
  const title = str(o.title);
  if (!id || !title) return null;
  const now = int(o.nowIndex);
  return {
    id,
    kind: str(o.kind) ?? "list",
    title,
    curated: o.curated === true,
    status: str(o.status) ?? "none",
    done: Math.max(0, int(o.done) ?? 0),
    total: Math.max(0, int(o.total) ?? 0),
    playable: Math.max(0, int(o.playable) ?? 0),
    poster: art(base, o.poster),
    backdrop: art(base, o.backdrop),
    nowIndex: now != null && now >= 0 ? now : null,
    nowTitle: str(o.nowTitle),
    touchedAt: int(o.touchedAt),
  };
}

export function parseHome(raw: unknown, base: string): JvHome | null {
  const o = obj(raw);
  if (!o || !Array.isArray(o.journeys)) return null;
  const all: Record<string, JvSummary> = {};
  for (const j of o.journeys) {
    const s = parseSummary(j, base);
    if (s && !(s.id in all)) all[s.id] = s;
  }
  const rows = obj(o.rows);
  // An id is in at most one row (spec); a server slip must not draw one card twice
  const seen = new Set<string>();
  const row = (k: string) => ids(rows?.[k]).filter((id) => id in all && !seen.has(id) && !!seen.add(id));
  const yours = row("yours");
  const finish = row("finish");
  const start = row("start");
  const active = str(o.active);
  return { active: active && active in all ? active : null, journeys: all, yours, finish, start, builtAt: int(o.builtAt) };
}

export const activeSummary = (h: JvHome | null): JvSummary | null => (h?.active ? h.journeys[h.active] ?? null : null);
export const rowOf = (h: JvHome, idsIn: string[]): JvSummary[] => idsIn.map((id) => h.journeys[id]).filter((s): s is JvSummary => !!s);
export const isHomeEmpty = (h: JvHome): boolean => !h.yours.length && !h.start.length && !h.finish.length && !activeSummary(h);

function parseIptv(raw: unknown, base: string): JvIptv | null {
  const o = obj(raw);
  const id = str(o?.id);
  if (!o || !id) return null;
  const p = obj(o.progress);
  const pos = num(p?.position) ?? 0;
  const played = p?.played === true;
  return {
    id,
    kind: o.kind === "series" || id.includes(":s:") ? "series" : "movie",
    backdrop: art(base, o.backdrop),
    poster: art(base, o.poster),
    resume: !played && pos > 30 ? Math.floor(pos) : 0,
  };
}

const SOURCES: JvSource[] = ["library", "iptv", "gettable", "missing"];

export function parseStep(raw: unknown, base: string): JvStep | null {
  const o = obj(raw);
  if (!o) return null;
  const title = str(o.title);
  if (!title) return null;
  const declared = SOURCES.includes(o.source as JvSource) ? (o.source as JvSource) : "missing";
  const iptv = declared === "iptv" ? parseIptv(o.iptv, base) : null;
  const itemId = str(o.itemId)?.replace(/-/g, "").toLowerCase() ?? null;
  // A playable source without what it needs to play is not playable here
  const source: JvSource = declared === "library" && !itemId ? "missing" : declared === "iptv" && !iptv ? "missing" : declared;
  const pos = (v: unknown) => {
    const n = num(v);
    return n != null && n > 0 ? n : null;
  };
  return {
    n: int(o.n) ?? 0,
    tmdbId: pos(int(o.tmdbId)),
    title,
    year: pos(int(o.year)),
    runtimeMin: pos(int(o.runtimeMin)),
    rating: pos(o.rating),
    why: str(o.why),
    source,
    itemId: source === "library" ? itemId : null,
    iptv,
    seen: o.seen === true,
    rewatch: o.rewatch === true,
    skipped: o.skipped === true,
    poster: art(base, o.poster),
    backdrop: art(base, o.backdrop),
  };
}

export function parseJourney(raw: unknown, base: string): JvJourney | null {
  const o = obj(raw);
  const summary = parseSummary(o, base);
  if (!o || !summary) return null;
  const steps = (Array.isArray(o.steps) ? o.steps : []).map((s) => parseStep(s, base)).filter((s): s is JvStep => !!s);
  return { summary, premise: str(o.premise), maxRating: str(o.maxRating), steps };
}

// ---------- ids + routes ----------

/** v1 ids: cur:<slug>, l:<letterboxd slug>, d:<TMDB person id>, f:<TMDB collection id>. */
const ID_RE = /^(cur:[a-z0-9][a-z0-9_-]{0,159}|l:[a-z0-9][a-z0-9_-]{0,159}|d:\d{1,10}|f:\d{1,10})$/i;
export const isJourneyId = (id: string): boolean => ID_RE.test(id);
export const journeyPath = (id: string): string => `/journeys/${encodeURIComponent(id)}`;
/** The API path segment: the id URL-encoded (the server takes "cur%3Aroad-to-heat" and "cur:road-to-heat"). */
export const apiId = (id: string): string => encodeURIComponent(id);

/** Where a step opens (details): the library item, or the IPTV title. Null = nothing to open. */
export function stepDetailsPath(st: JvStep): string | null {
  if (st.source === "library" && st.itemId) return `/item/${st.itemId}`;
  if (st.source === "iptv" && st.iptv) return vodPath(st.iptv.id);
  return null;
}

/** Where a step plays: /play/<id> for the library; an IPTV film the way IPTV details plays it (on the phone: the native player). */
export function stepPlayPath(st: JvStep): string | null {
  if (st.source === "library" && st.itemId) return `/play/${st.itemId}`;
  if (st.source === "iptv" && st.iptv) {
    // A series has no single thing to play: its page
    if (st.iptv.kind === "series") return vodPath(st.iptv.id);
    return `${targetPath({ vod: st.iptv.id })}&t=${encodeURIComponent(st.title)}${st.iptv.resume ? `&resume=${st.iptv.resume}` : ""}`;
  }
  return null;
}

// ---------- tonight's step ----------

/**
 * Tonight's step: the first step not seen, not skipped and playable. Earlier unseen steps that can't play yet are passed over
 * (the path offers the next playable one meanwhile). When every unseen playable step was skipped, the first skipped one is
 * offered again. Null = nothing left to play.
 */
export function nowIndex(steps: JvStep[]): number | null {
  const a = steps.findIndex((s) => !s.seen && !s.skipped && isPlayable(s.source));
  if (a >= 0) return a;
  const b = steps.findIndex((s) => !s.seen && isPlayable(s.source));
  return b >= 0 ? b : null;
}

/** The step a bare "Get it" fetches: the first unseen gettable one, as the server's /get picks it. */
export function getIndex(steps: JvStep[]): number | null {
  const i = steps.findIndex((s) => !s.seen && s.source === "gettable");
  return i >= 0 ? i : null;
}

// ---------- See the path buttons (Play is the way in; no Start) ----------

/**
 * The buttons, in order. Play starts the journey (no separate Start). An active journey can be removed from "my journeys"
 * (server pause, progress kept); a removed / dropped one added back. Drop only behind a confirm. A finished journey: nothing.
 */
export function pathButtons(status: string, canPlay: boolean, confirmingDrop: boolean): JvBtn[] {
  if (confirmingDrop) return ["dropConfirm", "dropCancel"];
  const out: JvBtn[] = [];
  if (canPlay) out.push("play");
  if (status === "active") out.push("remove");
  else if (status === "paused" || status === "dropped") out.push("addBack");
  if (status !== "dropped" && status !== "done") out.push("drop");
  return out;
}

export function btnLabel(b: JvBtn, nowTitle: string | null): string {
  switch (b) {
    case "play":
      return `▶ Play: ${nowTitle ?? ""}`;
    case "remove":
      return "Remove from my journeys";
    case "addBack":
      return "Add back to my journeys";
    case "drop":
      return "Drop it…";
    case "dropConfirm":
      return "Yes, drop it";
    case "dropCancel":
      return "Keep it";
  }
}

/** The kicker once a journey is yours: "YOUR JOURNEY: THE ROAD TO HEAT · STEP 3 OF 6" (null unless active). */
export function yourJourneyLine(title: string, status: string, now: number | null, size: number): string | null {
  if (status !== "active") return null;
  return now == null ? `Your journey: ${title} · every step seen` : `Your journey: ${title} · step ${now + 1} of ${size}`;
}

export const TOAST_ADDED = "Added to your journeys";
export const TOAST_REMOVED = "Removed from your journeys. Your progress is kept.";
export const TOAST_ADDED_BACK = "Added back to your journeys";
export const TOAST_DROPPED = "Dropped";

/** The status a start / pause / drop leaves (applied at once; the call itself is fire-and-forget). Finished stays done. */
export function statusAfter(status: string, action: "start" | "pause" | "drop"): string {
  if (status === "done") return "done";
  if (action === "start") return "active";
  if (action === "pause") return status === "active" ? "paused" : status;
  return "dropped";
}

// ---------- Get it for any step ----------

/** What a tap on a step does: play it, ask for it (gettable: the server only marks that for an admin), or nothing (missing). */
export function stepAction(st: JvStep): JvStepAction {
  if (isPlayable(st.source)) return "play";
  if (st.source === "gettable") return "get";
  return "none";
}

/** The short line under a step nothing can be done with. */
export function missingReason(st: JvStep): string | null {
  return st.source === "missing" ? "Not in the library or on TV, and it can't be fetched" : null;
}

/** The /get body for one chosen step: its place (0-based) and its TMDB id, so the server can check both. {} = next missing. */
export function getBody(steps: JvStep[], index: number | null): { index?: number; tmdbId?: number } {
  const st = index == null ? undefined : steps[index];
  if (!st || index == null) return {};
  return st.tmdbId ? { index, tmdbId: st.tmdbId } : { index };
}

/** A /get answer as the step's feedback. 409 reasons are shown as they are; 0 / 5xx / 429 are "try again", never Blocked. */
export function askFromReply(status: number, data: unknown): JvAsk {
  const reason = str(obj(data)?.reason);
  if (status === 200) return { state: "queued" };
  if (status === 409) return { state: "blocked", reason: reason ?? "Can't get it right now." };
  if (status === 403) return { state: "blocked", reason: "Only an admin can get films." };
  if (status === 404) return { state: "blocked", reason: "That step isn't in this journey any more. Go back and open it again." };
  if (status === 0) return { state: "error", reason: "Couldn't reach the server. Try again." };
  return { state: "error", reason: `Couldn't ask for it (${status}). Try again.` };
}

export function askLabel(a: JvAsk | null | undefined): string {
  switch (a?.state) {
    case undefined:
      return "Get it";
    case "asking":
      return "Asking…";
    case "queued":
      return "On its way";
    case "blocked":
      return "Blocked";
    case "error":
      return "Try again";
  }
}

export function askText(a: JvAsk): string {
  switch (a.state) {
    case "asking":
      return "Asking for it…";
    case "queued":
      return "On its way. It shows up here when it's downloaded.";
    case "blocked":
      return `Blocked: ${a.reason ?? "Can't get it right now."}`;
    case "error":
      return a.reason ?? "Couldn't ask for it. Try again.";
  }
}

/** Get it can be tapped again only when it isn't asking or already on its way. */
export const canAsk = (a: JvAsk | null | undefined): boolean => !a || a.state === "blocked" || a.state === "error";

// ---------- path strip + step facts ----------

export function dot(steps: JvStep[], i: number, now: number | null = nowIndex(steps)): JvDot {
  const s = steps[i];
  if (s.seen) return "done";
  if (i === now) return "now";
  if (s.skipped) return "skipped";
  if (!isPlayable(s.source)) return "gap";
  return "next";
}

/** "THE ROAD TO HEAT · STEP 3 OF 6": the position among the steps this person sees (authored numbers can have gaps). */
export const kicker = (title: string, index: number, total: number): string => `${title.toUpperCase()} · STEP ${index + 1} OF ${total}`;

export function runtime(min: number | null | undefined): string | null {
  if (!min || min <= 0) return null;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h === 0 ? `${m} m` : m === 0 ? `${h} h` : `${h} h ${m} m`;
}

export const sourceBadge = (s: JvSource): string => (s === "library" ? "Library" : s === "iptv" ? "IPTV" : s === "gettable" ? "Not here yet" : "Not available");

/** "2 h 2 m · ★ 7.4": runtime and rating only (the page draws the source as its own small badge). */
export const facts = (s: JvStep): string => [runtime(s.runtimeMin), s.rating ? `★ ${s.rating.toFixed(1)}` : null].filter(Boolean).join(" · ");

/** "2 h 2 m · ★ 7.4 · Library" (the year sits with the title): the source is a small fact; library and IPTV look the same. */
export function meta(s: JvStep): string {
  return [runtime(s.runtimeMin), s.rating ? `★ ${s.rating.toFixed(1)}` : null, sourceBadge(s.source), s.rewatch ? "Rewatch" : null].filter(Boolean).join(" · ");
}

export const progressLabel = (s: JvSummary): string => `${s.done}/${s.total}`;
export const progress = (s: JvSummary): number => (s.total <= 0 ? 0 : Math.min(1, Math.max(0, s.done / s.total)));

export interface PathWindow {
  from: number;
  to: number;
  /** Steps hidden before `from` / after `to` (drawn as "+n"). */
  before: number;
  after: number;
}

export const PATH_MAX = 5;

/** At most `max` steps around tonight's: one before it for context (more when nothing follows), the rest after. */
export function pathWindow(size: number, now: number | null, max = PATH_MAX): PathWindow {
  if (size <= 0) return { from: 0, to: -1, before: 0, after: 0 };
  if (size <= max) return { from: 0, to: size - 1, before: 0, after: 0 };
  const anchor = Math.min(size - 1, Math.max(0, now ?? size - 1));
  let from = Math.max(0, anchor - 1);
  let to = from + max - 1;
  if (to > size - 1) {
    to = size - 1;
    from = Math.max(0, to - max + 1);
  }
  return { from, to, before: from, after: size - 1 - to };
}

// ---------- Back lands on the same card (no re-rank) ----------

/** A refetch while the page is kept: ids already shown keep their places, new ones go at the end, gone ones go. */
export function stable(old: string[], fresh: string[]): string[] {
  const f = new Set(fresh);
  const kept = old.filter((x) => f.has(x));
  const k = new Set(kept);
  return [...kept, ...fresh.filter((x) => !k.has(x))];
}

export function stableHome(old: JvHome | null, fresh: JvHome): JvHome {
  if (!old) return fresh;
  // A card moving between rows (started: start -> yours) moves; within a row the order holds
  return { ...fresh, yours: stable(old.yours, fresh.yours), start: stable(old.start, fresh.start), finish: stable(old.finish, fresh.finish) };
}

// ---------- events ----------

/** Playing step `i` while tonight's is `now` (earlier, unseen): that one was passed over, a skip. */
export function skipFor(steps: JvStep[], i: number, now: number | null = nowIndex(steps)): JvStep | null {
  return now != null && i > now ? steps[now] : null;
}

/** Marks a step skipped locally (the server does the same) so tonight's moves on at once. */
export function withSkipped(steps: JvStep[], i: number): JvStep[] {
  return steps.map((s, k) => (k === i ? { ...s, skipped: true } : s));
}

// ---------- load policy: only a 404 is "missing" ----------

export const isNotFound = (status: number | null | undefined): boolean => status === 404;

/** One answer: 200 = ok; 404 = not there (kept for the session); anything else (0, 5xx, 503 lookup_failed, 429, 401…) = retry. */
export function loadOutcome(status: number | null | undefined): "ok" | "missing" | "retry" {
  return status === 200 ? "ok" : status === 404 ? "missing" : "retry";
}

export const RETRY_MIN_MS = 5_000;
export const RETRY_MAX_MS = 5 * 60_000;
/** Wait before retry number `failures` (1 = first failure): 5 s, 10 s, 20 s… capped at 5 min. */
export const retryMs = (failures: number): number => Math.min(RETRY_MAX_MS, RETRY_MIN_MS * 2 ** Math.max(0, failures - 1));

/** An answer still for what is on screen: a late answer for another journey (or person) is dropped. */
export const isCurrent = (answerFor: string, showing: string | null | undefined): boolean => !!showing && answerFor === showing;

/**
 * One at a time: `run` does nothing while a previous run is still going, and the flag always clears (resolve, throw, or a
 * synchronous throw). Returns false when it was busy.
 */
export function oneAtATime(): { run: (fn: () => Promise<unknown> | unknown) => boolean; readonly busy: boolean } {
  let busy = false;
  return {
    get busy() {
      return busy;
    },
    run(fn) {
      if (busy) return false;
      busy = true;
      try {
        Promise.resolve(fn())
          .catch(() => undefined)
          .finally(() => (busy = false));
      } catch {
        busy = false;
      }
      return true;
    },
  };
}

// ---------- Home hero: the journey slide arriving / leaving without a jump ----------

/**
 * Where the journey slide goes when it arrives (it loads after the mix): first, shown at once, while the hero has not rotated
 * yet; else right after the slide on screen, which stays put (rotation counter `i` is rebased onto it). `pos` is its place.
 */
export function insertJourneySlide(i: number, slideCount: number, rotated: boolean): { pos: number; i: number } {
  if (!rotated || slideCount === 0) return { pos: 0, i: 0 };
  const c = i % slideCount;
  return { pos: c + 1, i: c };
}

/** What the hero shows for rotation counter `i`: the journey slide, mix slide `index`, or nothing. */
export function heroSlideAt(i: number, slideCount: number, pos: number | null): { journey: true } | { index: number } | null {
  const n = slideCount + (pos != null ? 1 : 0);
  if (!n) return null;
  const at = i % n;
  if (pos == null) return { index: at };
  const p = Math.min(pos, slideCount);
  if (at === p) return { journey: true };
  return { index: at < p ? at : at - 1 };
}

/** The journey slide went away: keep the mix slide on screen (or, if the journey was showing, the one after it). */
export function removeJourneySlide(i: number, slideCount: number, pos: number): number {
  if (!slideCount) return 0;
  const cur = heroSlideAt(i, slideCount, pos);
  if (!cur || "journey" in cur) return Math.min(pos, slideCount) % slideCount;
  return cur.index;
}
