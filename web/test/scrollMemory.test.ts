import { test } from "node:test";
import assert from "node:assert/strict";
import { anchorScroll, pickAnchor, rowKeys, rowScrolls } from "../src/lib/scrollMemory.ts";
import { createNavStack } from "../src/lib/navStack.ts";

test("row keys: titles (whitespace-normalised), repeats numbered, untitled rows by index", () => {
  assert.deepEqual(rowKeys(["Continue watching", "Because you liked\n Heat", null, "Top 10", "Top 10", ""]), ["Continue watching", "Because you liked Heat", "#2", "Top 10", "Top 10#2", "#5"]);
});

test("anchor: the first row still on screen below the header", () => {
  const rows = [
    { key: "A", top: -900, bottom: -600 },
    { key: "B", top: -580, bottom: 30 }, // only 30 px under a 0 header: still counts (> 8)
    { key: "C", top: 50, bottom: 350 },
  ];
  assert.deepEqual(pickAnchor(rows), { key: "B", top: -580 });
  assert.deepEqual(pickAnchor(rows, 40), { key: "C", top: 50 }); // B's last 30 px sit under a 40 px header
  assert.equal(pickAnchor([]), null);
});

test("anchor scroll: a row above grew by 260 px (Continue watching appeared) -> scroll 260 further, never below 0", () => {
  // saved: anchor at top 120 with scrollY 1500; after Back the anchor is at 380 with scrollY 1500
  assert.equal(anchorScroll(1500, 380, 120), 1760);
  assert.equal(anchorScroll(1500, 120, 120), 1500);
  assert.equal(anchorScroll(100, -50, 300), 0);
});

test("row scrolls: only moved rows, rounded, capped at 40; none -> undefined", () => {
  assert.deepEqual(rowScrolls([{ key: "A", left: 0 }, { key: "B", left: 448.4 }]), { B: 448 });
  assert.equal(rowScrolls([{ key: "A", left: 0 }]), undefined);
  const many = Array.from({ length: 60 }, (_, i) => ({ key: `r${i}`, left: 10 }));
  assert.equal(Object.keys(rowScrolls(many)!).length, 40);
});

test("nav entry keeps rows and anchor through save, and returns them on Back", () => {
  let st: unknown = null;
  const h = { get state() { return st; }, replaceState: (s: unknown) => { st = s; }, back() {} };
  const nav = createNavStack(h);
  nav.init();
  nav.save(1500, "Heat", { rows: { "Row 4": 448 }, anchor: { key: "Row 4", top: -355 } });
  assert.deepEqual(nav.onHashChange(), { depth: 0, scrollY: 1500, focus: "Heat", rows: { "Row 4": 448 }, anchor: { key: "Row 4", top: -355 } });
});

test("anchor: a card below the screen is never the anchor (cards are listed before rows)", () => {
  const boxes = [
    { key: "fid:g9", top: 2000, bottom: 2200 }, // a card far below
    { key: "Row 1", top: 100, bottom: 400 },
  ];
  assert.deepEqual(pickAnchor(boxes, 0, 844), { key: "Row 1", top: 100 });
});

