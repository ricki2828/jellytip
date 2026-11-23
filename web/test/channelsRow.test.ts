import { test } from "node:test";
import assert from "node:assert/strict";
import { cardModel, channelNumber, channelRetryMs, commitOrder, mergeChannelOrder, orderForView, homeLeadRows, hhmm, leftText, nextLine, subLine, visibleChannels } from "../src/lib/channelsRow.ts";
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

// ---- Home "My Channels" row (Ricki 23-Nov, TV 268dcba9) ----

test("home placement: My Channels directly under Continue watching, else first; the rest keep their order", () => {
  assert.deepEqual(homeLeadRows(true), ["continue", "mychannels", "reminders", "recordings", "livesport", "upcoming"]);
  assert.deepEqual(homeLeadRows(false), ["mychannels", "reminders", "recordings", "livesport", "upcoming"]);
});

test("tile lines: number, time left, sub, next", () => {
  assert.equal(channelNumber(0), 101);
  assert.equal(channelNumber(4), 105);
  const now = new Date(2026, 10, 23, 20, 0).getTime();
  assert.equal(leftText(now + 25 * 60_000 + 30_000, now), "25 min left");
  assert.equal(leftText(now + 30_000, now), "ending");
  assert.equal(leftText(now - 60_000, now), "ending");
  assert.equal(leftText(now + 65 * 60_000, now), "1 h 05 left");
  assert.equal(subLine({ nowSub: "S2E4 · Pilot", nowEndMs: now + 10 * 60_000 }, now), "S2E4 · Pilot · 10 min left");
  assert.equal(subLine({ nowSub: "", nowEndMs: now + 10 * 60_000 }, now), "10 min left");
  assert.equal(hhmm(new Date(2026, 10, 23, 9, 5).getTime()), "09:05");
  assert.equal(nextLine({ title: "Heat", atMs: new Date(2026, 10, 23, 21, 30).getTime() }), "Next 21:30 · Heat");
  assert.equal(nextLine(null), "");
});

test("cardModel carries when now ends", () => {
  const now = EPOCH_MS + 3 * 3 * 3600_000 + 15 * 60_000;
  const m = cardModel(comedy, three, now)!;
  assert.equal(m.nowEndMs, now + 45 * 60_000);
});

test("a failed channel retries with backoff from 5 s to 5 min", () => {
  assert.deepEqual([1, 2, 3, 7, 20].map(channelRetryMs), [5_000, 10_000, 20_000, 300_000, 300_000]);
});

test("session channel order: placed channels keep places and numbers; late ones append; a re-rank never reorders", () => {
  const d = (id: string) => CHANNELS.find((c) => c.id === id)!;
  const first = mergeChannelOrder(null, [d("comedy"), d("90s-action"), d("sci-fi-nights")]);
  assert.deepEqual(first.order.map((c) => c.id), ["comedy", "90s-action", "sci-fi-nights"]);
  // Back after a film: affinity now ranks differently; the row keeps its order (so 101/102/103 stay on the same channels)
  const back = mergeChannelOrder(first.ids, [d("sci-fi-nights"), d("90s-action"), d("comedy")]);
  assert.deepEqual(back.order.map((c) => c.id), ["comedy", "90s-action", "sci-fi-nights"]);
  assert.deepEqual(back.order.map((c, i) => [c.id, channelNumber(i)]), [["comedy", 101], ["90s-action", 102], ["sci-fi-nights", 103]]);
  // a late load / a retry that came good goes at the end, nothing before it moves
  const late = mergeChannelOrder(back.ids, [d("documentaries"), d("sci-fi-nights"), d("comedy"), d("90s-action")]);
  assert.deepEqual(late.order.map((c) => c.id), ["comedy", "90s-action", "sci-fi-nights", "documentaries"]);
  // hidden for now: it keeps its place in the remembered ids for when it is shown again
  const hid = mergeChannelOrder(late.ids, [d("comedy"), d("sci-fi-nights"), d("documentaries")]);
  assert.deepEqual(hid.order.map((c) => c.id), ["comedy", "sci-fi-nights", "documentaries"]);
  assert.deepEqual(hid.ids, ["comedy", "90s-action", "sci-fi-nights", "documentaries"]);
  assert.deepEqual(mergeChannelOrder(hid.ids, [d("90s-action"), d("comedy")]).order.map((c) => c.id), ["comedy", "90s-action"]);
});

test("settled commit: channels arriving in another order than affinity still commit the affinity order; Back reuses it", () => {
  const d = (id: string) => CHANNELS.find((c) => c.id === id)!;
  // affinity (once everything is in): comedy, sci-fi, 90s action, documentaries. They arrive: documentaries, 90s action, …
  const arrivals = [[d("documentaries")], [d("90s-action"), d("documentaries")], [d("sci-fi-nights"), d("90s-action"), d("documentaries")]];
  let committed: string[] | null = null;
  // before the load settles nothing is committed: the view is the affinity order of what is in (it may move, as before)
  for (const ranked of arrivals) assert.deepEqual(orderForView(committed, ranked).map((c) => c.id), ranked.map((c) => c.id));
  // settled: the full affinity order is committed, not the arrival order
  const settled = [d("comedy"), d("sci-fi-nights"), d("90s-action"), d("documentaries")];
  committed = commitOrder(committed, settled);
  assert.deepEqual(committed, ["comedy", "sci-fi-nights", "90s-action", "documentaries"]);
  // Back (or the other screen): a different ranking now, the committed order is drawn
  const later = [d("documentaries"), d("90s-action"), d("comedy"), d("sci-fi-nights")];
  assert.deepEqual(orderForView(committed, later).map((c) => c.id), committed);
  // Back before this screen's own load is in: only what is loaded, still in the committed order
  assert.deepEqual(orderForView(committed, [d("documentaries"), d("comedy")]).map((c) => c.id), ["comedy", "documentaries"]);
  // a late channel (a retry that came good) appends on the next commit
  committed = commitOrder(committed, [d("family-night"), ...later]);
  assert.deepEqual(committed, ["comedy", "sci-fi-nights", "90s-action", "documentaries", "family-night"]);
});
