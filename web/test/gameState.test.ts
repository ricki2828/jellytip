import { test } from "node:test";
import assert from "node:assert/strict";
import { blowoutLine, classify, closerGame, cricketFormat, deciderRows, favouriteSide, matchMinute, periodNo, recapPlays, resultLine, scoreGapText, secsLeft, sportOf, wicketsInHand, type Sport, type StateInput } from "../src/lib/gameState.ts";
import { applyReveal, parseLive, tabsFor, revealAll, type LiveStats } from "../src/lib/liveStats.ts";
import { parseFixtureList } from "../src/lib/gameCentre.ts";

const live = (sport: Sport, home: number, away: number, period: string | null, clock: string | null, more: Partial<StateInput> = {}) => classify({ sport, status: "in", score: { home, away }, period, clock, ...more });
const kind = (...a: Parameters<typeof live>) => live(...a).kind;

test("sportOf: layout first, then the league key; rugby splits NRL from union", () => {
  assert.equal(sportOf("nfl"), "nfl");
  assert.equal(sportOf("basketball"), "nba");
  assert.equal(sportOf("rugby", "nrl"), "nrl");
  assert.equal(sportOf("rugby", "rugby-league-origin"), "nrl");
  assert.equal(sportOf("rugby", "super-rugby"), "rugby");
  assert.equal(sportOf("f1"), "default");
  assert.equal(sportOf(null, "nba"), "nba");
  assert.equal(sportOf(null, "ncaaf"), "nfl");
  assert.equal(sportOf(null, "eng.1"), "football");
  assert.equal(sportOf(null, "zzz"), "default");
});

test("parsers: period, clock, minute, cricket format", () => {
  assert.equal(periodNo("Q4"), 4);
  assert.equal(periodNo("3rd Quarter"), 3);
  assert.equal(periodNo("OT"), 5);
  assert.equal(periodNo("2OT"), 6);
  assert.equal(periodNo("2nd Half"), 2);
  assert.equal(periodNo(null), null);
  assert.equal(secsLeft("4:32"), 272);
  assert.equal(secsLeft("63'"), null);
  assert.equal(matchMinute("63'", null), 63);
  assert.equal(matchMinute("45+2'", null), 47);
  assert.equal(matchMinute("63:12", null), 63);
  assert.equal(matchMinute(null, "2nd Half"), 41);
  assert.equal(matchMinute(null, null), null);
  assert.equal(cricketFormat("ipl"), "t20");
  assert.equal(cricketFormat("cricket-international", "ODI"), "odi");
  assert.equal(cricketFormat("cricket-international", "Day 3"), "test");
  assert.equal(cricketFormat(null), "test");
});

test("status: pre, final, nothing known, and a break from the period text", () => {
  assert.equal(classify({ sport: "nfl", status: "pre", score: null, period: null, clock: null }).kind, "pre");
  assert.equal(classify({ sport: "nfl", status: "post", score: { home: 1, away: 0 }, period: null, clock: null }).kind, "final");
  assert.equal(classify({ sport: "nfl", status: "STATUS_FINAL", score: { home: 1, away: 0 }, period: null, clock: null }).kind, "final");
  assert.equal(classify({ sport: "nfl", status: null, score: null, period: null, clock: null }).kind, "pre");
  assert.equal(kind("nfl", 10, 7, "Halftime", null), "break");
  assert.equal(kind("football", 1, 0, "HT", null), "break");
  assert.equal(kind("football", 1, 0, "Half Time", "45'"), "break");
  assert.equal(kind("rugby", 10, 7, "Half time", null), "break");
  assert.equal(kind("cricket", 150, 3, "Innings Break", null), "break");
  assert.equal(kind("cricket", 150, 3, "Day 2", "Tea"), "break");
  assert.equal(kind("nba", 50, 48, "End of 2nd Quarter", null), "break");
  assert.equal(kind("nba", 25, 20, "End of 1st Quarter", null), "live", "a quarter break is too short for a recap");
  assert.equal(classify({ sport: "nba", status: "STATUS_HALFTIME", score: { home: 50, away: 48 }, period: null, clock: null }).kind, "break");
  assert.equal(live("nba", 50, 48, "Q2", "3:00").score, undefined);
});

test("no score while live is just live", () => {
  assert.equal(classify({ sport: "nba", status: "in", score: null, period: "Q4", clock: "1:00" }).kind, "live");
});

test("NFL thresholds", () => {
  assert.equal(kind("nfl", 21, 14, "Q4", "6:00"), "live-tight", "one score in Q4");
  assert.equal(kind("nfl", 24, 14, "Q4", "6:00"), "live", "10 points in Q4 is a two-score game, not tight");
  assert.equal(kind("nfl", 10, 7, "Q3", "6:00"), "live-tight", "a field goal in Q3");
  assert.equal(kind("nfl", 14, 7, "Q2", "6:00"), "live", "early");
  assert.equal(kind("nfl", 7, 7, "OT", "9:00"), "live-tight");
  assert.equal(kind("nfl", 35, 7, "Q3", "10:00"), "blowout", "28 in Q3");
  assert.equal(kind("nfl", 34, 7, "Q3", "10:00"), "live", "27 in Q3 is not yet");
  assert.equal(kind("nfl", 28, 7, "Q4", "14:00"), "blowout", "21 in Q4");
  assert.equal(kind("nfl", 24, 7, "Q4", "7:59"), "blowout", "17 with under 8 minutes");
  assert.equal(kind("nfl", 24, 7, "Q4", "12:00"), "live", "17 with 12 minutes left");
  assert.equal(kind("nfl", 40, 0, "Q2", "1:00"), "live", "never a blowout before the second half");
  assert.equal(live("nfl", 28, 7, "Q4", "14:00").leader, "home");
  assert.equal(live("nfl", 7, 28, "Q4", "14:00").leader, "away");
  assert.equal(live("nfl", 7, 7, "Q4", "14:00").leader, null);
  assert.equal(live("nfl", 28, 7, "Q4", "14:00").margin, 21);
});

test("NBA thresholds", () => {
  assert.equal(kind("nba", 100, 95, "Q4", "5:00"), "live-tight");
  assert.equal(kind("nba", 100, 90, "Q4", "5:00"), "live", "10 in Q4 with five minutes is not tight (> 8)");
  assert.equal(kind("nba", 80, 78, "Q3", "6:00"), "live-tight");
  assert.equal(kind("nba", 80, 70, "Q3", "6:00"), "live");
  assert.equal(kind("nba", 90, 90, "OT", "2:00"), "live-tight", "overtime is always tight");
  assert.equal(kind("nba", 90, 60, "Q3", "6:00"), "blowout", "30 in Q3");
  assert.equal(kind("nba", 110, 90, "Q4", "9:00"), "blowout", "20 in Q4");
  assert.equal(kind("nba", 105, 90, "Q4", "3:30"), "blowout", "15 with under four minutes");
  assert.equal(kind("nba", 105, 90, "Q4", "6:00"), "live");
  assert.equal(kind("nba", 60, 30, "Q2", "6:00"), "live", "never a blowout in the first half");
});

test("NRL thresholds (minute clock)", () => {
  assert.equal(kind("nrl", 14, 10, "2nd Half", "55'"), "live-tight", "one try at 55'");
  assert.equal(kind("nrl", 14, 8, "2nd Half", "52'"), "live-tight");
  assert.equal(kind("nrl", 14, 8, "1st Half", "30'"), "live", "early");
  assert.equal(kind("nrl", 20, 8, "2nd Half", "66'"), "live-tight", "12 points: two scores at 65'");
  assert.equal(kind("nrl", 20, 8, "2nd Half", "60'"), "live", "12 points at 60' is not tight yet");
  assert.equal(kind("nrl", 40, 10, "1st Half", "40'"), "blowout", "30 at 40'");
  assert.equal(kind("nrl", 34, 10, "2nd Half", "56'"), "blowout", "24 at 55'");
  assert.equal(kind("nrl", 28, 10, "2nd Half", "70'"), "blowout", "18 at 68'");
  assert.equal(kind("nrl", 28, 10, "2nd Half", "60'"), "live");
  assert.equal(kind("nrl", 28, 10, null, null), "live", "no minute known");
});

test("rugby union thresholds", () => {
  assert.equal(kind("rugby", 17, 10, "2nd Half", "60'"), "live-tight");
  assert.equal(kind("rugby", 24, 10, "2nd Half", "70'"), "live-tight", "14 points at 68'");
  assert.equal(kind("rugby", 24, 10, "2nd Half", "60'"), "live");
  assert.equal(kind("rugby", 45, 10, "2nd Half", "50'"), "blowout", "35 at 45'");
  assert.equal(kind("rugby", 38, 10, "2nd Half", "62'"), "blowout", "28 at 60'");
  assert.equal(kind("rugby", 31, 10, "2nd Half", "72'"), "blowout", "21 at 70'");
});

test("football thresholds", () => {
  assert.equal(kind("football", 1, 1, "2nd Half", "70'"), "live-tight");
  assert.equal(kind("football", 1, 0, "2nd Half", "88'"), "live-tight");
  assert.equal(kind("football", 2, 0, "2nd Half", "88'"), "live", "two goals is not tight");
  assert.equal(kind("football", 0, 0, "1st Half", "20'"), "live", "early");
  assert.equal(kind("football", 4, 0, "2nd Half", "55'"), "blowout");
  assert.equal(kind("football", 3, 0, "2nd Half", "75'"), "blowout");
  assert.equal(kind("football", 3, 0, "2nd Half", "60'"), "live");
});

test("cricket: limited-overs chases only; Tests stay live", () => {
  const t20 = (rr: string, rrr: string) => ({ leagueKey: "ipl", situation: { rr, rrr } });
  assert.equal(kind("cricket", 120, 3, "2nd Innings", "14.0 ov", t20("8.5", "9.2")), "live-tight");
  assert.equal(kind("cricket", 60, 4, "2nd Innings", "10.0 ov", t20("6.0", "13.5")), "blowout", "hopeless");
  assert.equal(kind("cricket", 100, 1, "2nd Innings", "8.0 ov", t20("12.5", "6.0")), "blowout", "cruising");
  assert.equal(kind("cricket", 100, 3, "2nd Innings", "10.0 ov", t20("10", "12.5")), "live");
  assert.equal(kind("cricket", 150, 3, "1st Innings", "15.0 ov", { leagueKey: "ipl", situation: { rr: "9.2" } }), "live", "first innings has no chase");
  const odi = (rr: string, rrr: string) => ({ leagueKey: "cricket-international", situation: { rr, rrr } });
  assert.equal(kind("cricket", 200, 4, "ODI 2nd Innings", "35.0 ov", odi("5.5", "6.2")), "live-tight");
  assert.equal(kind("cricket", 200, 4, "ODI 2nd Innings", "35.0 ov", odi("5.5", "6.9")), "live", "1.4 off is not tight in an ODI");
  assert.equal(kind("cricket", 100, 6, "ODI 2nd Innings", "30.0 ov", odi("3.5", "8")), "blowout");
  assert.equal(kind("cricket", 220, 3, "Test Day 3", "Session 2", { leagueKey: "cricket-international", situation: { rr: "3", rrr: "3" } }), "live", "Tests are never tight or blowout");
});

test("win probability: only when passed in, margin rules win", () => {
  // NBA Q4 10 points: margin says live, a 50% win probability says tight, 97% says blowout (Q4 late)
  assert.equal(kind("nba", 100, 90, "Q4", "5:00"), "live");
  assert.equal(kind("nba", 100, 90, "Q4", "5:00", { winProb: 0.5 }), "live-tight");
  assert.equal(kind("nba", 100, 90, "Q4", "5:00", { winProb: 0.97 }), "blowout");
  assert.equal(kind("nba", 100, 90, "Q4", "5:00", { winProb: 0.03 }), "blowout", "either side");
  assert.equal(kind("nba", 100, 90, "Q4", "5:00", { winProb: null }), "live", "spoilers off passes null");
  assert.equal(kind("nba", 100, 90, "Q2", "5:00", { winProb: 0.97 }), "live", "not in the first half");
  assert.equal(kind("nfl", 20, 3, "Q4", "9:00", { winProb: 0.99 }), "blowout");
  assert.equal(kind("nfl", 17, 3, "Q2", "9:00", { winProb: 0.7 }), "live");
  assert.equal(kind("football", 1, 0, "2nd Half", "70'", { winProb: 0.5 }), "live-tight");
  assert.equal(kind("nrl", 30, 12, "2nd Half", "70'", { winProb: 0.99 }), "blowout");
  // a blowout margin beats a tight win probability
  assert.equal(kind("nba", 120, 90, "Q4", "9:00", { winProb: 0.5 }), "blowout");
});

test("default sports are live", () => {
  assert.equal(kind("default", 1, 0, "Lap 3", null), "live");
});

test("favouriteSide: exact names only, never both, never an event", () => {
  const side = (name: string, short: string | null = null) => ({ name, short, logo: null, record: null, color: null, altColor: null, country: null });
  const fx = { kind: "match" as const, home: side("Golden State Warriors", "GSW"), away: side("Los Angeles Lakers", "LAL") };
  assert.equal(favouriteSide(fx, ["Los Angeles Lakers"]), "away");
  assert.equal(favouriteSide(fx, ["gsw"]), "home");
  assert.equal(favouriteSide(fx, ["Warriors"]), null, "a nickname is not a name (NRL Warriors)");
  assert.equal(favouriteSide(fx, ["GSW", "LAL"]), null);
  assert.equal(favouriteSide({ ...fx, kind: "event" as const }, ["GSW"]), null);
  assert.equal(favouriteSide(null, ["GSW"]), null);
});

const names = { home: "Chiefs", away: "Bills" };
const D = (o: Record<string, unknown>): LiveStats => parseLive({ status: "in", ...o })!;

test("deciderRows per sport", () => {
  const nfl = D({ layout: "nfl", score: { home: 21, away: 17 }, period: "Q4", clock: "2:00", situation: { down: "3rd", distance: 4, yardLine: "BUF 41", possession: "away", redZone: true, timeouts: { home: 1, away: 2 } }, winProb: 0.52 });
  const st = classify({ sport: "nfl", status: "in", score: nfl.score, period: nfl.period, clock: nfl.clock });
  assert.deepEqual(deciderRows(nfl, st, names, false), [["Down & distance", "3rd & 4"], ["Ball on", "BUF 41 · red zone"], ["Possession", "Bills"], ["Timeouts", "Chiefs 1 · Bills 2"], ["Score", "Chiefs by 4"]]);
  assert.ok(deciderRows(nfl, st, names, true).some(([k]) => k === "Win probability"), "win probability only when allowed");
  assert.ok(!deciderRows(nfl, st, names, false).some(([k]) => k === "Win probability"));

  const nba = D({ layout: "basketball", score: { home: 100, away: 98 }, period: "Q4", clock: "3:00", situation: { run: "KC 8-0 run", fouls: { home: 4, away: 5 } } });
  const nst = classify({ sport: "nba", status: "in", score: nba.score, period: "Q4", clock: "3:00" });
  assert.deepEqual(deciderRows(nba, nst, names, false), [["Run", "KC 8-0 run"], ["Team fouls", "Chiefs 4 · Bills 5"], ["Score", "Chiefs by 2"]]);

  const ck = D({ layout: "cricket", score: { home: 120, away: 150 }, period: "2nd Innings", situation: { rr: "8.5", rrr: "9.2", wickets: 3, equation: "NZ need 31 off 20" } });
  const cst = classify({ sport: "cricket", status: "in", score: ck.score, period: "2nd Innings", clock: null, leagueKey: "ipl", situation: ck.situation });
  assert.deepEqual(deciderRows(ck, cst, names, false), [["Required rate", "9.2 (now 8.5)"], ["Wickets in hand", "7"], ["Equation", "NZ need 31 off 20"]]);

  const fb = D({ layout: "football", score: { home: 1, away: 1 }, period: "2nd Half", clock: "78'", situation: { cards: ["62' Yellow", "70' Red"] } });
  const fst = classify({ sport: "football", status: "in", score: fb.score, period: "2nd Half", clock: "78'" });
  assert.deepEqual(deciderRows(fb, fst, names, false), [["Clock", "78'"], ["Score", "Level"], ["Cards", "62' Yellow · 70' Red"]]);

  const nrl = D({ layout: "rugby", score: { home: 14, away: 10 }, period: "2nd Half", clock: "66'", plays: [{ text: "Try Smith", scoring: true, clock: "62'" }, { text: "Penalty", scoring: false }] });
  const rst = classify({ sport: "nrl", status: "in", score: nrl.score, period: "2nd Half", clock: "66'" });
  assert.deepEqual(deciderRows(nrl, rst, names, false), [["Score", "Chiefs by 4 · one score"], ["Time left", "14'"], ["Last score", "62' Try Smith"]]);
});

test("small helpers", () => {
  assert.equal(scoreGapText(4, "nrl"), "one score");
  assert.equal(scoreGapText(7, "nrl"), "two scores");
  assert.equal(scoreGapText(8, "rugby"), "two scores");
  assert.equal(wicketsInHand({ wickets: 3 }), 7);
  assert.equal(wicketsInHand({ wicketsInHand: 4 }), 4);
  assert.equal(wicketsInHand({ batting: { wickets: "2" } }), 8);
  assert.equal(wicketsInHand({}), null);
  assert.equal(wicketsInHand(null), null);
});

test("blowoutLine, recapPlays (oldest first, favourite flagged, capped), resultLine", () => {
  const d = D({ score: { home: 112, away: 78 }, period: "Q4", clock: "3:10", plays: [{ text: "c", scoring: true, team: "home", period: "Q4", clock: "3:10" }, { text: "x", scoring: false }, { text: "b", scoring: true, team: "away", period: "Q4", clock: "4:00" }, { text: "a", scoring: true, team: "home", points: 3, period: "Q3", clock: "1:00" }] });
  const st = classify({ sport: "nba", status: "in", score: d.score, period: "Q4", clock: "3:10" });
  assert.equal(blowoutLine(d, st, { home: "Lakers", away: "Suns" }, null), "Lakers lead 112–78 · Q4 3:10");
  assert.equal(blowoutLine(d, st, { home: "Lakers", away: "Suns" }, "home"), "Your team leads 112–78 · Q4 3:10");
  assert.equal(blowoutLine(d, st, { home: "Lakers", away: "Suns" }, "away"), "Lakers lead 112–78 · Q4 3:10");
  const r = recapPlays(d, "home");
  assert.deepEqual(r.map((p) => p.text), ["a", "b", "c"]);
  assert.deepEqual(r.map((p) => p.mine), [true, false, true]);
  assert.equal(r[0].delta, 3);
  assert.equal(recapPlays(d, "home", 2).length, 2);
  assert.equal(resultLine(d, { home: "Lakers", away: "Suns" }), "Lakers 112–78 Suns");
  assert.equal(resultLine(D({}), names), null);
});

// ---- closer game ----
const fxItems = (rows: Record<string, unknown>[]) =>
  parseFixtureList({ items: rows.map((r, i) => ({ fid: `f${i}`, league: "NBA", leagueKey: "nba", kickoff: 1000, status: "in", home: `H${i}`, away: `A${i}`, favourite: false, significance: 50, channels: [{ id: `c${i}`, name: `Chan ${i}`, confidence: 0.95 }], best: `c${i}`, ...r })) }).items;
const sc = (home: number, away: number, period = "Q4", clock = "5:00") => ({ home, away, period, clock });

test("closerGame: live, scored, with a channel; tight beats live; favourite beats tight; not the current game; no blowouts", () => {
  const items = fxItems([
    { score: sc(100, 70) }, // blowout: out
    { score: sc(60, 40, "Q2") }, // live
    { score: sc(90, 88), matchId: "m2" }, // tight
    { status: "pre", score: null },
    { score: sc(90, 89), channels: [] }, // no channel: out
    { score: sc(80, 79), matchId: "m-current" }, // the game being watched: out
  ]);
  const pick = closerGame(items, { matchId: "m-current" });
  assert.equal(pick?.item.fid, "f2");
  assert.match(pick!.path, /^\/iptv\/play\?channel=c2&t=/);
  assert.ok(!pick!.path.includes("nospoil"), "a live play keeps the drawer");
  assert.ok(pick!.path.includes("mi=m2"), "carries its match so the drawer opens");
  const withFav = fxItems([{ score: sc(90, 88) }, { score: sc(60, 40, "Q2"), favourite: true }]);
  assert.equal(closerGame(withFav, {})?.item.fid, "f1");
  assert.equal(closerGame(withFav, { fixtureId: "f1" })?.item.fid, "f0", "excluded by fixture id");
  assert.equal(closerGame(fxItems([{ score: sc(100, 70) }]), {}), null);
  assert.equal(closerGame([], {}), null);
});

// ---- reveal gating ----
const full = (): LiveStats =>
  D({
    layout: "nfl",
    score: { home: 14, away: 10 },
    period: "Q3",
    clock: "5:00",
    updatedAt: 1000,
    stats: [{ label: "Yards", home: "200", away: "180" }],
    leaders: [{ name: "P. Mahomes", line: "20/30" }],
    situation: { down: "1st" },
    plays: [{ text: "TD", scoring: true }],
    moments: [{ at: 900, label: "TD", kind: "td", team: "home" }, { at: 990, label: "FG", kind: "fg", team: "away" }],
    winProb: { home: 0.6 },
    market: { kind: "h2h", home: 0.6, away: 0.4, source: "polymarket" },
    chat: { source: "reddit", comments: [{ author: "a", text: "early", at: 800 }, { author: "b", text: "late", at: 995 }, { author: "c", text: "untimed" }] },
    games: [{ matchId: "g", home: "A", away: "B", score: { home: 1, away: 2 } }],
  });

test("applyReveal: everything shown by default, same object back", () => {
  const d = full();
  assert.equal(applyReveal(d, revealAll), d);
});

test("applyReveal: a snapshot that is not revealed hides score, clock, stats, odds, plays and games", () => {
  const d = applyReveal(full(), (at) => at !== 1000 && at !== undefined);
  assert.equal(d.score, null);
  assert.equal(d.clock, null);
  assert.equal(d.period, null);
  assert.deepEqual(d.stats, []);
  assert.deepEqual(d.leaders, []);
  assert.equal(d.situation, null);
  assert.equal(d.winProb, null);
  assert.equal(d.market, null);
  assert.deepEqual(d.games, []);
  assert.deepEqual(d.plays, []);
});

test("applyReveal: moments and chat are gated one by one with their own time, the snapshot with updatedAt, plays with undefined", () => {
  const asked: (number | undefined)[] = [];
  const d = applyReveal(full(), (at) => {
    asked.push(at);
    return at === undefined || at <= 950 || at === 1000;
  });
  assert.deepEqual(d.moments.map((m) => m.label), ["TD"]);
  assert.deepEqual(d.chat!.comments.map((c) => c.author), ["a", "c"]);
  assert.equal(d.score?.home, 14);
  assert.equal(d.plays.length, 1);
  assert.ok(asked.includes(1000) && asked.includes(900) && asked.includes(990) && asked.includes(800) && asked.includes(995) && asked.includes(undefined));
});

test("applyReveal: plays need both the snapshot and the untimed answer", () => {
  assert.deepEqual(applyReveal(full(), (at) => at !== undefined).plays, []);
});

test("a hidden score cannot drive the state (blowout masked to live)", () => {
  const d = D({ layout: "basketball", score: { home: 120, away: 70 }, period: "Q4", clock: "5:00", updatedAt: 1000 });
  assert.equal(classify({ sport: "nba", status: d.status, score: d.score, period: d.period, clock: d.clock }).kind, "blowout");
  const m = applyReveal(d, (at) => at !== 1000);
  // status is masked too (an unreleased "final" must not leak): with nothing revealed the state is the neutral "pre", never blowout or final
  assert.equal(m.status, null);
  assert.equal(classify({ sport: "nba", status: m.status, score: m.score, period: m.period, clock: m.clock }).kind, "pre");
});

test("tabsFor: no Odds tab with spoilers off", () => {
  const t: [string, string][] = [["stats", "Stats"], ["plays", "Plays"], ["odds", "Odds"]];
  assert.deepEqual(tabsFor(t, false).map(([k]) => k), ["stats", "plays"]);
  assert.deepEqual(tabsFor(t, true).map(([k]) => k), ["stats", "plays", "odds"]);
});
