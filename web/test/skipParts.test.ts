import { test } from "node:test";
import assert from "node:assert/strict";
import {
  activeSeg,
  chapterSegments,
  chaptersOutcome,
  iptvChapterMarks,
  parseIptvChapters,
  parseMediaSegments,
  segmentsRetryMs,
  skipAction,
  skipLabel,
  skipVisible,
  type SkipSeg,
} from "../src/lib/skipParts.ts";

// The TV's IptvChapterSkipTest body (d10d8492): a reversed chapter and a bogus kind are dropped
const body = {
  chapters: [{ s: 0, e: 62, t: "Intro" }, { s: 62, e: 2400, t: null }, { s: 2400, e: 2500, t: "End Credits" }, { s: 9, e: 3 }],
  skip: [{ kind: "intro", s: 0, e: 62 }, { kind: "credits", s: 2400, e: 2500 }, { kind: "bogus", s: 1, e: 2 }],
};

test("parses chapters and skips", () => {
  const c = parseIptvChapters(body)!;
  assert.deepEqual(c.chapters, [{ s: 0, e: 62, t: "Intro" }, { s: 62, e: 2400, t: null }, { s: 2400, e: 2500, t: "End Credits" }]);
  assert.deepEqual(c.skip.map((x) => x.kind), ["intro", "credits"]);
});

test("not probed yet is null, none is empty, junk is null", () => {
  assert.equal(parseIptvChapters({ chapters: null, skip: [] })!.chapters, null);
  assert.deepEqual(parseIptvChapters({ chapters: [], skip: [] })!.chapters, []);
  assert.equal(parseIptvChapters("not json"), null);
  assert.equal(parseIptvChapters(null), null);
});

test("only a 404 stops the asking; not probed, 5xx, offline ask again", () => {
  assert.deepEqual(chaptersOutcome(404, { error: "no such movie" }), { kind: "stop" });
  assert.deepEqual(chaptersOutcome(200, { chapters: null, skip: [] }), { kind: "again" });
  assert.deepEqual(chaptersOutcome(503, null), { kind: "again" });
  assert.deepEqual(chaptersOutcome(0, null), { kind: "again" });
  assert.deepEqual(chaptersOutcome(403, null), { kind: "again" });
  assert.deepEqual(chaptersOutcome(200, "junk"), { kind: "again" });
  const ok = chaptersOutcome(200, body);
  assert.equal(ok.kind, "ok");
  const none = chaptersOutcome(200, { chapters: [], skip: [] });
  assert.equal(none.kind, "ok"); // known to have none: stop asking
});

test("active only inside the part and not in its last seconds", () => {
  const skip = parseIptvChapters(body)!.skip;
  assert.equal(activeSeg(skip, 0)?.kind, "intro");
  assert.equal(activeSeg(skip, 58)?.kind, "intro");
  assert.equal(activeSeg(skip, 59.5), null); // the last 3 s
  assert.equal(activeSeg(skip, 600), null);
  assert.equal(activeSeg(skip, 2400)?.kind, "credits");
});

test("shown for 10 s from where it was reached unless dismissed", () => {
  const seg: SkipSeg = { kind: "intro", s: 0, e: 62 };
  assert.equal(skipVisible(seg, 5, null, false), true); // first tick, before firstSeen is stored
  assert.equal(skipVisible(seg, 14, 5, false), true);
  assert.equal(skipVisible(seg, 15, 5, false), false);
  assert.equal(skipVisible(seg, 4, 5, false), false); // seeked back before where it was first seen
  assert.equal(skipVisible(seg, 6, 5, true), false);
  assert.equal(skipVisible(null, 6, 5, false), false);
});

test("credits play the next episode when there is one; to the end stops 1.5 s short", () => {
  const credits: SkipSeg = { kind: "credits", s: 2400, e: 2500 };
  assert.deepEqual(skipAction(credits, true), { kind: "next" });
  assert.deepEqual(skipAction(credits, false), { kind: "seek", to: 2500 });
  assert.deepEqual(skipAction(credits, false, 2500), { kind: "seek", to: 2498.5 });
  assert.deepEqual(skipAction({ kind: "credits", s: 2300, e: 2400 }, false, 2500), { kind: "seek", to: 2400 });
  assert.deepEqual(skipAction({ kind: "intro", s: 0, e: 62 }, true), { kind: "seek", to: 62 });
  assert.equal(skipLabel("intro"), "Skip intro");
  assert.equal(skipLabel("recap"), "Skip recap");
  assert.equal(skipLabel("credits"), "Skip credits");
});

test("marks are chapter starts inside the bar", () => {
  const chs = parseIptvChapters(body)!.chapters;
  assert.deepEqual(iptvChapterMarks(chs, 2500), [{ at: 62, name: "" }, { at: 2400, name: "End Credits" }]);
  assert.deepEqual(iptvChapterMarks(chs, 0), []);
  assert.deepEqual(iptvChapterMarks(null, 2500), []);
});

test("Jellyfin media segments: Outro is credits, Unknown and broken dropped, sorted", () => {
  const segs = parseMediaSegments({
    Items: [
      { Type: "Outro", StartTicks: 24_000_000_000, EndTicks: 25_000_000_000 },
      { Type: "Intro", StartTicks: 0, EndTicks: 620_000_000 },
      { Type: "Unknown", StartTicks: 0, EndTicks: 10 },
      { Type: "Recap", StartTicks: 50, EndTicks: 10 },
    ],
  });
  assert.deepEqual(segs, [{ kind: "intro", s: 0, e: 62 }, { kind: "credits", s: 2400, e: 2500 }]);
  assert.deepEqual(parseMediaSegments(null), []);
  assert.deepEqual(parseMediaSegments({ Items: "x" }), []);
});

test("chapter names: qualified names only, credits to the end, too short / too long intros dropped", () => {
  const marks = [
    { at: 0, name: "Previously on" },
    { at: 40, name: "Opening Titles" },
    { at: 100, name: "Chapter 2" },
    { at: 2400, name: "End Credits" },
  ];
  assert.deepEqual(chapterSegments(marks, 2500), [
    { kind: "recap", s: 0, e: 40 },
    { kind: "intro", s: 40, e: 100 },
    { kind: "credits", s: 2400, e: 2500 },
  ]);
  assert.deepEqual(chapterSegments([{ at: 0, name: "Ending" }], 100), []); // a scene, not credits
  assert.deepEqual(chapterSegments([{ at: 0, name: "Intro" }, { at: 3, name: "x" }], 100), []); // under 5 s
  assert.deepEqual(chapterSegments([{ at: 0, name: "Intro" }, { at: 400, name: "x" }], 900), []); // over 5 min
  assert.deepEqual(chapterSegments([{ at: 2400, name: "Credits" }], 0), []); // unknown end
});

test("library segments retry: 404 stops, anything else backs off to 5 min", () => {
  assert.equal(segmentsRetryMs(404, 0), null);
  assert.equal(segmentsRetryMs(500, 0), 5_000);
  assert.equal(segmentsRetryMs(0, 1), 10_000);
  assert.equal(segmentsRetryMs(503, 20), 300_000);
});

test("native payload: chapter starts in ms without 0, skips as sent", async () => {
  const { nativeChapters } = await import("../src/lib/skipParts.ts");
  const c = parseIptvChapters(body)!;
  assert.deepEqual(nativeChapters(c), { marksMs: [62_000, 2_400_000], skip: [{ kind: "intro", s: 0, e: 62 }, { kind: "credits", s: 2400, e: 2500 }], hasNext: false });
  assert.deepEqual(nativeChapters({ chapters: [], skip: [] }, true), { marksMs: [], skip: [], hasNext: true });
});

test("chapters query: films and episodes only", async () => {
  const { chaptersQuery } = await import("../src/lib/skipParts.ts");
  assert.equal(chaptersQuery({ vod: "pp000001:m:42" }), "vod=pp000001%3Am%3A42");
  assert.equal(chaptersQuery({ episode: "pp000001:e:7", series: "pp000001:s:3" }), "episode=pp000001%3Ae%3A7&series=pp000001%3As%3A3");
  assert.equal(chaptersQuery({ episode: "pp000001:e:7" }), null);
  assert.equal(chaptersQuery({ channel: "pp000001:1" }), null);
});
