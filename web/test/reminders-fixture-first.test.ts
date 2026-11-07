import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { findReminder, parseReminders } from "../src/lib/reminders.ts";

const rems = parseReminders({
  items: [
    { id: "a", key: "fx:f1@100", fid: "f1", matchId: "m1", title: "A at B", start: 100, stop: 200, channel: { id: "p:1", name: "C" } },
    { id: "b", key: "p:2@300", title: "Prog", start: 300, stop: 400, channel: { id: "p:2", name: "D" } },
  ],
});

test("findReminder matches by key, fid or match id", () => {
  assert.equal(findReminder(rems, { key: "p:2@300" })?.id, "b");
  assert.equal(findReminder(rems, { fid: "f1" })?.id, "a");
  assert.equal(findReminder(rems, { matchId: "m1" })?.id, "a");
  assert.equal(findReminder(rems, { key: "zz", fid: null, matchId: undefined }), null);
  assert.equal(findReminder(null, { matchId: "m1" }), null);
});

test("the server's matchId is parsed (null when absent)", () => {
  assert.equal(rems[0].matchId, "m1");
  assert.equal(rems.find((r) => r.id === "b")?.matchId, null);
});

const src = (p: string) => readFileSync(new URL(`../src/${p}`, import.meta.url), "utf8");

test("every caller that knows a fixture / match id hands it to the reminder store", () => {
  assert.match(src("components/MatchInfo.tsx"), /addReminderFor\(\{.{0,400}matchId: f\.id/);
  assert.match(src("components/SearchLive.tsx"), /addReminderFor\(\{.{0,400}matchId: ahead\.fixture\?\.id/);
  const ip = src("screens/iptv/IptvPlayer.tsx");
  const calls = ip.match(/addReminderFor\(\{.{0,400}?matchId: [^}]*\}\)/g) ?? [];
  assert.equal(calls.length, 3);
  for (const c of calls) assert.match(c, /matchId: /, c);
  const gc = src("screens/iptv/GameCentre.tsx");
  assert.match(gc, /addReminder\(g\.event, \{ matchId: g\.fixture\?\.id \}\)/);
  assert.doesNotMatch(gc, /addReminder\(g\.event\)/);
  // Upcoming passes the event itself: addReminder reads e.fixture / e.fid from it
  assert.match(src("components/Upcoming.tsx"), /addReminder\(e\)/);
});

test("addReminder tries the fixture path first and falls back to the channel path only without a fixture (or when refused)", () => {
  const s = src("api/reminderStore.ts");
  const fn = s.slice(s.indexOf("export async function addReminder("), s.indexOf("export async function addFixtureReminder"));
  assert.ok(fn.indexOf("addFx(") > -1 && fn.indexOf("addFx(") < fn.indexOf('"v1/iptv/reminders"'), "fixture call comes before the channel POST");
  assert.match(fn, /if \(fid \|\| matchId\)/);
});

test("lookups find a reminder by fid / match id everywhere the toggle state is shown", () => {
  for (const p of ["components/Upcoming.tsx", "screens/iptv/GameCentre.tsx", "screens/iptv/IptvPlayer.tsx"]) assert.match(src(p), /findReminder\(/, p);
  assert.doesNotMatch(src("screens/iptv/GameCentre.tsx"), /r\.event\.key === g\./);
});
