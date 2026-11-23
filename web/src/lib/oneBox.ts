// One box (Ricki 23-Nov; plan ~/ai/plans/jellytip-one-box.md, contract ~/ai/state/fleet/jellytip-find-v1-spec.md): Search and Chat
// are one destination (#/search). Typing still searches as you go (/v1/search); a SUBMIT (Enter, the button, a final voice result)
// asks GET /v1/find whether the words are a search, a chat ask, or both. Follow-ups in the chat go straight to /v1/ask/chat;
// Start over clears both. Pure and tested (web/test/oneBox.test.ts); screens/Search.tsx renders it. Same rules as the TV's FindFlow.
//
// Every step returns the new state plus the effects the screen must run (call find, send a chat turn, search plainly, feedback).

export type FindMode = "search" | "chat" | "both";
export type FindSwitch = "ask_chip" | "retyped" | "search_chip" | "played";

export interface FindDecision<S> {
  id: string;
  mode: FindMode;
  /** P(chat), 0..1. */
  p: number;
  why: string;
  /** The /v1/search body, parsed (null for chat, or when the server sent none / it didn't parse). */
  search: S | null;
  /** The text to send to /v1/ask/chat (null for search). */
  askText: string | null;
}

const isObj = (x: unknown): x is Record<string, unknown> => !!x && typeof x === "object" && !Array.isArray(x);

/** A /v1/find body -> the decision, or null when it isn't one (no id, unknown mode). `parseSearch` reads the embedded /v1/search body. */
export function parseFind<S>(d: unknown, parseSearch: (raw: unknown) => S | null): FindDecision<S> | null {
  if (!isObj(d)) return null;
  const mode = d.mode;
  if (mode !== "search" && mode !== "chat" && mode !== "both") return null;
  if (typeof d.id !== "string" || !d.id.trim()) return null;
  const search = mode === "chat" || !isObj(d.search) ? null : parseSearch(d.search);
  const ask = isObj(d.ask) && typeof d.ask.text === "string" && d.ask.text.trim() ? d.ask.text : null;
  return {
    id: d.id,
    mode,
    p: typeof d.p === "number" && Number.isFinite(d.p) ? d.p : 0,
    why: typeof d.why === "string" ? d.why : "",
    search,
    askText: mode === "search" ? null : ask,
  };
}

export type FindOutcome<S> = { kind: "ok"; d: FindDecision<S> } | { kind: "noRoute" } | { kind: "error"; status: number };

/** Only a 404 means "no /v1/find here" (an older server): search plainly. 401 / 403 / 5xx / 0 / an unreadable 200 are errors. */
export function findOutcome<S>(status: number, data: unknown, parseSearch: (raw: unknown) => S | null): FindOutcome<S> {
  if (status === 404) return { kind: "noRoute" };
  if (status !== 200) return { kind: "error", status };
  const d = parseFind(data, parseSearch);
  return d ? { kind: "ok", d } : { kind: "error", status: 200 };
}

/** How long a 404 is remembered (in memory only, like /v1/search's): after that /v1/find is tried again (it may have been deployed). */
export const NO_ROUTE_MS = 5 * 60_000;
export class NoRouteMemory {
  private until = 0;
  missing(now: number): boolean {
    return now < this.until;
  }
  mark(now: number): void {
    this.until = now + NO_ROUTE_MS;
  }
  clear(): void {
    this.until = 0;
  }
}

/** "Try again" after a failed find: the first retry goes at once, then 2 s, 4 s, 8 s ... up to 30 s (failures = consecutive ones). */
export function findRetryMs(failures: number): number {
  if (failures <= 1) return 0;
  return Math.min(30_000, 1000 * 2 ** (failures - 1));
}

// ---------- the flow ----------

export const RETYPE_WINDOW_MS = 60_000;
/** BOTH: the chat turn is sent only if no hit was opened within this long (an LLM turn counts against the daily cap). */
export const DEFER_CHAT_MS = 3000;

/** BOTH's deferred chat turn: waiting (timer armed) → sent, or cancelled (a hit was opened first: never sent). */
export type Deferred = "none" | "waiting" | "sent" | "cancelled";

export interface FindUi {
  /** What is on screen: search results (also while typing), the chat, or search results with the chat answer below. */
  view: FindMode;
  /** A submit is out at /v1/find. */
  deciding: boolean;
  /** The submit counter: an answer for an older submit is dropped. */
  seq: number;
  /** The words of the last submit (what the chat / search chips act on). */
  q: string;
  /** The decision on screen (null: no decision, e.g. an older server or a typed search). */
  decisionId: string | null;
  decidedAt: number;
  /** The decision's mode (for the "Just search for this" chip, shown only when it was chat). */
  decided: FindMode | null;
  /** Decision ids that already got their one feedback. */
  fed: string[];
  /** The last find failed (401 / 403 / 5xx / network): "Couldn't search, try again". */
  error: boolean;
  /** Consecutive failed finds (the retry backoff). */
  failures: number;
  /** The last submit was answered by the plain-search fallback (the server has no /v1/find). */
  fallback: boolean;
  deferred: Deferred;
}

export const emptyFind = (): FindUi => ({ view: "search", deciding: false, seq: 0, q: "", decisionId: null, decidedAt: 0, decided: null, fed: [], error: false, failures: 0, fallback: false, deferred: "none" });

export interface FindEffects {
  /** Call /v1/find for this text (for submit [FindUi.seq]). */
  find?: string;
  /** Send this text to the chat as a new turn (a first turn after a decision, or a follow-up). */
  chat?: string;
  /** Clear the chat (Start over, a new question, back to search). */
  resetChat?: boolean;
  /** Search plainly for this text (a 404 fallback, a decision without a search body, "Just search for this"). */
  search?: string;
  /** Feedback for a decision id (at most once per id). */
  feedback?: { id: string; switched: FindSwitch };
  /** BOTH: arm the deferred-chat timer (DEFER_CHAT_MS). */
  armTimer?: boolean;
  /** Stop the deferred-chat timer. */
  stopTimer?: boolean;
}
export type Step = { state: FindUi; fx: FindEffects };

const norm = (q: string) => q.replace(/\s+/g, " ").trim();
const same = (a: string, b: string) => norm(a).toLowerCase() === norm(b).toLowerCase();

/** One feedback per decision id: the effect, and the state that remembers it was sent. */
function feedback(s: FindUi, switched: FindSwitch): { state: FindUi; fb?: FindEffects["feedback"] } {
  const id = s.decisionId;
  if (!id || s.fed.includes(id)) return { state: s };
  return { state: { ...s, fed: [...s.fed, id].slice(-50) }, fb: { id, switched } };
}

/** A follow-up goes straight to the chat (find is not asked again) only while the chat is what is on screen. */
export const inChat = (s: FindUi): boolean => s.view === "chat";

/**
 * A submit from the box. In the chat it is a follow-up (straight to /v1/ask/chat). Otherwise a new question: /v1/find, unless the
 * server is known not to have it (`noRoute`), then plain search. Different words within 60 s of the last decision = "retyped".
 */
export function submit(s: FindUi, text: string, now: number, noRoute = false): Step {
  const q = norm(text);
  if (!q) return { state: s, fx: {} };
  if (inChat(s)) return { state: s, fx: { chat: q } };
  let next: FindUi = s;
  let fb: FindEffects["feedback"];
  if (s.decisionId && !same(s.q, q) && now - s.decidedAt >= 0 && now - s.decidedAt <= RETYPE_WINDOW_MS) ({ state: next, fb } = feedback(s, "retyped"));
  const base: FindUi = { ...next, view: "search", seq: s.seq + 1, q, error: false, deferred: "none", decided: null, decisionId: null };
  if (noRoute) return { state: { ...base, deciding: false, fallback: true, failures: 0 }, fx: { search: q, resetChat: true, stopTimer: true, ...(fb ? { feedback: fb } : {}) } };
  return { state: { ...base, deciding: true, fallback: false }, fx: { find: q, resetChat: true, stopTimer: true, ...(fb ? { feedback: fb } : {}) } };
}

/** Try again after a failed find: the same words again (the screen waits findRetryMs(failures) first). */
export function retryFind(s: FindUi): Step {
  if (!s.error || !s.q) return { state: s, fx: {} };
  return { state: { ...s, deciding: true, error: false, seq: s.seq + 1 }, fx: { find: s.q } };
}

/** The /v1/find answer for submit `seq` (an older one is dropped). */
export function decided<S>(s: FindUi, seq: number, out: FindOutcome<S>, now: number): Step {
  if (seq !== s.seq || !s.deciding) return { state: s, fx: {} };
  if (out.kind === "error") return { state: { ...s, deciding: false, error: true, failures: s.failures + 1 }, fx: {} };
  if (out.kind === "noRoute") return { state: { ...s, deciding: false, view: "search", fallback: true, failures: 0, decisionId: null, decided: null }, fx: { search: s.q } };
  const d = out.d;
  const next: FindUi = { ...s, deciding: false, error: false, failures: 0, fallback: false, view: d.mode, decided: d.mode, decisionId: d.id, decidedAt: now, deferred: d.mode === "both" ? "waiting" : "none" };
  const fx: FindEffects = {};
  if (d.mode === "chat") fx.chat = d.askText ?? s.q;
  if (d.mode !== "chat" && d.search == null) fx.search = s.q; // the server's search part failed: search plainly
  if (d.mode === "both") fx.armTimer = true;
  return { state: next, fx };
}

/** BOTH: the deferred chat turn goes now (the timer ran out, or the person moved / scrolled to the chat area). Once. */
export function sendDeferred(s: FindUi): Step {
  if (s.view !== "both" || s.deferred !== "waiting") return { state: s, fx: {} };
  return { state: { ...s, deferred: "sent" }, fx: { chat: s.q, stopTimer: true } };
}
// the screen spells out which trigger fired (all three do the same)
export const deferredTimer = sendDeferred;
export const deferredScrolled = sendDeferred;

/** A result (search hit or chat pick) was opened or played: the decision was right ("played", once); BOTH's waiting chat is never sent. */
export function opened(s: FindUi): Step {
  const { state, fb } = feedback(s, "played");
  const cancel = state.deferred === "waiting";
  return { state: cancel ? { ...state, deferred: "cancelled" } : state, fx: { ...(fb ? { feedback: fb } : {}), ...(cancel ? { stopTimer: true } : {}) } };
}

/**
 * "Ask Chat about this" (on a search or both answer, or on plain results): into the chat. The words go to the chat unless BOTH
 * already sent them. Feedback "ask_chip" when there is a decision.
 */
export function askChip(s: FindUi, text: string): Step {
  const already = s.view === "both" && s.deferred === "sent";
  // the words in the box (typed since, or never submitted); BOTH that already asked keeps its words
  const q = already ? s.q : norm(text) || s.q;
  const { state, fb } = feedback(s, "ask_chip");
  const fx: FindEffects = { stopTimer: true, ...(fb ? { feedback: fb } : {}) };
  if (!already && q) fx.chat = q;
  if (!already && s.view !== "both") fx.resetChat = true;
  return { state: { ...state, view: "chat", q, deferred: s.view === "both" ? "sent" : "none" }, fx };
}

/** "Just search for this" under a chat answer (decision mode chat): the plain results for the same words. */
export function searchChip(s: FindUi): Step {
  const { state, fb } = feedback(s, "search_chip");
  return { state: { ...state, view: "search", deferred: "none" }, fx: { search: s.q, resetChat: true, stopTimer: true, ...(fb ? { feedback: fb } : {}) } };
}

/** The chat answered 404 (chat switched off on the server): the plain results for the same words instead. */
export function chatOff(s: FindUi): Step {
  return { state: { ...s, view: "search", deferred: "none" }, fx: { search: s.q, resetChat: true, stopTimer: true } };
}

/** Start over: an empty box, plain search, no chat; anything in flight is dropped (seq moves on). */
export function startOver(s: FindUi): Step {
  return { state: { ...emptyFind(), seq: s.seq + 1, fed: s.fed }, fx: { resetChat: true, stopTimer: true } };
}

/** The words in the box changed after a decision: BOTH's waiting chat is for the old words, so it is never sent. */
export function typing(s: FindUi, text: string): Step {
  if (s.view !== "both" || s.deferred !== "waiting" || same(s.q, text)) return { state: s, fx: {} };
  return { state: { ...s, deferred: "cancelled" }, fx: { stopTimer: true } };
}

/** Leaving the screen (opening a hit, the nav): a waiting chat is never sent later, a find in flight becomes a Try again. */
export function leave(s: FindUi): FindUi {
  return {
    ...s,
    deferred: s.deferred === "waiting" ? "cancelled" : s.deferred,
    ...(s.deciding ? { deciding: false, error: true } : {}),
  };
}

// ---------- words ----------

/** The chat area under BOTH's results. */
export function bothLine(deferred: Deferred, chatPending: boolean, hasAnswer: boolean): string {
  if (chatPending || (deferred === "sent" && !hasAnswer)) return "Asking Chat about this…";
  return "Ask Chat about this";
}

export const PLACEHOLDER = "Search or describe what you want";
export const FOLLOW_UP_PLACEHOLDER = "Refine it: something shorter, with Paul Rudd…";
export const FIND_ERROR = "Couldn't search, try again";
