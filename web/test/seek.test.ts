import { test } from "node:test";
import assert from "node:assert/strict";
import { advanceSeek, settled, SETTLE_MS, BURST_GAP_MS, REPEAT_THROTTLE_MS, type SeekInput } from "../src/lib/seekRamp.ts";
import { chapterAt, chapterMarks, nextChapter, prevChapter } from "../src/lib/chapters.ts";
import { pickTrickplay, tileLocation, tileUrl, type TrickplayInfo } from "../src/lib/trickplay.ts";

const inp = (o: Partial<SeekInput>): SeekInput => ({ dir: 1, now: 0, repeat: false, shift: false, base: 100, duration: 1000, ...o });

test("discrete presses ramp 10 -> 30 -> 60 and accumulate on a pending target", () => {
  let s = advanceSeek(null, inp({ now: 0 }));
  assert.equal(s.target, 110);
  s = advanceSeek(s, inp({ now: 200 }));
  assert.equal(s.target, 120); // press 2 still 10
  s = advanceSeek(s, inp({ now: 400 }));
  assert.equal(s.target, 150); // press 3 -> 30
  s = advanceSeek(s, inp({ now: 600 }));
  assert.equal(s.target, 180);
  s = advanceSeek(s, inp({ now: 800 }));
  assert.equal(s.target, 240); // press 5 -> 60
});

test("a pause longer than the burst gap restarts from the playhead at 10 s", () => {
  const s = advanceSeek(null, inp({ now: 0 }));
  const t = advanceSeek(s, inp({ now: BURST_GAP_MS + 1, base: 500 }));
  assert.equal(t.target, 510);
  assert.equal(t.presses, 1);
});

test("held key: throttled repeats, tier by hold time", () => {
  let s = advanceSeek(null, inp({ now: 0 }));
  const same = advanceSeek(s, inp({ now: 30, repeat: true }));
  assert.equal(same, s); // throttled, unchanged
  s = advanceSeek(s, inp({ now: REPEAT_THROTTLE_MS, repeat: true }));
  assert.equal(s.target, 120);
  s = advanceSeek(s, inp({ now: 700, repeat: true }));
  assert.equal(s.target, 150); // held > 500 ms -> 30
  s = advanceSeek(s, inp({ now: 1600, repeat: true }));
  assert.equal(s.target, 210); // held > 1500 ms -> 60
});

test("shift jumps further", () => {
  assert.equal(advanceSeek(null, inp({ shift: true })).target, 160);
  assert.equal(advanceSeek(null, inp({ shift: true, dir: -1 })).target, 40);
});

test("targets are clamped to [0, duration-1]", () => {
  assert.equal(advanceSeek(null, inp({ dir: -1, base: 4 })).target, 0);
  assert.equal(advanceSeek(null, inp({ base: 995 })).target, 999);
});

test("reversing keeps the pending target and restarts the ramp", () => {
  let s = advanceSeek(null, inp({ now: 0 }));
  s = advanceSeek(s, inp({ now: 100 }));
  s = advanceSeek(s, inp({ now: 200 })); // 100 + 10 + 10 + 30 = 150
  const r = advanceSeek(s, inp({ now: 300, dir: -1 }));
  assert.equal(r.target, 140);
  assert.equal(r.presses, 1);
});

test("commit only after the settle window", () => {
  const s = advanceSeek(null, inp({ now: 1000 }));
  assert.equal(settled(s, 1000 + SETTLE_MS - 1), false);
  assert.equal(settled(s, 1000 + SETTLE_MS), true);
  assert.equal(settled(null, 5000), false);
});

const T = 10_000_000;
const marks = chapterMarks([{ Name: "B", StartPositionTicks: 300 * T }, { Name: "A", StartPositionTicks: 0 }, { Name: "C", StartPositionTicks: 900 * T }, { Name: "late", StartPositionTicks: 5000 * T }, {}], 1200);

test("chapter marks are sorted, in range, and skip malformed entries", () => {
  assert.deepEqual(marks.map((m) => m.name), ["A", "B", "C"]);
  assert.deepEqual(chapterMarks(undefined), []);
});

test("next / previous chapter", () => {
  assert.equal(nextChapter(marks, 10), 300);
  assert.equal(nextChapter(marks, 300), 900);
  assert.equal(nextChapter(marks, 950), null);
  assert.equal(prevChapter(marks, 350), 300); // deep in B -> start of B
  assert.equal(prevChapter(marks, 301), 0); // just after B start -> A
  assert.equal(prevChapter(marks, 1), 0);
  assert.equal(prevChapter([], 50), null);
  assert.equal(chapterAt(marks, 400)?.name, "B");
  assert.equal(chapterAt(marks, 0)?.name, "A");
});

const info: TrickplayInfo = { Width: 320, Height: 180, TileWidth: 10, TileHeight: 10, ThumbnailCount: 250, Interval: 10000 };

test("trickplay tile math: index, column, row, sheet", () => {
  assert.deepEqual(tileLocation(info, 0), { sheet: 0, x: 0, y: 0, w: 320, h: 180, cols: 10, rows: 10 });
  const a = tileLocation(info, 35); // thumb 3
  assert.equal(a.sheet, 0);
  assert.equal(a.x, 3 * 320);
  assert.equal(a.y, 0);
  const b = tileLocation(info, 125); // thumb 12 -> row 1 col 2
  assert.equal(b.x, 2 * 320);
  assert.equal(b.y, 180);
  const c = tileLocation(info, 1005); // thumb 100 -> sheet 1, first
  assert.equal(c.sheet, 1);
  assert.equal(c.x, 0);
  assert.equal(c.y, 0);
  const last = tileLocation(info, 99999); // clamped to thumb 249 -> sheet 2, slot 49
  assert.equal(last.sheet, 2);
  assert.equal(last.x, 9 * 320);
  assert.equal(last.y, 4 * 180);
});

test("pickTrickplay: widest <= 480, else narrowest, null when absent or malformed", () => {
  const map = { src1: { "320": info, "640": { ...info, Width: 640, Height: 360 } } };
  assert.equal(pickTrickplay(map, "src1")?.width, 320);
  assert.equal(pickTrickplay(map, "src1", 700)?.width, 640);
  assert.equal(pickTrickplay({ s: { "640": { ...info, Width: 640 } } }, "s", 200)?.width, 640);
  assert.equal(pickTrickplay(map, "other"), null);
  assert.equal(pickTrickplay(undefined, "src1"), null);
  assert.equal(pickTrickplay({ s: { "320": { ...info, Interval: 0 } } }, "s"), null);
});

test("tile url", () => {
  assert.equal(tileUrl("/jf", "item", 320, 2, "src", "tok"), "/jf/Videos/item/Trickplay/320/2.jpg?MediaSourceId=src&api_key=tok");
});
