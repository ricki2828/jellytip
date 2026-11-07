import { test } from "node:test";
import assert from "node:assert/strict";
import { BATCH_MAX, PlayTracker, createQueue, detailSignal, libraryTarget, trailerSignal, watchSignal, type Signal, type WatchMeta } from "../src/lib/signals.ts";

const sig = (o: Partial<Signal> = {}): Signal => ({ type: "detail", at: 1_000_000, kind: "movie", tmdbId: 1, ...o });

test("queue: dedupes the same type + title within a minute, keeps another minute or type", () => {
  const q = createQueue();
  assert.equal(q.add(sig()), true);
  assert.equal(q.add(sig({ at: 1_010_000 })), false);
  assert.equal(q.add(sig({ at: 1_000_000 + 60_000 })), true);
  assert.equal(q.add(sig({ type: "play" })), true);
  assert.equal(q.size(), 3);
});

test("queue: an event with no id, or a bad kind, is dropped", () => {
  const q = createQueue();
  assert.equal(q.add(sig({ tmdbId: undefined })), false);
  assert.equal(q.add(sig({ kind: "x" as never })), false);
  assert.equal(q.size(), 0);
});

test("queue: batches of at most 50, sent ones leave, unsent stay", () => {
  const q = createQueue(500);
  for (let i = 0; i < 120; i++) q.add(sig({ tmdbId: i + 1 }));
  const a = q.take();
  assert.equal(a.length, BATCH_MAX);
  assert.equal(q.take(500).length, BATCH_MAX);
  q.done(a);
  assert.equal(q.size(), 70);
  assert.equal(q.take()[0].tmdbId, 51);
});

test("queue: the cap drops the oldest", () => {
  const q = createQueue(3);
  for (let i = 1; i <= 5; i++) q.add(sig({ tmdbId: i }));
  assert.deepEqual(q.take().map((s) => s.tmdbId), [3, 4, 5]);
  assert.equal(q.add(sig({ tmdbId: 1 })), true); // its dedupe key went with it
});

test("PlayTracker counts playing steps, not seeks", () => {
  const t = new PlayTracker();
  for (let s = 0; s <= 10; s++) t.tick(s);
  assert.equal(Math.round(t.playedS), 10);
  t.tick(3000); // seek
  t.tick(3001);
  assert.equal(Math.round(t.playedS), 11);
  t.seeked();
  t.tick(50);
  t.tick(51);
  assert.equal(Math.round(t.playedS), 12);
  t.tick(80, 40); // app progress events every 30 s
  assert.equal(Math.round(t.playedS), 41);
});

const film: WatchMeta = { kind: "movie", tmdbId: 9, isFilm: true, channel: false };
const AT = 5;

test("watchSignal: finish, play, skip", () => {
  assert.equal(watchSignal(film, 5400, 6000, true, AT)?.type, "finish");
  assert.equal(watchSignal(film, 5500, 6000, false, AT)?.type, "finish"); // past 90% without the end event
  assert.equal(watchSignal(film, 1800, 6000, false, AT)?.type, "play");
  const sk = watchSignal(film, 90, 6000, false, AT);
  assert.equal(sk?.type, "skip");
  assert.equal(sk?.ms, 90_000);
  assert.equal(watchSignal(film, 20, 6000, false, AT)?.type, "skip"); // a quick abandon is a skip even at 20 s
});

test("watchSignal: a short thing or an episode never reads as skip", () => {
  assert.equal(watchSignal({ ...film, isFilm: false, kind: "series" }, 90, 3000, false, AT)?.type, "play");
  assert.equal(watchSignal(film, 90, 30 * 60, false, AT)?.type, "play"); // a 30-minute film: not "bailed on a feature"
  assert.equal(watchSignal({ ...film, isFilm: false }, 30, 3000, false, AT), null);
});

test("watchSignal: a channel pick is a weaker play, never finish or skip", () => {
  const ch = { ...film, channel: true };
  assert.equal(watchSignal(ch, 40, 6000, false, AT)?.type, "play");
  assert.equal(watchSignal(ch, 6000, 6000, true, AT)?.type, "play");
  assert.equal(watchSignal(ch, 10, 6000, false, AT), null);
});

test("trailer needs 5 s, detail needs 8 s", () => {
  const m = { kind: "movie" as const, tmdbId: 3 };
  assert.equal(trailerSignal(m, 4.9, 1), null);
  assert.equal(trailerSignal(m, 5, 1)?.ms, 5000);
  assert.equal(detailSignal(m, 7999, 1), null);
  assert.equal(detailSignal(m, 9000, 1)?.type, "detail");
  assert.equal(detailSignal(m, 99 * 60_000, 1)?.ms, 10 * 60_000);
});

test("libraryTarget: films by id + tmdb, episodes by their series, other types nothing", () => {
  assert.deepEqual(libraryTarget({ Id: "f", Type: "Movie", ProviderIds: { Tmdb: "12" } }), { kind: "movie", itemId: "f", tmdbId: 12 });
  assert.deepEqual(libraryTarget({ Id: "e", Type: "Episode", SeriesId: "s" }), { kind: "series", itemId: "s" });
  assert.equal(libraryTarget({ Id: "e", Type: "Episode" }), null);
  assert.equal(libraryTarget({ Id: "x", Type: "Person" }), null);
});

import { WatchSession } from "../src/lib/signals.ts";

test("WatchSession: a finish is not followed by a skip; ends once", () => {
  const s = new WatchSession();
  const meta: WatchMeta = { kind: "movie", tmdbId: 1, isFilm: true, channel: false };
  for (let t = 0; t <= 5400; t += 2) s.tick(t, undefined, meta, 6000);
  assert.equal(s.end(true, 1)?.type, "finish");
  assert.equal(s.end(false, 2), null);
});

test("WatchSession: under 10 s is no skip; the meta from the start is kept when the screen moves on", () => {
  const s = new WatchSession();
  const a: WatchMeta = { kind: "movie", tmdbId: 1, isFilm: true, channel: false };
  s.tick(0, undefined, a, 6000);
  s.tick(2, undefined, { ...a, tmdbId: 2 }, 7000);
  assert.equal(s.end(false, 1), null);
  s.reset();
  for (let t = 0; t <= 30; t++) s.tick(t, undefined, t === 0 ? a : { ...a, tmdbId: 2 }, 6000);
  const e = s.end(false, 1)!;
  assert.equal(e.type, "skip");
  assert.equal(e.tmdbId, 1);
});
