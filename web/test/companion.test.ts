import { test } from "node:test";
import assert from "node:assert/strict";
import { autoCloseDecision, autoOpenDecision, dropsTooHigh, panelKeyAction, panelModeFor, scorebugText, scorebugVisible, stepTab, tickClock, clockRunning, MOMENT_OPEN_MS, USER_CLOSE_QUIET_MS } from "../src/lib/companion.ts";

const base = { nowMs: 1_000_000, replay: false, panelOpen: false, userClosedAtMs: null, kind: "live" as const, freshMoment: false };

test("auto-open: break opens, a fresh moment opens, quiet otherwise", () => {
  assert.equal(autoOpenDecision({ ...base, kind: "break" }), "break");
  assert.equal(autoOpenDecision({ ...base, freshMoment: true }), "moment");
  assert.equal(autoOpenDecision({ ...base, freshMoment: true, kind: "live-tight" }), "moment");
  assert.equal(autoOpenDecision(base), null);
});
test("auto-open: never in replay, when open, in a blowout moment, pre or final moments", () => {
  assert.equal(autoOpenDecision({ ...base, kind: "break", replay: true }), null);
  assert.equal(autoOpenDecision({ ...base, kind: "break", panelOpen: true }), null);
  assert.equal(autoOpenDecision({ ...base, freshMoment: true, kind: "blowout" }), null);
  assert.equal(autoOpenDecision({ ...base, freshMoment: true, kind: "pre" }), null);
  assert.equal(autoOpenDecision({ ...base, freshMoment: true, kind: "final" }), null);
});
test("auto-open: not within 2 min of the viewer closing it", () => {
  assert.equal(autoOpenDecision({ ...base, kind: "break", userClosedAtMs: base.nowMs - 60_000 }), null);
  assert.equal(autoOpenDecision({ ...base, kind: "break", userClosedAtMs: base.nowMs - USER_CLOSE_QUIET_MS }), "break");
});
test("auto-close: moment after 6 s, break when play resumes, never user-opened or touched", () => {
  const c = { nowMs: 10_000, openedBy: "moment" as const, openedAtMs: 10_000 - MOMENT_OPEN_MS + 1, kind: "live" as const, touched: false, replay: false };
  assert.equal(autoCloseDecision(c), false);
  assert.equal(autoCloseDecision({ ...c, openedAtMs: 10_000 - MOMENT_OPEN_MS }), true);
  assert.equal(autoCloseDecision({ ...c, openedBy: "break", kind: "break" }), false);
  assert.equal(autoCloseDecision({ ...c, openedBy: "break", kind: "live" }), true);
  assert.equal(autoCloseDecision({ ...c, openedBy: "user", openedAtMs: 0 }), false);
  assert.equal(autoCloseDecision({ ...c, openedAtMs: 0, touched: true }), false);
  assert.equal(autoCloseDecision({ ...c, openedBy: "break", kind: "break", replay: true }), true);
});
test("panel mode: portrait phone is overlay, remembered overlay sticks, default squeeze", () => {
  assert.equal(panelModeFor(true, null), "overlay");
  assert.equal(panelModeFor(false, "overlay"), "overlay");
  assert.equal(panelModeFor(false, null), "squeeze");
  assert.equal(panelModeFor(false, "junk"), "squeeze");
});
test("drop watch: over 2% of enough frames", () => {
  assert.equal(dropsTooHigh(1500, 31), true);
  assert.equal(dropsTooHigh(1500, 30), false);
  assert.equal(dropsTooHigh(30, 20), false); // too few frames to judge
  assert.equal(dropsTooHigh(NaN, 5), false);
});
test("keys: hidden controls", () => {
  const k = (key: string, panelOpen = false, controlsInUse = false, available = true) => panelKeyAction({ key, panelOpen, controlsInUse, available });
  assert.equal(k("ArrowUp"), "toggle-panel");
  assert.equal(k("ArrowUp", true), "toggle-panel");
  assert.equal(k("ArrowDown"), "channels");
  assert.equal(k("ArrowDown", true), null);
  assert.equal(k("ArrowLeft"), null);
  assert.equal(k("ArrowLeft", true), "tab-prev");
  assert.equal(k("ArrowRight", true), "tab-next");
  assert.equal(k("Escape"), null);
  assert.equal(k("Escape", true), "close-panel");
  assert.equal(k("Backspace", true), "close-panel");
  assert.equal(k("Enter"), null);
});
test("keys: controls in use keep focus navigation, but Back still closes the panel; unavailable is never ours", () => {
  const k = (key: string, panelOpen: boolean, controlsInUse: boolean, available = true) => panelKeyAction({ key, panelOpen, controlsInUse, available });
  assert.equal(k("ArrowUp", false, true), null);
  assert.equal(k("ArrowRight", true, true), null);
  assert.equal(k("Escape", true, true), "close-panel");
  assert.equal(k("ArrowUp", false, false, false), null);
  assert.equal(k("Escape", true, false, false), null);
});
test("stepTab clamps at the ends", () => {
  assert.equal(stepTab(0, 4, -1), 0);
  assert.equal(stepTab(3, 4, 1), 3);
  assert.equal(stepTab(1, 4, 1), 2);
  assert.equal(stepTab(0, 0, 1), 0);
});
test("scorebug: text only with a revealed score; visibility rules", () => {
  assert.equal(scorebugText({ score: null, clock: "12:00", period: "Q1", home: "A", away: "B" }), null);
  assert.deepEqual(scorebugText({ score: { home: 21, away: 18 }, clock: "62:10", period: "2nd half", home: "NZL", away: "AUS" }), { teams: ["NZL", "AUS"], score: [21, 18], when: "2nd half 62:10" });
  const v = { nowMs: 100_000, changedAtMs: 95_000, pinned: false, panelOpen: false };
  assert.equal(scorebugVisible(v), true);
  assert.equal(scorebugVisible({ ...v, changedAtMs: 91_000 }), false);
  assert.equal(scorebugVisible({ ...v, changedAtMs: 1, pinned: true }), true);
  assert.equal(scorebugVisible({ ...v, pinned: true, panelOpen: true }), false);
  assert.equal(scorebugVisible({ ...v, changedAtMs: null }), false);
});
import { parseLive } from "../src/lib/liveStats.ts";
test("parseLive: a moment's wallclock wins over the first-observed time", () => {
  const d = parseLive({ moments: [{ at: 2000, wallclock: 1990, kind: "goal", label: "Goal", team: "home" }, { at: 2100, kind: "card", label: "Red" }] });
  assert.deepEqual(d?.moments.map((m) => m.at), [1990, 2100]);
});

test("tickClock: NFL/NBA count down, NRL/rugby/football count up, cap and floor, others untouched", () => {
  assert.equal(tickClock("12:05", "nfl", true, 5.9), "12:00");
  assert.equal(tickClock("0:03", "nba", true, 9), "0:00");
  assert.equal(tickClock("9:05", "nba", true, 6), "8:59");
  assert.equal(tickClock("62:10", "nrl", true, 3), "62:13");
  assert.equal(tickClock("59:58", "football", true, 4), "60:02");
  assert.equal(tickClock("12:05", "nfl", true, 400), "11:45"); // run-on capped at 20 s
  assert.equal(tickClock("12:05", "nfl", false, 5), "12:05"); // stopped
  assert.equal(tickClock("12:05", "nfl", true, 0.4), "12:05");
  assert.equal(tickClock("63'", "football", true, 10), "63'");
  assert.equal(tickClock("12:05", "cricket", true, 10), "12:05");
  assert.equal(tickClock("12:05", null, true, 10), "12:05");
  assert.equal(tickClock(null, "nfl", true, 10), null);
});
test("clockRunning: stopped at breaks, final, pre, or an unchanged clock 10+ s apart", () => {
  const cur = { clock: "5:00", period: "Q2", atMs: 100_000 };
  assert.equal(clockRunning("live", null, cur), true);
  assert.equal(clockRunning("break", null, cur), false);
  assert.equal(clockRunning("final", null, cur), false);
  assert.equal(clockRunning("pre", null, cur), false);
  assert.equal(clockRunning("live", { clock: "5:00", period: "Q2", atMs: 85_000 }, cur), false);
  assert.equal(clockRunning("live", { clock: "5:15", period: "Q2", atMs: 85_000 }, cur), true);
  assert.equal(clockRunning("live", { clock: "5:00", period: "Q2", atMs: 95_000 }, cur), true);
});
