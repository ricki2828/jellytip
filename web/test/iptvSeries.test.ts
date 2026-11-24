// IPTV details on the shared scaffold (parity #7): the series button, opening season, episode lines and load retries.
// Provider ids are scrubbed to pp000001.
import { test } from "node:test";
import assert from "node:assert/strict";
import { episodeMeta, episodePct, iptvOpenSeason, iptvSeriesPrimary, loadRetryDelay, primaryLabel, resumeAt, seasonName, vodMins } from "../src/lib/iptvSeries.ts";
import type { VodEpisode } from "../src/lib/iptvVod.ts";

const ep = (n: number, o: { played?: boolean; pos?: number; runtime?: number } = {}): VodEpisode => ({
  id: `pp000001:e:${n}`,
  episodeId: String(n),
  episode: n,
  title: `Chapter ${n}`,
  runtime: o.runtime ?? 2880,
  played: !!o.played,
  progress: o.played || o.pos ? { position: o.played ? 2870 : o.pos!, duration: 2880, played: !!o.played } : null,
});

test("series button: resumes the started episode after the last watched one", () => {
  const p = iptvSeriesPrimary([{ season: 1, episodes: [ep(1, { played: true }), ep(2, { pos: 900 }), ep(3)] }]);
  assert.ok(p);
  assert.equal(primaryLabel(p), "Resume S1 E2");
  assert.equal(p.ep.iptv?.id, "pp000001:e:2");
  assert.equal(p.next, true);
});

test("series button: nothing watched plays S1 E1", () => {
  const p = iptvSeriesPrimary([{ season: 1, episodes: [ep(1), ep(2)] }, { season: 2, episodes: [ep(1)] }]);
  assert.equal(p && primaryLabel(p), "Play S1 E1");
});

test("series button: next after the LAST watched, across seasons", () => {
  const p = iptvSeriesPrimary([
    { season: 1, episodes: [ep(1, { played: true }), ep(2), ep(3, { played: true })] },
    { season: 2, episodes: [ep(1), ep(2)] },
  ]);
  assert.equal(p && primaryLabel(p), "Play S2 E1");
});

test("series button: all watched = the first episode again, no Next pill", () => {
  const p = iptvSeriesPrimary([{ season: 1, episodes: [ep(1, { played: true }), ep(2, { played: true })] }]);
  assert.equal(p && primaryLabel(p), "Play S1 E1");
  assert.equal(p?.next, false);
});

test("series button: a 30 s nibble is not a resume", () => {
  const p = iptvSeriesPrimary([{ season: 1, episodes: [ep(1, { pos: 20 })] }]);
  assert.equal(p && primaryLabel(p), "Play S1 E1");
});

test("series button: specials only falls back to the old IPTV rule (started, else unwatched, else first)", () => {
  assert.equal(primaryLabel(iptvSeriesPrimary([{ season: 0, episodes: [ep(1, { played: true }), ep(2)] }])!), "Play S0 E2");
  assert.equal(primaryLabel(iptvSeriesPrimary([{ season: 0, episodes: [ep(1), ep(2, { pos: 600 })] }])!), "Resume S0 E2");
  assert.equal(primaryLabel(iptvSeriesPrimary([{ season: 0, episodes: [ep(1, { played: true })] }])!), "Play S0 E1");
});

test("series button: no episodes = no button", () => {
  assert.equal(iptvSeriesPrimary([]), null);
  assert.equal(iptvSeriesPrimary(undefined), null);
  assert.equal(iptvSeriesPrimary([{ season: 1, episodes: [] }]), null);
});

test("opening season: ?season= wins, then the next episode's, then first unwatched, then first", () => {
  const seasons = [
    { season: 1, episodes: [ep(1, { played: true })] },
    { season: 2, episodes: [ep(1)] },
    { season: 3, episodes: [ep(1)] },
  ];
  const p = iptvSeriesPrimary(seasons);
  assert.equal(iptvOpenSeason(seasons, "3", p), 3);
  assert.equal(iptvOpenSeason(seasons, null, p), 2);
  // stale / foreign ?season= is ignored
  assert.equal(iptvOpenSeason(seasons, "9", p), 2);
  assert.equal(iptvOpenSeason(seasons, "abc", p), 2);
  assert.equal(iptvOpenSeason(seasons, null, null), 2);
  const done = [{ season: 1, episodes: [ep(1, { played: true })] }, { season: 2, episodes: [ep(1, { played: true })] }];
  assert.equal(iptvOpenSeason(done, null, iptvSeriesPrimary(done)), 1);
  assert.equal(iptvOpenSeason([], null, null), null);
});

test("episode line and progress", () => {
  assert.equal(episodeMeta(ep(1, { played: true })), "48m  ·  Watched");
  assert.equal(episodeMeta(ep(2, { pos: 900 })), "48m  ·  15m in");
  assert.equal(episodeMeta(ep(3)), "48m");
  assert.equal(episodeMeta({ ...ep(4), runtime: null }), "");
  assert.equal(Math.round(episodePct(ep(2, { pos: 1440 }))), 50);
  assert.equal(episodePct(ep(1, { played: true })), 0);
  assert.equal(episodePct(ep(3)), 0);
});

test("words: minutes, seasons, film resume", () => {
  assert.equal(vodMins(7020), "1h 57m");
  assert.equal(vodMins(2880), "48m");
  assert.equal(vodMins(0), null);
  assert.equal(vodMins(null), null);
  assert.equal(seasonName(0), "Specials");
  assert.equal(seasonName(2), "Season 2");
  assert.equal(resumeAt({ position: 1800, duration: 7020 }), 1800);
  assert.equal(resumeAt({ position: 1800, duration: 7020, played: true }), 0);
  assert.equal(resumeAt({ position: 25, duration: 7020 }), 0);
  assert.equal(resumeAt(null), 0);
});

test("load retries: only a 404 means gone; errors and 5xx back off three times", () => {
  assert.equal(loadRetryDelay(404, 0), null);
  assert.equal(loadRetryDelay(403, 0), null);
  assert.deepEqual([0, 1, 2, 3].map((a) => loadRetryDelay(503, a)), [1000, 2000, 4000, null]);
  assert.equal(loadRetryDelay(0, 0), 1000);
  assert.equal(loadRetryDelay(429, 1), 2000);
  assert.equal(loadRetryDelay(408, 2), 4000);
});
