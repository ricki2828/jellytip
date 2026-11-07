import { test } from "node:test";
import assert from "node:assert/strict";
import { allVoted, finished, minutesLeft, nextToVote, parseCode, parseSession, scoreOf, shareLink, shortlist, showCode, tally, winner, type Session } from "../src/lib/movieNight.ts";
import type { Cand } from "../src/lib/tonight.ts";
import type { Item } from "../src/api/jellyfin.ts";

const id = (n: number) => n.toString(16).padStart(32, "0");
const c = (yes: number, maybe: number, no: number) => ({ yes, maybe, no });

test("parseCode: case, spaces, dashes, links; rejects look-alikes and wrong length", () => {
  assert.equal(parseCode("k7m2qx"), "K7M2QX");
  assert.equal(parseCode(" K7M-2QX "), "K7M2QX");
  assert.equal(parseCode("K7M 2QX"), "K7M2QX");
  assert.equal(parseCode("https://x.example/app/#/movienight?join=k7m2qx"), "K7M2QX");
  assert.equal(parseCode("#/movienight?join=K7M%202QX&x=1"), "K7M2QX");
  assert.equal(parseCode("K7M2Q"), null); // short
  assert.equal(parseCode("K7M2QXX"), null); // long
  assert.equal(parseCode("K7M2Q0"), null); // 0 is not in the alphabet
  assert.equal(parseCode("K7M2QI"), null); // nor I
  assert.equal(parseCode("https://x.example/K7M2QX"), null); // a link needs join=
  assert.equal(parseCode("?join=%E0%A4%A"), null); // bad escape does not throw
  assert.equal(parseCode(""), null);
});

test("showCode and shareLink", () => {
  assert.equal(showCode("K7M2QX"), "K7M 2QX");
  assert.equal(shareLink("https://a.example/app/#/movienight?join=OLD123", "K7M2QX"), "https://a.example/app/#/movienight?join=K7M2QX");
  assert.equal(shareLink("https://a.example/app/", "K7M2QX"), "https://a.example/app/#/movienight?join=K7M2QX");
});

test("scoring: yes +2, maybe +1, no -2", () => {
  assert.equal(scoreOf(c(2, 1, 1)), 3);
  assert.equal(scoreOf(c(0, 0, 0)), 0);
});

test("tally ranks by score, then fewer no, then more yes, then host order", () => {
  const items = [id(1), id(2), id(3), id(4), id(5)];
  const rows = tally(items, {
    [id(1)]: c(1, 0, 0), // 2, no=0, yes=1
    [id(2)]: c(0, 2, 0), // 2, no=0, yes=0  -> behind id1 on yes
    [id(3)]: c(2, 0, 1), // 2, no=1 -> behind both on fewer-no
    [id(4)]: c(1, 0, 0), // identical to id1 -> host order decides (id1 first)
    // id5 absent -> zero
  });
  assert.deepEqual(rows.map((r) => r.id), [id(1), id(4), id(2), id(3), id(5)]);
  assert.equal(rows[4].score, 0);
});

test("tally: a yes+no split loses to two maybes", () => {
  const rows = tally([id(1), id(2)], { [id(1)]: c(1, 0, 1), [id(2)]: c(0, 2, 0) });
  assert.equal(rows[0].id, id(2));
});

test("tally does not mutate or reorder its input and ignores unknown ids", () => {
  const items = [id(2), id(1)];
  const rows = tally(items, { [id(9)]: c(5, 0, 0) });
  assert.deepEqual(items, [id(2), id(1)]);
  assert.deepEqual(rows.map((r) => r.id), [id(2), id(1)]);
});

test("winner: needs a yes or maybe; all-no or no votes means none", () => {
  assert.equal(winner(tally([id(1)], { [id(1)]: c(0, 0, 2) })), null);
  assert.equal(winner(tally([id(1)], {})), null);
  assert.equal(winner(tally([id(1), id(2)], { [id(2)]: c(0, 1, 0) }))?.id, id(2));
  assert.equal(winner([]), null);
});

const sess = (o: Partial<Session> = {}): Session => ({ code: "K7M2QX", status: "open", host: true, items: [id(1), id(2)], expiresAt: 10_000, mine: {}, counts: {}, members: [], ...o });
const m = (voted: number, me = false) => ({ name: "n", voted, me, host: false });

test("allVoted / finished", () => {
  assert.equal(allVoted(sess({ members: [m(2)] })), false); // alone: wait for someone
  assert.equal(allVoted(sess({ members: [m(2), m(1)] })), false);
  assert.equal(allVoted(sess({ members: [m(2), m(2)] })), true);
  assert.equal(finished(sess({ members: [m(2), m(1)] })), false);
  assert.equal(finished(sess({ status: "closed", members: [m(0)] })), true);
  assert.equal(finished(sess({ members: [m(2), m(2)] })), true);
});

test("nextToVote and minutesLeft", () => {
  assert.equal(nextToVote(sess({ mine: { [id(1)]: "yes" } })), id(2));
  assert.equal(nextToVote(sess({ mine: { [id(1)]: "yes", [id(2)]: "no" } })), null);
  assert.equal(minutesLeft(sess({ expiresAt: 1000 }), 1000 - 90), 2);
  assert.equal(minutesLeft(sess({ expiresAt: 1000 }), 5000), 0);
});

test("parseSession: accepts a server view, drops junk, rejects non-sessions", () => {
  const raw = {
    code: "K7M2QX", status: "open", host: true, items: [id(1), id(2)], expiresAt: 5, now: 1,
    mine: { [id(1)]: "yes", [id(2)]: "love", [id(9)]: "no" },
    counts: { [id(1)]: c(1, 0, 0), [id(2)]: { yes: -1 } },
    members: [{ name: "Ann", voted: 1, me: true, host: true }, null, { name: 5, voted: "x" }],
  };
  const s = parseSession(raw)!;
  assert.deepEqual(s.mine, { [id(1)]: "yes" });
  assert.deepEqual(Object.keys(s.counts), [id(1)]);
  assert.equal(s.members.length, 2);
  assert.equal(s.members[1].name, "");
  assert.equal(s.members[1].voted, 0);
  assert.equal(parseSession(null), null);
  assert.equal(parseSession({ error: "x" }), null);
  assert.equal(parseSession({ ...raw, status: "weird" }), null);
  assert.equal(parseSession({ ...raw, items: [1] }), null);
});

const cand = (key: string, o: Partial<Cand> & { type?: string } = {}): Cand => ({
  key, source: o.source ?? "rated", title: key, sub: null, reason: "", play: "", playLabel: "Play", artId: key, poster: null, minutes: 100,
  probe: { Id: key, Name: key, Type: o.type ?? "Movie" } as Item, started: o.started ?? false, rank: o.rank ?? 0,
});

test("shortlist: library films only, nothing begun, ranked, capped", () => {
  const cs = [
    cand(id(1), { source: "resume", started: true }),
    cand(id(2), { source: "nextup", started: true, type: "Episode" }),
    cand("iptv:movie:5", { source: "iptv" }),
    cand(id(3), { source: "rated", rank: 0 }),
    cand(id(4), { source: "recs", rank: 1 }),
    cand(id(5), { source: "recs", rank: 0 }),
    cand(id(6), { source: "rated", type: "Episode" }),
  ];
  assert.deepEqual(shortlist(cs).map((x) => x.key), [id(5), id(4), id(3)]);
  assert.deepEqual(shortlist(cs, 2).map((x) => x.key), [id(5), id(4)]);
  const many = Array.from({ length: 30 }, (_, i) => cand(id(i + 1), { rank: i }));
  assert.equal(shortlist(many).length, 10);
  assert.equal(shortlist(many, 99).length, 12);
});
