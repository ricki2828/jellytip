import { test } from "node:test";
import assert from "node:assert/strict";
import { hasServerRanking, kindPill, mayBuffer, parseQualitySummary, parseSport, parseSuggest, qualityLabel, toggleName } from "../src/lib/sport.ts";
import { parseOnNow } from "../src/lib/onNow.ts";
import { parsePrefs } from "../src/lib/prefs.ts";

const N = 1_000_000;
test("sport fields are read when present and absent otherwise", () => {
  const s = parseSport({ quality: { bucket: "fhd50", height: 1080, fps: 50 }, event: { key: "a-b", alternates: ["1:2", 3] }, match: { team: "Warriors" } }, { kind: "live", sport: true });
  assert.equal(s.kind, "live");
  assert.equal(s.sport, true);
  assert.equal(s.quality?.bucket, "fhd50");
  assert.deepEqual(s.alternates, ["1:2"]);
  assert.equal(s.match?.team, "Warriors");
  const none = parseSport({ name: "x" });
  assert.equal(hasServerRanking(none), false);
  assert.equal(none.quality, null);
});

test("labels: quality chips, pills and the buffer hint", () => {
  assert.equal(qualityLabel({ bucket: "uhd", height: 2160, fps: 50 }), "UHD");
  assert.equal(qualityLabel({ bucket: "fhd50", height: 1080, fps: 50 }), "1080 50fps");
  assert.equal(qualityLabel({ bucket: "hd", height: 720, fps: 25 }), "720");
  assert.equal(qualityLabel({ bucket: "dead", height: null, fps: null }), null);
  assert.equal(qualityLabel({ bucket: "slow", height: 1080, fps: 25 }), "1080");
  assert.equal(mayBuffer({ bucket: "slow", height: 1080, fps: 25 }), true);
  assert.deepEqual(kindPill("highlights"), { text: "REPLAY", tone: "replay" });
  assert.equal(kindPill("other"), null);
});

test("on now: the server's order is kept when it ranks; one card per event", () => {
  const items = [
    { channel: { id: "1:1", name: "Rugby" }, sport: false, now: { start: N - 1, stop: N + 9, title: "Show", kind: "other" } },
    { channel: { id: "1:2", name: "Sky" }, sport: true, now: { start: N - 1, stop: N + 9, title: "NFL", kind: "live" }, event: { key: "nfl", alternates: ["1:3"] } },
    { channel: { id: "1:3", name: "Alt" }, sport: true, now: { start: N - 1, stop: N + 9, title: "NFL", kind: "live" }, event: { key: "nfl", alternates: [] } },
  ];
  const r = parseOnNow({ channels: items }, N);
  assert.deepEqual(r.map((o) => o.channelId), ["1:1", "1:2"]);
  assert.equal(r[1].info.alternates[0], "1:3");
});

test("settings parsers tolerate odd input", () => {
  assert.deepEqual(parseSuggest({ teams: ["Warriors", { name: "49ers" }, 3], leagues: [{ name: "NRL" }, "NRL"] }).map((s) => `${s.type}:${s.name}`), ["team:Warriors", "team:49ers", "league:NRL"]);
  assert.deepEqual(parseSuggest(null), []);
  const q = parseQualitySummary({ buckets: { fhd: 40, uhd: 2, dead: 0 }, progress: { done: 10, total: 100 } });
  assert.deepEqual(q?.counts, [{ bucket: "uhd", n: 2 }, { bucket: "fhd", n: 40 }]);
  assert.deepEqual(q?.progress, { done: 10, total: 100 });
  assert.equal(parseQualitySummary({}), null);
  assert.deepEqual(toggleName(["Warriors"], "warriors"), []);
  assert.deepEqual(parsePrefs({ sport: { teams: ["A", "A", 1], leagues: "x" } }).sport, { teams: ["A"], leagues: [] });
});
