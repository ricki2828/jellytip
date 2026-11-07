import { test } from "node:test";
import assert from "node:assert/strict";
import { cardModel, visibleChannels } from "../src/lib/channelsRow.ts";
import { CHANNELS, EPOCH_MS, TICKS_PER_SECOND, type ChanItem } from "../src/lib/channels.ts";

const MIN = 60 * TICKS_PER_SECOND;
const it = (id: string, min: number, o: Partial<ChanItem> = {}): ChanItem => ({ Id: id, Name: `Film ${id}`, Type: "Movie", RunTimeTicks: min * MIN, ...o });
const three = [it("a".repeat(32), 60), it("b".repeat(32), 60), it("c".repeat(32), 60)];
const comedy = CHANNELS.find((c) => c.id === "comedy")!;

test("visibleChannels: needs 3 playable items, not hidden, built-in order", () => {
  const loads = { "90s-action": three, comedy: three.slice(0, 2), "sci-fi-nights": three, documentaries: undefined };
  assert.deepEqual(visibleChannels(loads, []).map((c) => c.id), ["90s-action", "sci-fi-nights"]);
  assert.deepEqual(visibleChannels(loads, ["sci-fi-nights"]).map((c) => c.id), ["90s-action"]);
  assert.deepEqual(visibleChannels({}, []), []);
});

test("cardModel: now, progress, next with its start time, tune target at the offset", () => {
  const now = EPOCH_MS + 3 * 3 * 3600_000 + 15 * 60_000; // 15 min into a 60 min item (cycle is 3 h)
  const m = cardModel(comedy, three, now)!;
  assert.equal(m.nowTitle.startsWith("Film "), true);
  assert.equal(Math.round(m.progress * 100), 25);
  assert.equal(m.target.startS, 15 * 60);
  assert.equal(m.next!.atMs, now - 15 * 60_000 + 3600_000);
  assert.equal(m.artId, m.target.id);
  assert.equal(m.artUrl, null);
});

test("cardModel: an IPTV film shows its TMDB art and tunes through the IPTV player", () => {
  const films = [it("p:m:1", 100, { Art: "https://image.tmdb.org/t/p/w780/x.jpg" }), it("p:m:2", 100, { Art: "https://image.tmdb.org/t/p/w780/y.jpg" }), it("p:m:3", 100, { Art: "https://image.tmdb.org/t/p/w780/z.jpg" })];
  const m = cardModel(comedy, films, EPOCH_MS + 1000 * 60)!;
  assert.equal(m.artId, null);
  assert.match(m.artUrl!, /^https:\/\/image\.tmdb\.org\//);
});

test("cardModel: nothing scheduled → null; episodes use the series for art", () => {
  assert.equal(cardModel(comedy, [], Date.now()), null);
  const eps = [1, 2, 3].map((n) => it(`e${n}`.padEnd(32, "0"), 22, { Type: "Episode", SeriesName: "Show", SeriesId: "s".repeat(32) }));
  const m = cardModel(CHANNELS.find((c) => c.id === "sunday-sitcoms")!, eps, EPOCH_MS + 60_000)!;
  assert.equal(m.artId, "s".repeat(32));
  assert.equal(m.nowTitle, "Show");
});
