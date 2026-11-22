import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  alignCards, applyError, applyResponse, cardKeyOf, ChatSender, isIptvCard, libraryIds, scopeHint, displayChips, dismissNotice, emptyState, isServerChip, MAX_TEXT, MAX_TURNS, noticeSeen, NOTICE_KEY, parseResponse,
  refineChat, retry, submitText, tapChip, TOO_LONG_TEXT, type ChatBody, type ChatResponse, type ChatState, type Op, type ServerChip,
} from "../src/lib/askChat.ts";
import { chipFor } from "../src/lib/askParse.ts";
import type { Item } from "../src/api/jellyfin.ts";
import { parseHash } from "../src/lib/routeParse.ts";

const live = JSON.parse(readFileSync(new URL("./fixtures/askchat-live-20261122.json", import.meta.url), "utf8")) as Record<string, { status: number; body: ChatBody; resp: unknown }>;
const NOW = new Date("2026-11-22T00:00:00Z");
const resp = (o: Partial<ChatResponse> = {}): ChatResponse => ({ v: 1, rev: 1, mode: "fallback", reply: "r", chips: [], cards: [], notes: [], sources: ["library"], matched: 0, partial: false, ...o });
const send = (s: ChatState, text: string) => submitText(s, text)!;
const tap = (s: ChatState, op: Op) => tapChip(s, op)!;
const ids = (xs: { id: string }[]) => xs.map((x) => x.id);
const card = (id: string) => ({ kind: "film" as const, source: "library" as const, id, title: id });

test("submitText: rev+1, pending, text trimmed and capped, nothing for empty text", () => {
  const s0 = emptyState();
  assert.equal(submitText(s0, "   "), null);
  const { state, body } = send(s0, "  funny  films  ");
  assert.equal(body.rev, 1);
  assert.equal(body.text, "funny films");
  assert.equal(state.rev, 1);
  assert.ok(state.pending);
  assert.equal(send(s0, "x".repeat(500)).body.text!.length, MAX_TEXT);
  assert.deepEqual([body.v, body.turns, body.chips, body.removed], [1, [], [], []]);
});

test("pending hides nothing in the state but the view rule is explicit: pending = no cards to show", () => {
  const s = applyResponse(send(emptyState(), "funny").state, resp({ cards: [card("a"), card("b")], chips: [chipFor({ t: "genre", genres: ["Comedy"] })] }));
  assert.equal(s.cards.length, 2);
  const p = send(s, "kids").state;
  assert.ok(p.pending, "the screen shows a skeleton while pending, whatever cards the state still holds");
});

test("pending shows the local reading as provisional chips on top of the current ones", () => {
  let s = applyResponse(send(emptyState(), "x").state, resp({ chips: [chipFor({ t: "genre", genres: ["Comedy"] })] }));
  s = send(s, "under 90 minutes").state;
  const shown = displayChips(s, NOW);
  assert.deepEqual(ids(shown), ["genre:Comedy", "maxrt:90"]);
  assert.equal((shown[0] as { provisional?: boolean }).provisional, undefined);
  assert.equal((shown[1] as { provisional?: boolean }).provisional, true);
  assert.ok(!("pos" in shown[1]) && !("src" in shown[1]));
  assert.deepEqual(ids(displayChips(applyResponse(s, resp({ rev: 2, chips: [] })), NOW)), []);
});

test("pending op add shows the chip provisional; pending op remove drops it at once", () => {
  const base = applyResponse(send(emptyState(), "x").state, resp({ chips: [chipFor({ t: "genre", genres: ["Comedy"] }), chipFor({ t: "kind", kind: "Movie" })] }));
  const add = tap(base, { add: { t: "unplayed" } }).state;
  assert.deepEqual(ids(displayChips(add)), ["genre:Comedy", "kind:Movie", "unplayed"]);
  const rem = tap(base, { remove: "kind:Movie" }).state;
  assert.deepEqual(ids(displayChips(rem)), ["genre:Comedy"]);
  assert.deepEqual(ids(base.chips), ["genre:Comedy", "kind:Movie"], "the state itself still holds the server's chips");
});

test("rev rule: an older response is dropped, the latest is applied", () => {
  const a = send(emptyState(), "funny");
  const first = applyResponse(a.state, resp({ rev: 1, reply: "one", cards: [card("one")] }));
  const b = send(first, "scary");
  assert.equal(b.body.rev, 2);
  const late = applyResponse(b.state, resp({ rev: 1, reply: "OLD", cards: [card("old")] }));
  assert.equal(late, b.state, "rev 1 after rev 2 was issued: dropped (same object)");
  const ok = applyResponse(b.state, resp({ rev: 2, reply: "NEW", cards: [card("new")] }));
  assert.equal(ok.reply, "NEW");
  assert.deepEqual(ids(ok.cards), ["new"]);
  assert.equal(ok.pending, null);
  assert.equal(applyResponse(ok, resp({ rev: 2, reply: "AGAIN" })), ok, "a duplicate answer is ignored (nothing pending)");
  assert.equal(applyResponse(a.state, resp({ rev: 9 })), a.state, "a rev from the future is not applied either");
});

test("a second send or tap while a turn is pending is refused (no body); after the answer it builds from the NEW server state", () => {
  const first = send(emptyState(), "here with my kids");
  assert.equal(submitText(first.state, "something funny"), null, "second text refused");
  assert.equal(tapChip(first.state, { remove: "kids" }), null, "tap refused");
  assert.equal(tapChip(first.state, { add: { t: "unplayed" } }), null);
  assert.equal(first.state.rev, 1, "nothing was issued");
  const kids = { ...chipFor({ t: "kids", ratings: ["G", "PG"], genres: ["Family"] }), removable: true, protected: true };
  const answered = applyResponse(first.state, resp({ rev: 1, chips: [kids], reply: "Family picks" }));
  const second = send(answered, "something funny");
  assert.equal(second.body.rev, 2);
  assert.deepEqual(ids(second.body.chips), ["kids"], "kids is in the echoed state of the second turn");
  assert.deepEqual(second.body.turns, [{ user: "here with my kids", reply: "Family picks" }]);
  assert.ok(tapChip(answered, { remove: "kids" }), "a tap on a settled state is fine");
});

test("applyResponse replaces chips, cards, notes, mode, sources wholesale; the turn is recorded", () => {
  const s1 = applyResponse(send(emptyState(), "funny").state, resp({ reply: "Comedy", mode: "llm", chips: [chipFor({ t: "genre", genres: ["Comedy"] })], cards: [card("a")], notes: [{ text: "n", code: "c" }], sources: ["library"] }));
  assert.deepEqual(s1.turns, [{ user: "funny", reply: "Comedy" }]);
  const s2 = applyResponse(send(s1, "shorter").state, resp({ rev: 2, reply: "Comedy · short", mode: "fallback", chips: [chipFor({ t: "maxRuntime", min: 90 })], cards: [card("b"), card("c")], notes: [], sources: ["library", "iptv"] }));
  assert.deepEqual(ids(s2.chips), ["maxrt:90"], "chips replaced, not merged");
  assert.deepEqual(ids(s2.cards), ["b", "c"]);
  assert.deepEqual([s2.notes, s2.mode, s2.sources, s2.reply], [[], "fallback", ["library", "iptv"], "Comedy · short"]);
  assert.equal(s2.turns.length, 2);
});

test("turns: at most 6 are kept and sent", () => {
  let s = emptyState();
  for (let i = 1; i <= 9; i++) {
    const r = send(s, `t${i}`);
    assert.ok(r.body.turns.length <= MAX_TURNS);
    s = applyResponse(r.state, resp({ rev: i, reply: `r${i}` }));
  }
  assert.equal(s.turns.length, MAX_TURNS);
  assert.deepEqual(s.turns.map((t) => t.user), ["t4", "t5", "t6", "t7", "t8", "t9"]);
  assert.equal(send(s, "next").body.turns.length, MAX_TURNS);
});

test("an op turn restates the last reply and adds no turn; a removed id is remembered and a re-added one forgotten", () => {
  let s = applyResponse(send(emptyState(), "kids film").state, resp({ reply: "Family", chips: [chipFor({ t: "kind", kind: "Movie" })] }));
  const r = tap(s, { remove: "kind:Movie" });
  assert.equal(r.body.op && "remove" in r.body.op && r.body.op.remove, "kind:Movie");
  s = applyResponse(r.state, resp({ rev: 2, reply: "Family (all kinds)", chips: [] }));
  assert.deepEqual(s.turns, [{ user: "kids film", reply: "Family (all kinds)" }]);
  assert.deepEqual(s.removed, ["kind:Movie"]);
  assert.deepEqual(send(s, "x").body.removed, ["kind:Movie"], "removed is echoed");
  s = applyResponse(tap(s, { add: { t: "kind", kind: "Movie" } }).state, resp({ rev: 3, chips: [chipFor({ t: "kind", kind: "Movie" })] }));
  assert.deepEqual(s.removed, [], "adding it back clears it from removed");
});

test("a server chip round-trips byte-identical, and native chips are echoed verbatim too", () => {
  const ns: ServerChip = { id: "noscary", label: "nothing scary", server: true, data: { t: "noscary" }, removable: true };
  const mood: ServerChip = { id: "mood:heist", label: "heist", server: true, data: { t: "mood", key: "heist" }, removable: true, applied: false };
  const kids = { ...chipFor({ t: "kids", ratings: ["G", "PG"], genres: ["Family"] }), removable: true, protected: true };
  const s = applyResponse(send(emptyState(), "x").state, resp({ chips: [ns, kids, mood] }));
  const body = send(s, "more").body;
  assert.equal(JSON.stringify(body.chips), JSON.stringify([ns, kids, mood]));
  assert.ok(isServerChip(body.chips[0]) && !isServerChip(body.chips[1]));
});

const err = (s: ChatState, rev: number, status: number) => applyError(s, rev, status);

test("errors: 404 = off (classic Ask), 401 / 403 = signed out, 0 / 5xx / 429 = unreachable + retry", () => {
  const good = applyResponse(send(emptyState(), "funny").state, resp({ reply: "good", cards: [card("a")], chips: [chipFor({ t: "genre", genres: ["Comedy"] })] }));
  const p = send(good, "scary");
  assert.equal(err(p.state, 2, 404).state.error, "off");
  assert.equal(err(p.state, 2, 401).state.error, "signedOut");
  assert.equal(err(p.state, 2, 403).state.error, "signedOut");
  for (const st of [0, 500, 502, 503, 504, 429]) {
    const { state: e, resend } = err(p.state, 2, st);
    assert.equal(e.error, "unreachable", String(st));
    assert.equal(resend, null);
    assert.equal(e.pending, null);
    assert.equal(e.reply, "good");
    assert.deepEqual(ids(e.chips), ["genre:Comedy"]);
    assert.equal(e.turns.length, 1, "the failed turn is not recorded");
  }
  assert.equal(err(p.state, 1, 503).state, p.state, "an error for an older rev is ignored");
  const e = err(p.state, 2, 503).state;
  const r = retry(e)!;
  assert.deepEqual(r.body, p.body, "retry re-sends the same body (same rev)");
  assert.ok(r.state.pending && r.state.error === null);
  assert.equal(retry(good), null);
  assert.equal(retry(err(p.state, 2, 404).state), null);
  const ok = applyResponse(r.state, resp({ rev: 2, reply: "recovered", cards: [card("z")] }));
  assert.equal(ok.reply, "recovered");
  assert.deepEqual(ids(ok.cards), ["z"]);
});

test("unreachable: the previous cards stay hidden (error + Retry only) until a newer response is applied", () => {
  const good = applyResponse(send(emptyState(), "kids film").state, resp({ reply: "good", cards: [card("a"), card("b")], notes: [{ text: "n", code: "c" }], matched: 9 }));
  const p = send(good, "something funny");
  const e = err(p.state, 2, 503).state;
  assert.deepEqual([e.cards, e.notes, e.matched], [[], [], 0], "cards from the superseded reading do not reappear");
  assert.equal(e.error, "unreachable");
  const again = err(retry(e)!.state, 2, 0).state;
  assert.deepEqual(again.cards, []);
  const ok = applyResponse(retry(e)!.state, resp({ rev: 2, cards: [card("c")] }));
  assert.deepEqual(ids(ok.cards), ["c"]);
});

test("400 / 413: the oldest turns are trimmed and the request re-sent once; then a plain 'shorter message'", () => {
  let s = emptyState();
  for (let i = 1; i <= 6; i++) s = applyResponse(send(s, `t${i}`).state, resp({ rev: i, reply: `r${i}` }));
  const p = send(s, "seventh");
  assert.equal(p.body.turns.length, 6);
  for (const st of [400, 413]) {
    const { state, resend } = err(p.state, 7, st);
    assert.ok(resend, String(st));
    assert.equal(resend!.rev, 7, "same rev");
    assert.deepEqual(resend!.turns.map((t) => t.user), ["t4", "t5", "t6"], "the oldest half is gone");
    assert.equal(resend!.text, "seventh");
    assert.ok(state.pending && state.pending.trimmed && state.error === null);
    const second = err(state, 7, st);
    assert.equal(second.resend, null, "only one re-send");
    assert.equal(second.state.error, "tooLong");
    assert.equal(second.state.pending, null);
    assert.deepEqual([second.state.cards, second.state.notes, second.state.matched], [[], [], 0], "cards of the superseded reading are hidden here too");
    assert.equal(second.state.reply, state.reply, "the last good chips / reply stay");
    assert.equal(retry(second.state), null, "no Retry of the same body");
  }
  // one turn: dropping it is fine, and the re-send still carries the current text and chips
  const oneTurn = applyResponse(send(emptyState(), "first").state, resp({ rev: 1, reply: "r1", chips: [chipFor({ t: "genre", genres: ["Comedy"] })] }));
  const q = send(oneTurn, "second");
  const rs = err(q.state, 2, 413).resend!;
  assert.deepEqual([rs.turns, rs.text, ids(rs.chips), rs.rev], [[], "second", ["genre:Comedy"], 2]);
  // nothing to trim: straight to the message
  const bare = send(emptyState(), "x");
  assert.equal(err(bare.state, 1, 413).state.error, "tooLong");
  assert.equal(err(bare.state, 1, 413).resend, null);
  assert.ok(TOO_LONG_TEXT.includes("shorter message"));
  // a trimmed re-send that succeeds is applied normally
  const ok = applyResponse(err(p.state, 7, 400).state, resp({ rev: 7, reply: "fine" }));
  assert.equal(ok.reply, "fine");
});

test("an unknown add op never reaches a pending state or a skeleton", () => {
  const s = applyResponse(send(emptyState(), "x").state, resp({}));
  assert.equal(tapChip(s, { add: { t: "mystery" } as never }), null);
  assert.equal(tapChip(s, { add: null as never }), null);
  assert.equal(parseResponse({ ...(live.paul_rudd.resp as object), notes: [{ text: "n", code: "c", actions: [{ label: "bad", op: { add: { t: "mystery" } } }, { label: "ok", op: { add: { t: "unplayed" } } }] }] })!.notes[0].actions!.length, 1);
  const weird = applyResponse({ ...send(s, "y").state, pending: { body: { ...send(s, "y").body, text: undefined, op: { add: { t: "mystery" } as never } } } }, resp({ rev: 2 }));
  assert.equal(weird.pending, null, "applying a response never throws on an odd pending op");
  assert.deepEqual(ids(displayChips({ ...s, pending: { body: { v: 1, rev: 2, op: { add: { t: "mystery" } as never }, turns: [], chips: [], removed: [] } } })), []);
});

test("ChatSender: a newer request aborts the one in flight; the aborted answer is never applied", async () => {
  const sigs: AbortSignal[] = [];
  const release: (() => void)[] = [];
  const call = (_m: "POST", _p: string, _b: unknown, o: { signal?: AbortSignal }) =>
    new Promise<{ status: number; data: unknown }>((res) => {
      sigs.push(o.signal!);
      release.push(() => res({ status: 200, data: { ...(live.headline.resp as object), rev: sigs.length } }));
    });
  const sender = new ChatSender(call);
  const b1 = send(emptyState(), "one").body;
  const p1 = sender.send(b1);
  const p2 = sender.send({ ...b1, rev: 2 });
  assert.equal(sigs[0].aborted, true);
  assert.equal(sigs[1].aborted, false);
  release[0](); release[1]();
  assert.deepEqual(await p1, { kind: "aborted" });
  const r2 = await p2;
  assert.equal(r2.kind, "ok");
});

test("ChatSender: status mapping, bad bodies, warm never throws", async () => {
  const mk = (status: number, data: unknown) => new ChatSender(async () => ({ status, data }));
  const b = send(emptyState(), "x").body;
  assert.deepEqual(await mk(404, null).send(b), { kind: "error", status: 404 });
  assert.deepEqual(await mk(0, null).send(b), { kind: "error", status: 0 });
  assert.deepEqual(await mk(200, { nope: 1 }).send(b), { kind: "error", status: 500 });
  const posts: string[] = [];
  new ChatSender(async (_m, p) => { posts.push(p); throw new Error("boom"); }).warm();
  assert.deepEqual(posts, ["v1/ask/chat/warm"]);
});

test("live fixtures: every response parses and applies exactly as given", () => {
  for (const [name, f] of Object.entries(live)) {
    const r = parseResponse(f.resp);
    assert.ok(r, name);
    const raw = f.resp as { chips: unknown[]; cards: { id: string }[]; notes: unknown[]; mode: string; sources: string[]; reply: string };
    let s = emptyState();
    s = { ...s, rev: f.body.rev - 1 };
    const sub = f.body.text ? submitText(s, f.body.text)! : tap(s, f.body.op!);
    const out = applyResponse(sub.state, r!);
    assert.equal(out.rev, f.body.rev, name);
    assert.deepEqual(out.chips, raw.chips, `${name}: chips exactly as given`);
    assert.deepEqual(ids(out.cards), raw.cards.map((c) => c.id), `${name}: card ids in server order`);
    assert.deepEqual(out.notes, raw.notes, `${name}: notes`);
    assert.equal(out.mode, raw.mode, name);
    assert.deepEqual(out.sources, raw.sources, name);
    assert.equal(out.reply, raw.reply, name);
  }
});

test("live: the headline kids answer", () => {
  const f = live.headline;
  const out = applyResponse(submitText(emptyState(), f.body.text!)!.state, parseResponse(f.resp)!);
  assert.equal(out.mode, "llm");
  assert.equal(out.reply, "Family picks · PG or under · no Animation · nothing scary");
  assert.deepEqual(ids(out.chips), ["notgenre:Animation", "kids", "noscary"]);
  const kids = out.chips.find((c) => c.id === "kids")!;
  assert.equal((kids as { protected?: boolean }).protected, true);
  const ns = out.chips.find((c) => c.id === "noscary")!;
  assert.ok(isServerChip(ns));
  assert.equal(out.cards.length, 24);
  assert.equal(out.matched, 54);
});

test("live: the Paul Rudd no-results case, then the echoed state goes back verbatim", () => {
  const head = applyResponse(submitText(emptyState(), live.headline.body.text!)!.state, parseResponse(live.headline.resp)!);
  const r2 = submitText(head, "something with Paul Rudd")!;
  assert.deepEqual(r2.body.turns, [{ user: live.headline.body.text, reply: "Family picks · PG or under · no Animation · nothing scary" }]);
  assert.equal(JSON.stringify(r2.body.chips), JSON.stringify((live.headline.resp as { chips: unknown[] }).chips));
  const out = applyResponse(r2.state, parseResponse(live.paul_rudd.resp)!);
  assert.deepEqual(out.cards, []);
  assert.equal(out.notes[0].code, "no_results");
});

test("notes with actions parse (up to 3, valid ops only) and an action is a tap", () => {
  const p = parseResponse({ ...(live.paul_rudd.resp as object), notes: [{ text: "Nothing fits all of that. Try dropping one:", code: "no_results", actions: [
    { label: "Drop “with Paul Rudd”", op: { remove: "person:3e909596adb405578ff2191e296d5826" } }, { label: "Drop the kids filter", op: { remove: "kids" } },
    { label: "bad", op: { remove: "" } }, { label: "bad2" }, { label: "x", op: { add: { t: "unplayed" } } }, { label: "y", op: { remove: "z" } }, { label: "z", op: { remove: "w" } },
  ] }] })!;
  const acts = p.notes[0].actions!;
  assert.equal(acts.length, 3);
  assert.deepEqual(acts.map((a) => a.label), ["Drop “with Paul Rudd”", "Drop the kids filter", "x"]);
  const t = tap(applyResponse(submitText(emptyState(), "x")!.state, { ...p, rev: 1 }), acts[1].op);
  assert.deepEqual(t.body.op, { remove: "kids" });
});

test("parseResponse: rejects non-answers, drops malformed parts", () => {
  assert.equal(parseResponse(null), null);
  assert.equal(parseResponse({ v: 2, rev: 1, mode: "llm", reply: "" }), null);
  assert.equal(parseResponse({ v: 1, rev: 1, mode: "weird", reply: "" }), null);
  const r = parseResponse({ v: 1, rev: 3, mode: "llm", reply: "r", chips: [{ id: "a" }, null, { id: "b", label: "B", data: { t: "x" } }], cards: [{ id: "c", source: "library", kind: "film" }, { id: 5 }, { id: "d", source: "iptv", kind: "film" }], notes: [{ text: 1 }, { text: "ok", code: "c" }] })!;
  assert.deepEqual([ids(r.chips), ids(r.cards), r.notes.length, r.sources], [["b"], ["c"], 1, []]);
});

test("cards: a fetch that drops an id leaves a gap, never a substitute; server order wins", () => {
  const cards = ["a", "b", "c", "d"].map(card);
  const it = (id: string) => ({ Id: id.toUpperCase(), Name: id, Type: "Movie" }) as Item;
  const got = alignCards(cards, [it("d"), it("a"), it("x"), it("c")]);
  assert.deepEqual(got.map((g) => g.card.id), ["a", "c", "d"], "b is a gap; x (not asked for) never shows; order = the server's");
  assert.equal(alignCards(cards, []).length, 0);
  const dashed = { Id: "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", Name: "z", Type: "Movie" } as Item;
  assert.equal(alignCards([card("0a1b2c3d4e5f60718293a4b5c6d7e8f9")], [dashed]).length, 1, "dashed ids match");
});

test("refine: native chips only, never one already applied", () => {
  const mk = (n: number, g: string[], extra: Partial<Item> = {}) => ({ Id: `i${n}`, Name: `m${n}`, Type: "Movie", Genres: g, RunTimeTicks: 100 * 600_000_000, ProductionYear: 1990 + n, ...extra }) as Item;
  const items = [mk(1, ["Comedy", "Family"]), mk(2, ["Comedy", "Family"]), mk(3, ["Comedy", "Drama"]), mk(4, ["Comedy"]), mk(5, ["Drama"])];
  const ns: ServerChip = { id: "noscary", label: "nothing scary", server: true, data: { t: "noscary" } };
  const out = refineChat(items, [], [ns, chipFor({ t: "kind", kind: "Movie" })]);
  assert.ok(out.length > 0);
  assert.ok(out.every((c) => c.id !== "kind:Movie"));
  assert.ok(out.some((c) => c.id === "genre:Comedy"));
});

test("notice: once per device, in localStorage", () => {
  const mem = new Map<string, string>();
  const st = { getItem: (k: string) => mem.get(k) ?? null, setItem: (k: string, v: string) => void mem.set(k, v) };
  assert.equal(noticeSeen(st), false);
  dismissNotice(st);
  assert.equal(noticeSeen(st), true);
  assert.equal(mem.get(NOTICE_KEY), "1");
  assert.equal(noticeSeen({ getItem: () => { throw new Error("blocked"); }, setItem: () => {} }), false);
  assert.doesNotThrow(() => dismissNotice({ getItem: () => null, setItem: () => { throw new Error("full"); } }));
});

test("routes: #/ask/chat is the chat screen, #/ask stays classic Ask", () => {
  assert.equal(parseHash("#/ask/chat").name, "askchat");
  assert.equal(parseHash("#/ask").name, "ask");
  assert.equal(parseHash("#/ask?x=1").name, "ask");
  assert.equal(parseHash("#/ask/other").name, "ask");
});

test("ChatSender: a call that never answers times out as error 0 (pending can clear), the signal is aborted", async () => {
  let sig: AbortSignal | undefined;
  const sender = new ChatSender((_m, _p, _b, o) => { sig = o.signal; return new Promise(() => {}); }, 30);
  const body = send(emptyState(), "x");
  const t0 = Date.now();
  const out = await sender.send(body.body);
  assert.deepEqual(out, { kind: "error", status: 0 });
  assert.ok(Date.now() - t0 >= 25 && Date.now() - t0 < 1000);
  assert.equal(sig!.aborted, true);
  const s = applyError(body.state, 1, (out as { status: number }).status).state;
  assert.equal(s.pending, null);
  assert.equal(s.error, "unreachable");
  assert.ok(retry(s), "Retry is offered after a timeout");
});

test("ChatSender: a superseded request is 'aborted' (never an error), even if it would later time out; a throwing call is error 0", async () => {
  const sender = new ChatSender(() => new Promise(() => {}), 40);
  const b1 = send(emptyState(), "one").body;
  const p1 = sender.send(b1);
  const p2 = sender.send({ ...b1, rev: 2 });
  assert.deepEqual(await p1, { kind: "aborted" });
  assert.deepEqual(await p2, { kind: "error", status: 0 });
  assert.deepEqual(await new ChatSender(async () => { throw new Error("net"); }).send(b1), { kind: "error", status: 0 });
});

test("ChatSender: a 200 for another rev than the body that was sent is an error, not a silent drop", async () => {
  const body = send(emptyState(), "x");
  const good = live.headline.resp as ChatResponse;
  const mk = (rev: number) => new ChatSender(async () => ({ status: 200, data: { ...good, rev } }));
  assert.equal((await mk(body.body.rev).send(body.body)).kind, "ok");
  const bad = await mk(99).send(body.body);
  assert.deepEqual(bad, { kind: "error", status: 500 });
  const st = applyError(body.state, 1, 500).state;
  assert.equal(st.pending, null);
  assert.equal(st.error, "unreachable");
});

test("every terminal path leaves pending null (no request can freeze the screen)", async () => {
  const good = live.headline.resp as ChatResponse;
  const base = applyResponse(send(emptyState(), "first").state, resp({ rev: 1, reply: "r", chips: [chipFor({ t: "genre", genres: ["Comedy"] })], cards: [card("a")] }));
  // what the screen does with a Sent: apply, and re-send once on a resend
  const settle = async (mkCall: () => ConstructorParameters<typeof ChatSender>[0]) => {
    let { state, body } = send(base, "second");
    for (let i = 0; i < 3; i++) {
      const out = await new ChatSender(mkCall(), 30).send(body);
      if (out.kind === "aborted") throw new Error("a single request is never superseded");
      if (out.kind === "ok") return applyResponse(state, out.resp);
      const e = applyError(state, body.rev, out.status);
      state = e.state;
      if (!e.resend) return state;
      body = e.resend;
    }
    throw new Error("re-sent more than once");
  };
  const classes: [string, () => ConstructorParameters<typeof ChatSender>[0]][] = [
    ["200 ok", () => async () => ({ status: 200, data: { ...good, rev: 2 } })],
    ["200 wrong rev", () => async () => ({ status: 200, data: { ...good, rev: 7 } })],
    ["200 garbage", () => async () => ({ status: 200, data: { nope: 1 } })],
    ["never answers", () => () => new Promise(() => {})],
    ["throws", () => async () => { throw new Error("x"); }],
    ...[0, 400, 401, 403, 404, 413, 429, 500, 502, 503, 504].map((st): [string, () => ConstructorParameters<typeof ChatSender>[0]] => [`status ${st}`, () => async () => ({ status: st, data: null })]),
  ];
  for (const [name, mk] of classes) {
    const end = await settle(mk);
    assert.equal(end.pending, null, name);
  }
});

// ---------- stage 4: TV (IPTV) cards ----------
const tv = (iptvId: string, o: Record<string, unknown> = {}) => ({ kind: "film" as const, source: "iptv" as const, iptvId, title: `TV ${iptvId}`, year: 2001, tmdbId: 1, rating: 7.1, poster: "https://image.tmdb.org/t/p/w342/p.jpg", backdrop: null, quality: ["1080p"], tech: ["ATMOS"], catalog: false, certification: "PG", runtime: 98, audio: "atmos", ...o });
const mixed = () => [card("a"), tv("p1:m:1"), card("b"), tv("p1:s:2", { kind: "series" }), card("c")];

test("mixed response: library and TV cards parse and keep the server's order", () => {
  const raw = { ...(live.headline.resp as object), rev: 1, sources: ["library", "iptv"], cards: mixed() };
  const r = parseResponse(raw)!;
  assert.deepEqual(r.cards.map(cardKeyOf), ["a", "iptv:p1:m:1", "b", "iptv:p1:s:2", "c"]);
  assert.deepEqual(r.sources, ["library", "iptv"]);
  const s = applyResponse(submitText(emptyState(), "x")!.state, r);
  assert.deepEqual(s.cards.map(cardKeyOf), ["a", "iptv:p1:m:1", "b", "iptv:p1:s:2", "c"]);
  assert.equal(isIptvCard(s.cards[1]), true);
  assert.equal(isIptvCard(s.cards[0]), false);
});

test("TV cards need an iptvId; malformed ones are dropped, library cards still need an id", () => {
  const r = parseResponse({ ...(live.headline.resp as object), cards: [tv("p1:m:1"), { ...tv("x"), iptvId: undefined }, { ...tv("y"), iptvId: "" }, { source: "iptv", kind: "film" }, { kind: "film", source: "library" }, { ...tv("z"), kind: "show" }, card("a")] })!;
  assert.deepEqual(r.cards.map(cardKeyOf), ["iptv:p1:m:1", "a"]);
});

test("alignCards: a library id the fetch drops is a gap; TV cards are never dropped and need no Item; order kept", () => {
  const it = (id: string) => ({ Id: id, Name: id, Type: "Movie" }) as Item;
  const got = alignCards(mixed() as never, [it("c"), it("a")]);
  assert.deepEqual(got.map((g) => cardKeyOf(g.card)), ["a", "iptv:p1:m:1", "iptv:p1:s:2", "c"], "b is a gap; the TV cards stay at their places");
  assert.deepEqual(got.map((g) => !!g.item), [true, false, false, true]);
  assert.deepEqual(alignCards(mixed() as never, []).map((g) => cardKeyOf(g.card)), ["iptv:p1:m:1", "iptv:p1:s:2"], "nothing fetched: only the TV cards (never a substitute for the library ones)");
  assert.deepEqual(alignCards([tv("p1:m:9")] as never, []).length, 1);
});

test("libraryIds: only library ids are fetched from Jellyfin", () => {
  assert.deepEqual(libraryIds(mixed() as never), ["a", "b", "c"]);
  assert.deepEqual(libraryIds([tv("p1:m:1")] as never), []);
});

test("scope hint from the sources actually searched", () => {
  assert.equal(scopeHint(["library"]), "Library");
  assert.equal(scopeHint(["library", "iptv"]), "Library + TV");
  assert.equal(scopeHint(["iptv"]), "TV");
  assert.equal(scopeHint([]), "");
  assert.equal(scopeHint(["iptv", "library"]), "Library + TV");
});

test("a TV-only turn: no library ids, the live fixtures' library-only answers are unchanged", () => {
  const only = parseResponse({ ...(live.headline.resp as object), cards: [tv("p1:m:1"), tv("p1:m:2")], sources: ["iptv"] })!;
  assert.deepEqual(libraryIds(only.cards), []);
  for (const f of Object.values(live)) assert.ok(parseResponse(f.resp)!.cards.every((c) => !isIptvCard(c)), "library-only responses still parse as library cards");
});
