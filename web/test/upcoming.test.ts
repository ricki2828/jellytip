import { test } from "node:test";
import assert from "node:assert/strict";
import { groupByDay, isLive, parseUpcoming, timeChip } from "../src/lib/upcoming.ts";

const ch = (id: string, extra: object = {}) => ({ id, name: id, ...extra });
const ev = (key: string, start: number, extra: object = {}) => ({ key, title: `T ${key}`, start, stop: start + 7200, channel: ch("1:1", { quality: { bucket: "fhd50" } }), ...extra });

test("parse: best channel first then alternates; bad items dropped; favourite from match", () => {
  const r = parseUpcoming({
    items: [
      ev("a", 100, { match: { team: "Warriors" }, league: "NRL", alternateChannels: [ch("1:2"), ch("1:1")], recordingId: 7 }),
      { key: "b", title: "no channel", start: 1, stop: 2 },
      ev("a", 100),
    ],
  });
  assert.equal(r.length, 1);
  assert.deepEqual(r[0].channels.map((c) => c.id), ["1:1", "1:2"]);
  assert.equal(r[0].favourite, true);
  assert.equal(r[0].league, "NRL");
  assert.equal(r[0].recordingId, 7);
  assert.equal(r[0].recordable, true);
  assert.deepEqual(parseUpcoming(null), []);
});

test("chips and days, in local time", () => {
  const now = new Date(2026, 9, 30, 10, 0);
  const t = (d: number, h: number, m = 0) => new Date(2026, 9, d, h, m).getTime() / 1000;
  assert.equal(timeChip({ start: t(30, 19, 30), stop: t(30, 21) }, now), "Today 19:30");
  assert.equal(timeChip({ start: t(31, 2), stop: t(31, 4) }, now), "Tomorrow 02:00");
  assert.equal(timeChip({ start: t(30, 9), stop: t(30, 11) }, now), "Live now");
  assert.equal(isLive({ start: t(30, 9), stop: t(30, 11) }, now.getTime() / 1000), true);
  const events = parseUpcoming([ev("late", t(31, 20)), ev("fav", t(30, 21), { match: { team: "X" } }), ev("early", t(30, 12)), ev("done", t(30, 5))]);
  const g = groupByDay(events, now);
  assert.deepEqual(g.map((d) => d.label), ["Today", "Tomorrow"]);
  // inside a day the server's order is kept (favourites first); finished events are gone
  assert.deepEqual(g[0].events.map((e) => e.key), ["fav", "early"]);
});
