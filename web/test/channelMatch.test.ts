import { test } from "node:test";
import assert from "node:assert/strict";
import { resolveChannelMatch } from "../src/lib/channelMatch.ts";

const ch = (id: string, confidence = 0.9, maybe = false) => ({ id, name: id, confidence, maybe, dormant: false }) as never;
const fx = (o: Record<string, unknown>) => ({ fid: "f", home: "UCLA", away: "Minnesota", kickoff: 1000, status: "in", matchId: "m1", channels: [], ...o }) as never;
const NOW = 1000;

test("live fixture with this channel resolves", () => {
  assert.deepEqual(resolveChannelMatch([fx({ channels: [ch("1:2")] })], "1:2", NOW), { matchId: "m1", maybe: false, title: "UCLA v Minnesota" });
});
test("highest confidence wins; maybe is carried", () => {
  const r = resolveChannelMatch([fx({ matchId: "a", channels: [ch("1:2", 0.5, true)] }), fx({ matchId: "b", channels: [ch("1:2", 0.7, true)] })], "1:2", NOW);
  assert.equal(r?.matchId, "b");
  assert.equal(r?.maybe, true);
});
test("starting within 15 min counts; later or finished does not", () => {
  assert.equal(resolveChannelMatch([fx({ status: "pre", kickoff: NOW + 600, channels: [ch("1:2")] })], "1:2", NOW)?.matchId, "m1");
  assert.equal(resolveChannelMatch([fx({ status: "pre", kickoff: NOW + 3600, channels: [ch("1:2")] })], "1:2", NOW), null);
  assert.equal(resolveChannelMatch([fx({ status: "post", channels: [ch("1:2")] })], "1:2", NOW), null);
});
test("other channel gives null; no matchId falls back to the fixture id (same rule as Game Centre launches)", () => {
  assert.equal(resolveChannelMatch([fx({ channels: [ch("9:9")] })], "1:2", NOW), null);
  assert.equal(resolveChannelMatch([fx({ matchId: null, channels: [ch("1:2")] })], "1:2", NOW)?.matchId, "f");
});
