import { test } from "node:test";
import assert from "node:assert/strict";
import { hasStream, parseReminders } from "../src/lib/reminders.ts";

test("fixture reminder with no channel is kept, flagged as no stream", () => {
  const r = parseReminders({ items: [{ id: "a", key: "fx:f1@100", fid: "f1", title: "A at B", start: 100, stop: 200, channel: null, alternateChannels: [] }] });
  assert.equal(r.length, 1);
  assert.equal(r[0].fid, "f1");
  assert.equal(hasStream(r[0].event), false);
  assert.equal(r[0].event.reminderId, "a");
});

test("reminder with a channel keeps it; a channel-less legacy item (no fid) is still dropped", () => {
  const r = parseReminders({ items: [
    { id: "b", key: "k", fid: "f2", title: "T", start: 1, stop: 2, channel: { id: "p:1", name: "C" } },
    { id: "c", key: "k2", title: "T", start: 1, stop: 2, channel: null },
  ] });
  assert.equal(r.length, 1);
  assert.equal(hasStream(r[0].event), true);
});
