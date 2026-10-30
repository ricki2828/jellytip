import { test } from "node:test";
import assert from "node:assert/strict";
import { audioLabel, hasServerRanking, kindPill, mayBuffer, parseQualitySummary, parseSport, parseSuggest, qualityLabel, toggleName } from "../src/lib/sport.ts";
import { parseOnNow } from "../src/lib/onNow.ts";
import { parsePrefs } from "../src/lib/prefs.ts";

const N = 1_000_000;
test("sport fields are read when present and absent otherwise", () => {
  const s = parseSport({ quality: { bucket: "fhd50", height: 1080, fps: 50, audio: "Atmos" }, eventGroup: { key: "a-b", alternates: ["1:2", 3] }, match: { team: "Warriors" } }, { kind: "live", sport: true });
  assert.equal(s.kind, "live");
  assert.equal(s.sport, true);
  assert.equal(s.quality?.bucket, "fhd50");
  assert.equal(audioLabel(s.quality), "ATMOS");
  assert.equal(audioLabel({ bucket: "hd", height: 720, fps: 25, audio: "5.1", hint: null }), "5.1");
  assert.equal(audioLabel({ bucket: "hd", height: 720, fps: 25, audio: "stereo", hint: null }), null);
  assert.equal(audioLabel(null), null);
  assert.deepEqual(s.alternates, ["1:2"]);
  assert.equal(s.match?.team, "Warriors");
  const none = parseSport({ name: "x" });
  assert.equal(hasServerRanking(none), false);
  assert.equal(none.quality, null);
});

test("alternate channels carry name and quality when the server sends them", () => {
  const s = parseSport({ eventGroup: { key: "k", alternates: ["1:2"], alternateChannels: [{ id: "1:2", name: "Sky 2", num: 502, quality: { bucket: "uhd", audio: "atmos" } }, { name: "no id" }] } });
  assert.equal(s.alternateChannels.length, 1);
  assert.equal(s.alternateChannels[0].name, "Sky 2");
  assert.equal(s.alternateChannels[0].quality?.audio, "atmos");
  assert.equal(parseSport({ eventGroup: { key: "k", alternates: ["1:2"] } }).alternateChannels.length, 0);
});

test("art: parts are optional; nothing to draw is null", () => {
  assert.equal(parseSport({}).art, null);
  const a = parseSport({ art: { league: { badge: "v1/iptv/img/l.png", fanart: "v1/iptv/img/f.jpg" }, home: { name: "Warriors", badge: "v1/iptv/img/w.png" }, away: { name: "Broncos" } } }).art;
  assert.equal(a?.fanart, "v1/iptv/img/f.jpg");
  assert.equal(a?.home?.name, "Warriors");
  assert.equal(a?.away, null);
  assert.equal(parseSport({ art: {} }).art, null);
});

test("labels: quality chips, pills and the buffer hint", () => {
  assert.equal(qualityLabel({ bucket: "uhd", height: 2160, fps: 50, audio: null, hint: null }), "UHD");
  assert.equal(qualityLabel({ bucket: "fhd50", height: 1080, fps: 50, audio: null, hint: null }), "1080 50fps");
  assert.equal(qualityLabel({ bucket: "hd", height: 720, fps: 25, audio: null, hint: null }), "720");
  assert.equal(qualityLabel({ bucket: "dead", height: null, fps: null, audio: null, hint: null }), null);
  assert.equal(qualityLabel({ bucket: "slow", height: 1080, fps: 25, audio: null, hint: null }), "1080");
  assert.equal(mayBuffer({ bucket: "slow", height: 1080, fps: 25, audio: null, hint: null }), true);
  assert.equal(mayBuffer({ bucket: "fhd50", height: 1080, fps: 50, audio: null, hint: "may buffer" }), true);
  assert.equal(parseSport({ event: true }).eventKey, null);
  assert.deepEqual(kindPill("highlights"), { text: "REPLAY", tone: "replay" });
  assert.equal(kindPill("other"), null);
});

test("on now: the server's order is kept when it ranks; one card per event", () => {
  const items = [
    { channel: { id: "1:1", name: "Rugby" }, sport: false, now: { start: N - 1, stop: N + 9, title: "Show", kind: "other" } },
    { channel: { id: "1:2", name: "Sky" }, sport: true, now: { start: N - 1, stop: N + 9, title: "NFL", kind: "live" }, eventGroup: { key: "nfl", alternates: ["1:3"] } },
    { channel: { id: "1:3", name: "Alt" }, sport: true, now: { start: N - 1, stop: N + 9, title: "NFL", kind: "live" }, eventGroup: { key: "nfl", alternates: [] } },
  ];
  const r = parseOnNow({ channels: items }, N);
  assert.deepEqual(r.map((o) => o.channelId), ["1:1", "1:2"]);
  assert.equal(r[1].info.alternates[0], "1:3");
});

test("settings parsers tolerate odd input", () => {
  assert.deepEqual(parseSuggest({ teams: ["Warriors", { name: "49ers" }, 3], leagues: [{ name: "NRL" }, "NRL"] }).map((s) => `${s.type}:${s.name}`), ["team:Warriors", "team:49ers", "league:NRL"]);
  assert.equal(parseSuggest({ leagues: [{ name: "World Cup", type: "tournament" }, { name: "NRL", type: "league" }] }).map((s) => s.tournament === true).join(), "true,false");
  assert.deepEqual(parseSuggest(null), []);
  const q = parseQualitySummary({
    providers: [
      { name: "A", buckets: { fhd: 40, uhd: 2, dead: 0 }, progress: { total: 100, probed: 10, fresh: 5 }, running: true, autoAfter: 5, onboardedAt: null },
      { name: "B", buckets: { fhd: 1, slow: 3 }, progress: { total: 10, probed: 10, fresh: 10 }, onboardedAt: 9 },
    ],
  });
  assert.deepEqual(q?.counts, [{ bucket: "uhd", n: 2 }, { bucket: "fhd", n: 41 }, { bucket: "slow", n: 3 }]);
  assert.deepEqual(q?.progress, { done: 20, total: 110 });
  assert.equal(q?.providers[0].running, true);
  assert.equal(q?.providers[1].onboarded, true);
  assert.equal(parseQualitySummary({}), null);
  assert.deepEqual(toggleName(["Warriors"], "warriors"), []);
  assert.deepEqual(parsePrefs({ sport: { teams: ["A", "A", 1], leagues: "x" } }).sport, { teams: ["A"], leagues: [], sports: [], muted: { sports: [], leagues: [], tags: [] }, setup: false });
});

test("sports picker is offered only when the server returns sport.sports", () => {
  assert.equal(parsePrefs({ sport: { sports: [] } }).sportsSupported, true);
  assert.equal(parsePrefs({ sport: { sports: ["cricket"] } }).sportsSupported, true);
  assert.equal(parsePrefs({ sport: { teams: [] } }).sportsSupported, false);
  assert.equal(parsePrefs({}).sportsSupported, false);
});
