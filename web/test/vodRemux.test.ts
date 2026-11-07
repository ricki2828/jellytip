import { test } from "node:test";
import assert from "node:assert/strict";
import { planSeek, progressFor, rangesOf, remuxOffset, toTimeline } from "../src/lib/vodRemux.ts";
import { playQuery } from "../src/lib/iptvPlay.ts";

test("offset only for a remux answer", () => {
  assert.equal(remuxOffset({ remux: true, startAt: 754 }), 754);
  assert.equal(remuxOffset({ remux: true }), 0);
  assert.equal(remuxOffset({ startAt: 90 }), 0);
  assert.equal(remuxOffset(null), 0);
});

test("timeline = element time + offset", () => {
  assert.equal(toTimeline(12.5, 600), 612.5);
  assert.equal(toTimeline(-1, 0), 0);
});

test("seek inside the buffer is local, element time = target - offset", () => {
  assert.deepEqual(planSeek(640, 600, [[0, 120]], 7200), { kind: "local", t: 40 });
});

test("seek outside the buffer, before the stream began, or past it reloads from the target", () => {
  assert.deepEqual(planSeek(900, 600, [[0, 120]], 7200), { kind: "reload", start: 900 });
  assert.deepEqual(planSeek(300, 600, [[0, 120]], 7200), { kind: "reload", start: 300 });
  assert.deepEqual(planSeek(720, 600, [[0, 120]], 7200), { kind: "reload", start: 720 }); // last second of the buffer is not trusted
});

test("seek clamps to the title and never goes negative", () => {
  assert.deepEqual(planSeek(9999, 0, [], 7200), { kind: "reload", start: 7199 });
  assert.deepEqual(planSeek(-30, 0, [], 7200), { kind: "reload", start: 0 });
  assert.deepEqual(planSeek(10, 0, [], 0), { kind: "reload", start: 10 });
});

test("rangesOf reads TimeRanges", () => {
  const r = { length: 2, start: (i: number) => [0, 50][i], end: (i: number) => [10, 80][i] };
  assert.deepEqual(rangesOf(r), [[0, 10], [50, 80]]);
});

test("progress: remux saves title position and provider length, not the fragment", () => {
  assert.deepEqual(progressFor(30, 600, Infinity, 7200, true), { pos: 630, dur: 7200 });
  assert.deepEqual(progressFor(30, 600, 45, 7200, true), { pos: 630, dur: 7200 });
  assert.deepEqual(progressFor(30, 0, 7195, 7200, false), { pos: 30, dur: 7195 });
  assert.deepEqual(progressFor(30, 0, NaN, 7200, false), { pos: 30, dur: 7200 });
});

test("resume then seek: offsets compose across reloads", () => {
  // resume at 600 -> stream begins at 600; viewer is 100 s in; seeks +30 min -> reload at 2500 -> 0 s in -> 2500
  let off = 600;
  assert.equal(toTimeline(100, off), 700);
  const plan = planSeek(toTimeline(100, off) + 1800, off, [[0, 130]], 7200);
  assert.deepEqual(plan, { kind: "reload", start: 2500 });
  off = plan.kind === "reload" ? plan.start : off;
  assert.equal(toTimeline(0, off), 2500);
});

test("playQuery: films and episodes ask for compat=web and carry the start", () => {
  const f = new URLSearchParams(playQuery({ vod: "abc12345:m:9" }, "web-x").split("?")[1]);
  assert.equal(f.get("compat"), "web");
  assert.equal(f.has("start"), false);
  const e = new URLSearchParams(playQuery({ series: "a:s:1", episode: "a:e:2" }, "web-x", "ts", false, 754.9).split("?")[1]);
  assert.equal(e.get("compat"), "web");
  assert.equal(e.get("start"), "754");
  const n = new URLSearchParams(playQuery({ vod: "a:m:9" }, "d", "ts", true, 50).split("?")[1]);
  assert.equal(n.has("compat"), false); // the TV/phone plays AC-3 natively
  assert.equal(n.has("start"), false);
});
