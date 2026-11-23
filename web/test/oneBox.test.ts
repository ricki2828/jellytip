import { test } from "node:test";
import assert from "node:assert/strict";
import {
  askChip, chatOff, decided, DEFER_CHAT_MS, deferredScrolled, deferredTimer, emptyFind, findOutcome, findRetryMs, leave, NO_ROUTE_MS, NoRouteMemory, opened, parseFind, retryFind,
  RETYPE_WINDOW_MS, searchChip, startOver, submit, typing, interrupt, bothLine, type FindOutcome, type FindUi,
} from "../src/lib/oneBox.ts";
import { applyResponse, basicLabel, emptyState, parseResponse, submitText, tapChip, type ChatResponse } from "../src/lib/askChat.ts";
import { parseHash } from "../src/lib/routeParse.ts";
import { navActive } from "../src/lib/navModel.ts";

type S = { q: string };
const ps = (raw: unknown): S | null => (raw && typeof raw === "object" && typeof (raw as S).q === "string" ? (raw as S) : null);
const body = (o: Record<string, unknown> = {}) => ({ v: 1, id: "f_1", mode: "search", p: 0.1, why: "title_match", search: { q: "heat" }, ask: null, ...o });
const ok = (o: Record<string, unknown> = {}): FindOutcome<S> => findOutcome(200, body(o), ps);
const T0 = 1_000_000;

/** Submit then answer: the state after the decision. */
function decide(q: string, o: Record<string, unknown>, s: FindUi = emptyFind(), now = T0) {
  const a = submit(s, q, now);
  return decided(a.state, a.state.seq, ok(o), now);
}

// ---------- parsing ----------

test("parseFind: the three modes; search null for chat; ask null for search; p / why defaulted", () => {
  const s = parseFind(body(), ps)!;
  assert.deepEqual([s.mode, s.id, s.search, s.askText, s.p, s.why], ["search", "f_1", { q: "heat" }, null, 0.1, "title_match"]);
  const c = parseFind(body({ mode: "chat", search: { q: "x" }, ask: { text: "something cosy" } }), ps)!;
  assert.deepEqual([c.mode, c.search, c.askText], ["chat", null, "something cosy"]);
  const b = parseFind(body({ mode: "both", ask: { text: "heat but funny" }, p: "x", why: 3 }), ps)!;
  assert.deepEqual([b.mode, b.search, b.askText, b.p, b.why], ["both", { q: "heat" }, "heat but funny", 0, ""]);
  assert.equal(parseFind(body({ ask: { text: "ignored" } }), ps)!.askText, null);
});

test("parseFind: not a decision without an id or with an unknown mode; a bad search body is null, not fatal", () => {
  assert.equal(parseFind(body({ id: "" }), ps), null);
  assert.equal(parseFind(body({ id: 5 }), ps), null);
  assert.equal(parseFind(body({ mode: "ask" }), ps), null);
  assert.equal(parseFind(null, ps), null);
  assert.equal(parseFind([], ps), null);
  assert.equal(parseFind(body({ search: { nope: 1 } }), ps)!.search, null);
  assert.equal(parseFind(body({ search: null }), ps)!.search, null);
});

test("findOutcome: only 404 is 'no route'; 401 / 403 / 5xx / 0 and an unreadable 200 are errors", () => {
  assert.deepEqual(findOutcome(404, null, ps), { kind: "noRoute" });
  for (const st of [0, 400, 401, 403, 429, 500, 502, 503]) assert.deepEqual(findOutcome(st, body(), ps), { kind: "error", status: st });
  assert.deepEqual(findOutcome(200, { v: 1 }, ps), { kind: "error", status: 200 });
  assert.equal(findOutcome(200, body(), ps).kind, "ok");
});

test("NoRouteMemory: a 404 is remembered for NO_ROUTE_MS, then find is tried again; clear forgets it", () => {
  const m = new NoRouteMemory();
  assert.equal(m.missing(T0), false);
  m.mark(T0);
  assert.equal(m.missing(T0 + NO_ROUTE_MS - 1), true);
  assert.equal(m.missing(T0 + NO_ROUTE_MS), false);
  m.mark(T0);
  m.clear();
  assert.equal(m.missing(T0 + 1), false);
});

test("findRetryMs: first retry at once, then doubling from 2 s, capped at 30 s", () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 9].map(findRetryMs), [0, 2000, 4000, 8000, 16000, 30000, 30000]);
});

// ---------- submit / decided: the mode-to-view plan ----------

test("submit: trims, asks find, resets the chat; blank does nothing", () => {
  const a = submit(emptyFind(), "  heat \n", T0);
  assert.deepEqual(a.fx, { find: "heat", resetChat: true, stopTimer: true });
  assert.deepEqual([a.state.deciding, a.state.q, a.state.seq], [true, "heat", 1]);
  assert.deepEqual(submit(emptyFind(), "   ", T0), { state: emptyFind(), fx: {} });
});

test("mode search: the results, no chat; mode chat: the chat view and the first turn with ask.text", () => {
  const s = decide("heat", {});
  assert.deepEqual([s.state.view, s.state.decisionId, s.state.deferred], ["search", "f_1", "none"]);
  assert.deepEqual(s.fx, {});
  const c = decide("something cosy for a rainy day", { mode: "chat", search: null, ask: { text: "something cosy for a rainy day" } });
  assert.equal(c.state.view, "chat");
  assert.deepEqual(c.fx, { chat: "something cosy for a rainy day" });
  // no ask text from the server: the words as typed
  assert.equal(decide("cosy", { mode: "chat", search: null, ask: null }).fx.chat, "cosy");
});

test("mode both: results first, chat DEFERRED (timer armed, nothing sent yet)", () => {
  const b = decide("heat but funny", { mode: "both", ask: { text: "heat but funny" } });
  assert.deepEqual([b.state.view, b.state.deferred], ["both", "waiting"]);
  assert.deepEqual(b.fx, { armTimer: true });
  assert.equal(b.fx.chat, undefined);
  assert.equal(DEFER_CHAT_MS, 3000);
});

test("a decision whose search part is missing searches plainly (search and both)", () => {
  assert.deepEqual(decide("heat", { search: null }).fx, { search: "heat" });
  assert.deepEqual(decide("heat", { mode: "both", search: null }).fx, { search: "heat", armTimer: true });
});

test("404 fallback: plain search for the same words, no decision, no error; with the memory set a submit skips find", () => {
  const a = submit(emptyFind(), "heat", T0);
  const n = decided(a.state, a.state.seq, { kind: "noRoute" }, T0);
  assert.deepEqual(n.fx, { search: "heat" });
  assert.deepEqual([n.state.view, n.state.fallback, n.state.error, n.state.decisionId], ["search", true, false, null]);
  const again = submit(n.state, "alien", T0 + 5, true);
  assert.deepEqual(again.fx, { search: "alien", resetChat: true, stopTimer: true });
  assert.deepEqual([again.state.deciding, again.state.fallback], [false, true]);
});

test("error: 'Couldn't search' state, no fallback search; Try again re-asks find with the same words; failures count", () => {
  const a = submit(emptyFind(), "heat", T0);
  const e = decided(a.state, a.state.seq, { kind: "error", status: 503 }, T0);
  assert.deepEqual(e.fx, {});
  assert.deepEqual([e.state.error, e.state.deciding, e.state.failures], [true, false, 1]);
  const r = retryFind(e.state);
  assert.deepEqual(r.fx, { find: "heat" });
  const e2 = decided(r.state, r.state.seq, { kind: "error", status: 0 }, T0);
  assert.equal(e2.state.failures, 2);
  assert.equal(findRetryMs(e2.state.failures), 2000);
  const good = decided(retryFind(e2.state).state, e2.state.seq + 1, ok(), T0);
  assert.deepEqual([good.state.failures, good.state.error], [0, false]);
  assert.deepEqual(retryFind(emptyFind()).fx, {}, "nothing to retry");
});

test("an answer for an older submit is dropped", () => {
  const a = submit(emptyFind(), "heat", T0);
  const b = submit({ ...a.state, deciding: false }, "alien", T0 + 10);
  const stale = decided(b.state, a.state.seq, ok(), T0 + 20);
  assert.equal(stale.state, b.state);
  assert.deepEqual(stale.fx, {});
});

// ---------- follow-ups ----------

test("in the chat a submit is a follow-up straight to the chat (find is not asked again)", () => {
  const c = decide("cosy", { mode: "chat", search: null });
  const f = submit(c.state, "shorter", T0 + 1000);
  assert.deepEqual(f.fx, { chat: "shorter" });
  assert.equal(f.state, c.state);
});

// ---------- the deferred chat (both) ----------

test("deferred: the timer sends the chat once; a second trigger does nothing", () => {
  const b = decide("heat but funny", { mode: "both" });
  const t = deferredTimer(b.state);
  assert.deepEqual(t.fx, { chat: "heat but funny", stopTimer: true });
  assert.equal(t.state.deferred, "sent");
  assert.deepEqual(deferredScrolled(t.state).fx, {});
  assert.deepEqual(deferredTimer(t.state).fx, {});
});

test("deferred: scrolling to the chat area sends it before the timer", () => {
  const b = decide("heat but funny", { mode: "both" });
  const s = deferredScrolled(b.state);
  assert.equal(s.fx.chat, "heat but funny");
  assert.deepEqual(deferredTimer(s.state).fx, {}, "the timer then finds it sent");
});

test("deferred: a hit opened first cancels it for good (played feedback, timer stopped, never sent)", () => {
  const b = decide("heat but funny", { mode: "both" });
  const o = opened(b.state);
  assert.deepEqual(o.fx, { feedback: { id: "f_1", switched: "played" }, stopTimer: true });
  assert.equal(o.state.deferred, "cancelled");
  assert.deepEqual(deferredTimer(o.state).fx, {});
  assert.deepEqual(deferredScrolled(o.state).fx, {});
  assert.equal(leave(o.state).deferred, "cancelled", "Back never re-arms it");
});

test("deferred: the chip sends it at once (ask_chip) and moves to the chat; after the timer it does not send twice", () => {
  const b = decide("heat but funny", { mode: "both" });
  const c = askChip(b.state, "heat but funny");
  assert.deepEqual(c.fx, { stopTimer: true, feedback: { id: "f_1", switched: "ask_chip" }, chat: "heat but funny" });
  assert.deepEqual([c.state.view, c.state.deferred], ["chat", "sent"]);
  const t = deferredTimer(decide("heat but funny", { mode: "both" }).state);
  const c2 = askChip(t.state, "heat but funny");
  assert.equal(c2.fx.chat, undefined, "already asked: just the chat view");
  assert.equal(c2.state.view, "chat");
});

test("deferred: a hit opened after Back on a cancelled both, then the chip: the chat is asked now", () => {
  const o = opened(decide("heat but funny", { mode: "both" }).state);
  assert.equal(askChip(o.state, "heat but funny").fx.chat, "heat but funny");
  assert.equal(askChip(o.state, "heat but sad").fx.chat, "heat but sad", "the words now in the box");
});

test("typing other words while a find is out abandons it: no longer deciding, seq moves on, its late answer is dropped", () => {
  const a = submit(emptyFind(), "heat", T0);
  assert.deepEqual(typing(a.state, "heat").fx, {}, "same words: the find stands");
  const t = typing(a.state, "heat 19");
  assert.deepEqual(t.fx, { abortFind: true });
  assert.deepEqual([t.state.deciding, t.state.seq], [false, a.state.seq + 1]);
  const late = decided(t.state, a.state.seq, ok({ mode: "chat", search: null }), T0 + 100);
  assert.equal(late.state, t.state);
  assert.deepEqual(late.fx, {}, "no chat, no box wipe");
});

test("an emptied box (Escape) or a picked chip's words abandon a find out for other words", () => {
  const a = submit(emptyFind(), "heat 1995", T0);
  assert.deepEqual(typing(a.state, "").fx, { abortFind: true }, "Escape empties the box");
  assert.deepEqual(typing(a.state, "heat").fx, { abortFind: true }, "a filter chip removed: other words");
});

test("typing other words under a both answer cancels its waiting chat; the same words do not", () => {
  const b = decide("heat but funny", { mode: "both" });
  assert.deepEqual(typing(b.state, " Heat  but funny").fx, {});
  const t = typing(b.state, "heat but");
  assert.deepEqual([t.state.deferred, t.fx], ["cancelled", { stopTimer: true }]);
  assert.deepEqual(typing(decide("heat", {}).state, "x").fx, {}, "nothing waiting");
});

test("both: the deferred turn and the chip send the server's ask.text, not the raw words", () => {
  const b = decide("Heat  but funny", { mode: "both", ask: { text: "heat but funny (norm)" } });
  assert.equal(deferredTimer(b.state).fx.chat, "heat but funny (norm)");
  assert.equal(askChip(b.state, "heat but funny").fx.chat, "heat but funny (norm)", "same words: ask.text");
  assert.equal(askChip(b.state, "heat but sad").fx.chat, "heat but sad", "words typed since: as typed");
  assert.equal(decide("x y z w", { mode: "chat", search: null, ask: { text: "x y z w!" } }).fx.chat, "x y z w!");
});

test("interrupt (speech starts, a suggestion picked): a waiting both chat is cancelled", () => {
  const b = decide("heat but funny", { mode: "both" });
  assert.deepEqual([interrupt(b.state).state.deferred, interrupt(b.state).fx], ["cancelled", { stopTimer: true }]);
  assert.deepEqual(interrupt(deferredTimer(b.state).state).fx, {});
});

test("leave: a waiting chat becomes cancelled; a find in flight becomes a Try again", () => {
  const b = decide("heat but funny", { mode: "both" });
  assert.equal(leave(b.state).deferred, "cancelled");
  const a = submit(emptyFind(), "heat", T0);
  const l = leave(a.state);
  assert.deepEqual([l.deciding, l.error], [false, true]);
  assert.deepEqual(retryFind(l).fx, { find: "heat" });
});

// ---------- feedback rules ----------

test("feedback: at most once per decision id, whatever the switch", () => {
  const s = decide("heat", {});
  const p1 = opened(s.state);
  assert.deepEqual(p1.fx.feedback, { id: "f_1", switched: "played" });
  assert.equal(opened(p1.state).fx.feedback, undefined);
  assert.equal(askChip(p1.state, "heat").fx.feedback, undefined);
  // a new decision gets its own one
  const n = decide("alien", { id: "f_2" }, { ...p1.state }, T0 + RETYPE_WINDOW_MS + 1);
  assert.deepEqual(opened(n.state).fx.feedback, { id: "f_2", switched: "played" });
});

test("retyped: a new submit within 60 s with different words; not for the same words, not after 60 s, once per id", () => {
  const s = decide("heat", {});
  assert.deepEqual(submit(s.state, "heat 1995", T0 + 30_000).fx.feedback, { id: "f_1", switched: "retyped" });
  assert.equal(submit(s.state, " HEAT ", T0 + 30_000).fx.feedback, undefined, "same words (case, spaces)");
  assert.equal(submit(s.state, "alien", T0 + RETYPE_WINDOW_MS + 1).fx.feedback, undefined, "too late");
  assert.deepEqual(submit(s.state, "alien", T0 + RETYPE_WINDOW_MS).fx.feedback?.switched, "retyped");
  const played = opened(s.state).state;
  assert.equal(submit(played, "alien", T0 + 1000).fx.feedback, undefined, "already sent for f_1");
  // no decision (a 404 fallback): nothing to report
  const fb = decided(submit(emptyFind(), "heat", T0).state, 1, { kind: "noRoute" }, T0);
  assert.equal(submit(fb.state, "alien", T0 + 1).fx.feedback, undefined);
});

test("search_chip under a chat answer: back to the plain results for the same words, chat cleared, feedback once", () => {
  const c = decide("cosy", { mode: "chat", search: null });
  const s = searchChip(c.state);
  assert.deepEqual(s.fx, { search: "cosy", resetChat: true, stopTimer: true, feedback: { id: "f_1", switched: "search_chip" } });
  assert.equal(s.state.view, "search");
  assert.equal(searchChip(s.state).fx.feedback, undefined);
});

test("ask chip on plain results (an older server, no decision): into the chat with the typed words, no feedback", () => {
  const fb = decided(submit(emptyFind(), "heat", T0).state, 1, { kind: "noRoute" }, T0);
  const c = askChip(fb.state, "heat");
  assert.deepEqual(c.fx, { stopTimer: true, chat: "heat", resetChat: true });
  assert.equal(c.state.view, "chat");
  // typed but never submitted: the box's words
  assert.equal(askChip(emptyFind(), " funny films ").fx.chat, "funny films");
});

test("chat 404 (chat off on the server): the plain results for the same words", () => {
  const c = decide("cosy", { mode: "chat", search: null });
  assert.deepEqual(chatOff(c.state).fx, { search: "cosy", resetChat: true, stopTimer: true });
  assert.equal(chatOff(c.state).state.view, "search");
});

test("Start over: back to an empty find state; a late find answer is dropped", () => {
  const a = submit(emptyFind(), "heat", T0);
  const s = startOver(a.state);
  assert.deepEqual(s.fx, { resetChat: true, stopTimer: true });
  assert.deepEqual([s.state.view, s.state.q, s.state.deciding, s.state.decisionId], ["search", "", false, null]);
  assert.deepEqual(decided(s.state, a.state.seq, ok(), T0).fx, {});
});

test("bothLine: the chat area's words", () => {
  assert.equal(bothLine("waiting", false, false), "Ask Chat about this");
  assert.equal(bothLine("sent", true, false), "Asking Chat about this…");
  assert.equal(bothLine("sent", false, true), "Ask Chat about this");
});

// ---------- the basic-mode label ----------

const resp = (o: Partial<ChatResponse> = {}): ChatResponse => ({ v: 1, rev: 1, mode: "llm", reply: "r", chips: [], cards: [], notes: [], sources: ["library"], matched: 0, partial: false, ...o });

test("basicLabel: the server's basic wins; an older server = fallback AND a typed turn; a chip op never", () => {
  const typed = submitText(emptyState(), "funny")!.body;
  const op = tapChip(emptyState(), { remove: "genre:Comedy" })!.body;
  assert.equal(basicLabel(resp({ basic: true }), typed), true);
  assert.equal(basicLabel(resp({ basic: false, mode: "fallback" }), typed), false, "the server says not basic");
  assert.equal(basicLabel(resp({ mode: "fallback" }), typed), true, "old server, typed fallback");
  assert.equal(basicLabel(resp({ mode: "fallback" }), op), false, "old server, a chip tap");
  assert.equal(basicLabel(resp({ mode: "llm" }), typed), false);
});

test("basic is parsed (only a boolean) and lands in the state with the answer", () => {
  assert.equal(parseResponse({ ...resp(), basic: true })!.basic, true);
  assert.equal("basic" in parseResponse({ ...resp(), basic: "yes" })!, false);
  assert.equal(parseResponse({ ...resp(), turn: "op" })!.turn, "op");
  const a = submitText(emptyState(), "funny")!;
  assert.equal(applyResponse(a.state, resp({ mode: "fallback" })).basic, true);
  assert.equal(applyResponse(a.state, resp({ mode: "fallback", basic: false })).basic, false);
  assert.equal(emptyState().basic, false);
});

// ---------- routes ----------

test("#/ask, #/ask/chat and old deep links land on the one box, carrying q", () => {
  for (const h of ["#/ask", "#/ask/chat", "#/ask/other", "#/ask?q=funny"]) assert.equal(parseHash(h).name, "search", h);
  assert.equal(parseHash("#/ask?q=funny").query.get("q"), "funny");
  assert.equal(parseHash("#/ask/chat?q=cosy").query.get("q"), "cosy");
  assert.equal(navActive("search"), "search");
});
