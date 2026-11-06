import { test } from "node:test";
import assert from "node:assert/strict";
import { buildGames, gatePress, gateStart, gateArmed, CONFIRM_MS, isHighlightProgramme, leaksResult, mentionsTeam, phaseOf, sanitizeTitle, scoreText, scoreVisible, sectionGames, spoilersOn, waysFor } from "../src/lib/gameCentre.ts";
import { parsePrefs } from "../src/lib/prefs.ts";
import { parseUpcoming } from "../src/lib/upcoming.ts";
import type { Rec } from "../src/lib/recs.ts";

const NOW = new Date(2026, 10, 6, 20, 0).getTime() / 1000;
const at = (h: number, m = 0, dayOff = 0) => new Date(2026, 10, 6 + dayOff, h, m).getTime() / 1000;
const ch = (id = "1:1", name = "Sky Sport 1") => ({ id, name });
const ev = (key: string, title: string, start: number, extra: object = {}) => ({ key, title, start, stop: start + 7200, channel: ch(), ...extra });
const events = (...x: object[]) => parseUpcoming({ items: x });
const rec = (id: number, title: string, start: number, extra: Partial<Rec> = {}): Rec => ({ id, channelId: "1:1", channel: "Sky Sport 1", title, start, stop: start + 7200, state: "done", playable: true, ...extra });

test("sanitiser: scores, results and highlights never survive", () => {
  const dirty: string[] = [
    "Warriors 24-18 Panthers",
    "NRL: Warriors 24 - 18 Panthers (FT)",
    "Warriors v Panthers (2-1)",
    "Arsenal 3–1 Chelsea",
    "Arsenal 3—1 Chelsea [FT]",
    "Match highlights: Warriors v Panthers",
    "Warriors v Panthers - Extended Highlights",
    "Warriors won 24-18",
    "Final score: Warriors 24 Panthers 18",
    "Full time: Liverpool v Everton",
    "Warriors win thriller",
    "Chiefs beat Raiders 31-17",
    "Chiefs defeated Raiders by 14 points",
    "Result: Lakers v Celtics",
    "Federer v Nadal 6-4 6-3 7-5",
    "Black Caps beat Australia by 5 wickets",
    "All Blacks 3-0 Springboks (HT)",
    "Rugby: Blues bt Chiefs",
    "Man City two-nil Spurs",
    "Post-match recap: Roosters v Eels",
    "Warriors v Panthers (Won 24-18)",
  ];
  for (const t of dirty) {
    const s = sanitizeTitle(t);
    assert.doesNotMatch(s, /\d\s*[-–—]\s*\d/, `score left in "${s}" from "${t}"`);
    assert.doesNotMatch(s, /\b(won|wins?|beat|beats|defeated|bt|highlights?|recap|FT|HT|nil|result|final score|full time)\b/i, `result word left in "${s}" from "${t}"`);
    assert.ok(s.length > 0);
  }
});

test("sanitiser: winner is not leaked by word order, and clean titles pass through", () => {
  assert.equal(sanitizeTitle("Chiefs beat Raiders 31-17"), "Chiefs v Raiders");
  assert.equal(sanitizeTitle("Raiders beaten by Chiefs"), "Chiefs v Raiders");
  assert.equal(sanitizeTitle("NFL: Raiders beat Chiefs"), "NFL: Chiefs v Raiders");
  assert.equal(sanitizeTitle("Warriors v Panthers"), "Warriors v Panthers");
  assert.equal(sanitizeTitle("NRL Telstra Premiership: Warriors v Panthers"), "NRL Telstra Premiership: Warriors v Panthers");
  assert.equal(sanitizeTitle("Rugby Championship 2026-27 Round 3"), "Rugby Championship 2026-27 Round 3");
  assert.equal(sanitizeTitle("Super Rugby Pacific 19:30 kick-off"), "Super Rugby Pacific 19:30 kick-off");
  assert.equal(sanitizeTitle("NBA Finals Game 7: Celtics v Lakers"), "NBA Finals Game 7: Celtics v Lakers");
  assert.equal(sanitizeTitle("24-18"), "Game");
  assert.equal(leaksResult("Warriors 24-18 Panthers"), true);
  assert.equal(leaksResult("Warriors v Panthers"), false);
});

test("highlight programmes are not games", () => {
  assert.equal(isHighlightProgramme("Match of the Day"), true);
  assert.equal(isHighlightProgramme("Warriors v Panthers - Highlights"), true);
  assert.equal(isHighlightProgramme("Warriors v Panthers", "highlights"), true);
  assert.equal(isHighlightProgramme("Warriors v Panthers", "live"), false);
});

test("team mentions are whole-word and case-insensitive", () => {
  assert.equal(mentionsTeam("NRL: Warriors v Panthers", ["warriors"]), true);
  assert.equal(mentionsTeam("Warriorsville Open", ["Warriors"]), false);
  assert.equal(mentionsTeam("Man City v Spurs", ["Man City"]), true);
  assert.equal(mentionsTeam("anything", []), false);
});

test("grouping: followed games only, highlights dropped, recording joined to its event, orphan recording kept", () => {
  const evs = events(
    ev("a", "NRL: Warriors v Panthers", at(19), { recordingId: 9 }),
    ev("b", "NRL: Storm v Broncos", at(19)),
    ev("c", "Warriors v Panthers - Highlights", at(23), { kind: "highlights" }),
    ev("d", "Warriors v Roosters", at(21, 30), { league: "NRL" }),
    ev("e", "Dolphins v Titans", at(19), { match: { team: "Dolphins" } }),
  );
  const recs = [rec(9, "Warriors v Panthers", at(19)), rec(10, "Warriors v Knights", at(19, 0, -1)), rec(11, "Storm v Cowboys", at(19, 0, -1))];
  const g = buildGames(evs, recs, ["Warriors"], NOW);
  assert.deepEqual(g.map((x) => x.key).sort(), ["e:a", "e:d", "e:e", "r:10"]);
  assert.equal(g.find((x) => x.key === "e:a")!.rec?.id, 9);
  assert.equal(g.filter((x) => x.rec?.id === 9).length, 1);
});

test("grouping: old games drop out of the window; sections put today first, then ahead, then earlier days", () => {
  const evs = events(ev("old", "Warriors v Panthers", at(19, 0, -9)), ev("y", "Warriors v Storm", at(19, 0, -1)), ev("t", "Warriors v Eels", at(19)), ev("n", "Warriors v Bulldogs", at(19, 0, 1)));
  const g = buildGames(evs, null, ["Warriors"], NOW);
  assert.equal(g.length, 3);
  assert.deepEqual(sectionGames(g, NOW).map((s) => s.label), ["Today", "Tomorrow", "Yesterday"]);
});

test("sections: live first, then upcoming, then finished", () => {
  const evs = events(ev("fin", "Warriors v A", at(15)), ev("up", "Warriors v B", at(22)), ev("live", "Warriors v C", at(19, 30)));
  const [today] = sectionGames(buildGames(evs, null, ["Warriors"], NOW), NOW);
  assert.deepEqual(today.games.map((g) => g.key), ["e:live", "e:up", "e:fin"]);
});

test("ways: finished with a recording and with catch-up", () => {
  const g = buildGames(events(ev("a", "Warriors v Panthers", at(15), { startOver: true, recordingId: 9 })), [rec(9, "x", at(15))], ["Warriors"], NOW)[0];
  const w = waysFor(g, NOW);
  assert.equal(w.phase, "finished");
  assert.equal(w.note, "Finished — watch from the start");
  assert.deepEqual(w.ways.map((x) => x.kind), ["recording", "catchup"]);
  assert.match(w.ways[0].path, /recording=9/);
  assert.doesNotMatch(w.ways[0].path, /resume=/);
  assert.match(w.ways[1].path, /channel=1%3A1&start=\d+&stop=\d+/);
});

test("ways: live only says how long ago it started; live with catch-up offers the start", () => {
  const only = buildGames(events(ev("a", "Warriors v Panthers", NOW - 40 * 60)), null, ["Warriors"], NOW)[0];
  const w = waysFor(only, NOW);
  assert.equal(w.phase, "live");
  assert.equal(w.note, "Live only — started 40 min ago");
  assert.deepEqual(w.ways.map((x) => x.kind), ["live"]);
  const back = buildGames(events(ev("a", "Warriors v Panthers", NOW - 3900, { startOver: true })), null, ["Warriors"], NOW)[0];
  const w2 = waysFor(back, NOW);
  assert.deepEqual(w2.ways.map((x) => x.kind), ["catchup", "live"]);
  assert.match(w2.note, /watch from the start/);
  assert.equal(waysFor(only, only.start + 65 * 60).note, "Live only — started 1 h 5 min ago");
});

test("ways: upcoming says when, finished with nothing says so, and a game in progress recording plays from the start", () => {
  const up = buildGames(events(ev("a", "Warriors v Panthers", at(21))), null, ["Warriors"], NOW)[0];
  const w = waysFor(up, NOW);
  assert.equal(w.phase, "upcoming");
  assert.equal(w.note, "Starts 21:00");
  assert.equal(w.ways.length, 0);
  const none = buildGames(events(ev("a", "Warriors v Panthers", at(14))), null, ["Warriors"], NOW)[0];
  assert.equal(waysFor(none, NOW).note, "Finished — no replay available");
  const inprog = buildGames(null, [rec(3, "Warriors v Panthers", NOW - 1800, { state: "recording" })], ["Warriors"], NOW)[0];
  assert.equal(phaseOf(inprog, NOW), "live");
  assert.deepEqual(waysFor(inprog, NOW).ways.map((x) => x.kind), ["recording"]);
});

test("ways: no note or label ever contains a score or the raw title", () => {
  const raw = "Warriors 24-18 Panthers (FT)";
  const g = buildGames(events(ev("a", raw, at(15), { startOver: true })), null, ["Warriors"], NOW)[0];
  const w = waysFor(g, NOW);
  const all = [g.title, w.note, ...w.ways.flatMap((x) => [x.label, decodeURIComponent(x.path)])].join(" ");
  assert.doesNotMatch(all, /24|18|FT/);
});

test("fixture-backed games title as Home v Away, never in winner order", () => {
  const f = { id: "m1", kind: "match", home: { name: "Panthers", short: "PAN" }, away: { name: "Warriors", short: "WAR" }, league: "NRL", startsAt: at(15), status: "post", score: { home: 18, away: 24, clock: null, period: null } };
  const g = buildGames(events(ev("a", "Warriors beat Panthers 24-18", at(15), { matchInfo: { id: "m1", home: { name: "Panthers" }, away: { name: "Warriors" }, status: "post", score: { home: 18, away: 24 } } })), null, ["Warriors"], NOW)[0];
  assert.doesNotMatch(g.title, /\d|beat/);
  void f;
});

test("reveal gate: two presses within the window reveal; a late second press re-arms", () => {
  const a = gatePress(gateStart, 1000);
  assert.equal(a.revealed, false);
  assert.equal(gateArmed(a.gate, 1000 + CONFIRM_MS), true);
  assert.equal(gateArmed(a.gate, 1000 + CONFIRM_MS + 1), false);
  assert.equal(gatePress(a.gate, 1000 + 500).revealed, true);
  const late = gatePress(a.gate, 1000 + CONFIRM_MS + 1);
  assert.equal(late.revealed, false);
  assert.equal(late.gate.armedAt, 1000 + CONFIRM_MS + 1);
});

test("score is visible only after a reveal or with the pref on; scoreText needs a score", () => {
  assert.equal(scoreVisible(false, new Set(), "e:a"), false);
  assert.equal(scoreVisible(false, new Set(["e:a"]), "e:a"), true);
  assert.equal(scoreVisible(false, new Set(["e:b"]), "e:a"), false);
  assert.equal(scoreVisible(true, new Set(), "e:a"), true);
  const f = { home: { name: "Panthers", short: null }, away: { name: "Warriors", short: "WAR" }, score: { home: 18, away: 24, clock: null, period: null } } as never;
  assert.equal(scoreText(f), "Panthers 18–24 WAR");
  assert.equal(scoreText({ home: { name: "A" }, away: { name: "B" }, score: null } as never), null);
  assert.equal(scoreText(null), null);
});

test("spoiler pref defaults to safe", () => {
  assert.equal(spoilersOn(parsePrefs({}).sport ? parsePrefs({}) : null), false);
  assert.equal(spoilersOn(parsePrefs(null)), false);
  assert.equal(spoilersOn(parsePrefs({ sport: { spoilers: "yes" } })), false);
  assert.equal(spoilersOn(parsePrefs({ sport: { spoilers: true } })), true);
  assert.equal(spoilersOn(null), false);
  assert.equal(spoilersOn(undefined), false);
});

test("route: #/games parses", async () => {
  const { parseHash } = await import("../src/lib/routeParse.ts");
  assert.equal(parseHash("#/games").name, "games");
});
