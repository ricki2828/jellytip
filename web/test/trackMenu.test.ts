import { test } from "node:test";
import assert from "node:assert/strict";
import { audioOptionLabel, audioSelectable, initialFocus, jellyfinAudioLabel, nextFocus } from "../src/lib/trackMenu.ts";

test("audio labels come from exposed metadata, else Unknown", () => {
  assert.equal(audioOptionLabel("English · en"), "English · en");
  assert.equal(audioOptionLabel("Track 2"), "Unknown");
  assert.equal(audioOptionLabel(""), "Unknown");
  assert.equal(audioOptionLabel(undefined), "Unknown");
  assert.equal(audioOptionLabel("und"), "Unknown");
  assert.equal(jellyfinAudioLabel({ DisplayTitle: "French - AC3 5.1" }), "French - AC3 5.1");
  assert.equal(jellyfinAudioLabel({ Language: "spa" }), "spa");
  assert.equal(jellyfinAudioLabel({ Language: "und" }), "Unknown");
  assert.equal(jellyfinAudioLabel({}), "Unknown");
});

test("audio is only offered when the engine exposes more than one track", () => {
  const t = (id: string) => ({ id, label: id, active: false });
  assert.equal(audioSelectable([]), false);
  assert.equal(audioSelectable([t("a")]), false);
  assert.equal(audioSelectable([t("a"), t("b")]), true);
});

test("arrow navigation wraps and skips disabled items", () => {
  const d = [false, true, false, false];
  assert.equal(nextFocus(d, 0, "ArrowDown"), 2);
  assert.equal(nextFocus(d, 3, "ArrowDown"), 0);
  assert.equal(nextFocus(d, 0, "ArrowUp"), 3);
  assert.equal(nextFocus(d, 2, "ArrowUp"), 0);
  assert.equal(nextFocus([true, false, false, true], 0, "Home"), 1);
  assert.equal(nextFocus([true, false, false, true], 0, "End"), 2);
  assert.equal(nextFocus([true, true], 0, "ArrowDown"), -1);
  assert.equal(nextFocus([false], 0, "ArrowDown"), 0);
});

test("opening focuses the active choice, else the first enabled one", () => {
  assert.equal(initialFocus([{ active: false }, { active: true }, { active: false }]), 1);
  assert.equal(initialFocus([{ active: false, disabled: true }, { active: false }]), 1);
  assert.equal(initialFocus([{ active: true, disabled: true }, { active: false }]), 1);
  assert.equal(initialFocus([]), -1);
});

import { menuKeyAction, playerKeyWhileMenu, rowTarget, tabTarget } from "../src/lib/trackMenu.ts";

test("menu keys: arrows stay inside the menu, Escape/Backspace close, unrelated keys pass", () => {
  assert.deepEqual(menuKeyAction("Escape"), { kind: "close" });
  assert.deepEqual(menuKeyAction("Backspace"), { kind: "close" });
  assert.deepEqual(menuKeyAction("ArrowDown"), { kind: "move", key: "ArrowDown" });
  assert.deepEqual(menuKeyAction("ArrowLeft"), { kind: "row", dir: -1 });
  assert.deepEqual(menuKeyAction("ArrowRight"), { kind: "row", dir: 1 });
  assert.deepEqual(menuKeyAction("Tab", true), { kind: "tab", shift: true });
  assert.deepEqual(menuKeyAction("Enter"), { kind: "swallow" });
  assert.deepEqual(menuKeyAction("x"), { kind: "pass" });
});

test("player-level keys: closed menu is untouched; open menu closes on Back even off-focus, defers the rest", () => {
  assert.equal(playerKeyWhileMenu(false, "ArrowDown"), "normal");
  assert.equal(playerKeyWhileMenu(false, "Backspace"), "normal");
  assert.equal(playerKeyWhileMenu(true, "Backspace"), "close");
  assert.equal(playerKeyWhileMenu(true, "Escape"), "close");
  assert.equal(playerKeyWhileMenu(true, "ArrowDown"), "defer");
});

test("Tab is trapped and Left/Right step along a row without wrapping", () => {
  assert.equal(tabTarget(4, 3, false), 0);
  assert.equal(tabTarget(4, 0, true), 3);
  assert.equal(tabTarget(4, -1, false), 0);
  assert.equal(tabTarget(0, 0, false), -1);
  assert.equal(rowTarget([false, false, false, false], 1, 1), 2);
  assert.equal(rowTarget([false, false, false, false], 3, 1), 3);
  assert.equal(rowTarget([false, true, false], 0, 1), 2);
  assert.equal(rowTarget([false, false], 0, -1), 0);
});
