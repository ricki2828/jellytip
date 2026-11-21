import { test } from "node:test";
import assert from "node:assert/strict";
import { createSportQueue, shownKey, SPORT_BATCH_MAX, SPORT_QUEUE_CAP, validSportEvent, wireEvent, type SportEvent } from "../src/lib/sportEvents.ts";
import { parseFixtureList } from "../src/lib/gameCentre.ts";

const ev = (o: Partial<SportEvent> = {}): SportEvent => ({ type: "shown", fid: "f1", surface: "gamecentre", listId: "L1", ...o });

test("'shown' counts once per list load, surface and fixture; a new list (listId) counts again", () => {
  const q = createSportQueue();
  assert.equal(q.add(ev()), true);
  assert.equal(q.add(ev({ pos: 3 })), false); // same list, fixture, surface, section: not new
  assert.equal(q.add(ev({ section: "hero" })), true); // Game Centre's hero and the card are two impressions
  assert.equal(q.add(ev({ section: "hero", pos: 1 })), false);
  assert.equal(q.add(ev({ surface: "home_hero" })), true);
  assert.equal(q.add(ev({ fid: "f2" })), true);
  assert.equal(q.add(ev({ listId: "L2" })), true); // the list was reloaded
  assert.equal(q.size(), 5);
  // without a listId (server before the field): once for this load
  assert.equal(q.add(ev({ listId: undefined })), true);
  assert.equal(q.add(ev({ listId: undefined })), false);
});

test("presses are never deduped: two watches are two events; clear() forgets the shown keys", () => {
  const q = createSportQueue();
  q.add(ev({ type: "watch" }));
  q.add(ev({ type: "watch" }));
  q.add(ev({ type: "remind" }));
  assert.equal(q.size(), 3);
  q.add(ev());
  q.clear();
  assert.equal(q.size(), 0);
  assert.equal(q.add(ev()), true);
});

test("invalid events are dropped: unknown type or surface, empty fid, bad pos / ms", () => {
  assert.equal(validSportEvent(ev()), true);
  for (const bad of [ev({ type: "liked" as never }), ev({ surface: "tv" as never }), ev({ fid: "" }), ev({ pos: -1 }), ev({ pos: 1.5 }), ev({ ms: Number.NaN })]) assert.equal(validSportEvent(bad), false, JSON.stringify(bad));
  for (const t of ["shown", "opened", "watch", "remind", "record", "not_interested", "kickoff_ok", "kickoff_back"] as const) assert.equal(validSportEvent(ev({ type: t })), true, t);
  for (const s of ["gamecentre", "home_hero", "home_row", "kickoff_card", "player_switcher", "search", "web_fixtures"] as const) assert.equal(validSportEvent(ev({ surface: s })), true, s);
});

test("batches are at most 50; sent events leave the queue; the cap keeps the newest", () => {
  const q = createSportQueue();
  for (let i = 0; i < 120; i++) q.add(ev({ type: "opened", pos: i }));
  const b = q.take();
  assert.equal(b.length, SPORT_BATCH_MAX);
  assert.equal(q.take(500).length, SPORT_BATCH_MAX);
  q.done(b);
  assert.equal(q.size(), 70);
  assert.equal(q.take()[0].pos, 50);
  const small = createSportQueue(3);
  for (let i = 0; i < 5; i++) small.add(ev({ type: "opened", pos: i }));
  assert.deepEqual(small.take().map((e) => e.pos), [2, 3, 4]);
  assert.equal(SPORT_QUEUE_CAP, 200);
});

test("the wire form carries only the contract's fields, empty ones dropped, ms rounded", () => {
  assert.deepEqual(wireEvent({ ...ev({ section: "live", pos: 0, at: 1700000000000, ms: 1234.6 }), extra: 1 } as SportEvent), { type: "shown", fid: "f1", surface: "gamecentre", section: "live", pos: 0, listId: "L1", at: 1700000000000, ms: 1235 });
  assert.deepEqual(wireEvent({ type: "kickoff_ok", fid: "f9", surface: "kickoff_card", listId: undefined, section: "" }), { type: "kickoff_ok", fid: "f9", surface: "kickoff_card" });
  assert.equal(shownKey({ fid: "f", surface: "gamecentre" }), "-|gamecentre||f");
  assert.equal(shownKey({ fid: "f", surface: "gamecentre", section: "live", listId: "L" }), "L|gamecentre|live|f");
});

test("the fixtures answer's listId and ranker reach every item; absent = null", () => {
  const row = (fid: string) => ({ fid, league: "NRL", kickoff: 1000, status: "pre", home: "H", away: "A", channels: [] });
  const a = parseFixtureList({ listId: "abc123", ranker: "v1", items: [row("a"), row("b")] });
  assert.equal(a.listId, "abc123");
  assert.equal(a.ranker, "v1");
  assert.deepEqual(a.items.map((f) => f.listId), ["abc123", "abc123"]);
  const b = parseFixtureList({ items: [row("a")] });
  assert.equal(b.listId, null);
  assert.equal(b.items[0].listId, null);
});
