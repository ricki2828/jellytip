import { test } from "node:test";
import assert from "node:assert/strict";
import { chipFor, hasPending, lengthText, orderRecs, unwatchedCount, type Rec } from "../src/lib/recs.ts";

const r = (id: number, state: Rec["state"], start: number, extra: Partial<Rec> = {}): Rec => ({ id, channelId: "1:1", channel: "Sky", title: `T${id}`, start, stop: start + 3600, state, playable: state === "done", ...extra });

test("order: recording now, NEW, scheduled soonest first, watched; failed and cancelled hidden", () => {
  const list = [r(1, "done", 100, { watched: true }), r(2, "scheduled", 900), r(3, "scheduled", 500), r(4, "done", 200), r(5, "recording", 50), r(6, "failed", 10), r(7, "cancelled", 10), r(8, "done", 300)];
  assert.deepEqual(orderRecs(list).map((x) => x.id), [5, 8, 4, 3, 2, 1]);
  assert.equal(unwatchedCount(list), 2);
  assert.equal(hasPending(list), true);
  assert.equal(hasPending([r(1, "done", 1)]), false);
});

test("chips and length", () => {
  const now = new Date(2026, 9, 30, 10, 0);
  assert.deepEqual(chipFor(r(1, "recording", 0), now), { text: "● REC", tone: "rec" });
  assert.deepEqual(chipFor(r(2, "done", 0), now), { text: "NEW", tone: "new" });
  assert.deepEqual(chipFor(r(2, "done", 0, { watched: true }), now), { text: "Watched", tone: "dim" });
  assert.equal(chipFor(r(3, "scheduled", new Date(2026, 9, 30, 12, 14).getTime() / 1000), now).text, "Today 12:14");
  assert.equal(chipFor(r(3, "scheduled", new Date(2026, 9, 31, 9, 5).getTime() / 1000), now).text, "Tomorrow 09:05");
  assert.equal(lengthText({ start: 0, stop: 5400 }), "1h 30m");
  assert.equal(lengthText({ start: 0, stop: 2700 }), "45m");
});
