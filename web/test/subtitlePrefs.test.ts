import { test } from "node:test";
import assert from "node:assert/strict";
import { DEFAULT_SUBTITLE_PREFS, SUB_PREFS_KEY, clampOffset, loadSubtitlePrefs, offsetLabel, parseSubtitlePrefs, saveSubtitlePrefs, stepOffset, subtitleStyleVars } from "../src/lib/subtitlePrefs.ts";

const mem = () => {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), m };
};

test("defaults are readable and apply with nothing stored", () => {
  assert.deepEqual(loadSubtitlePrefs(mem()), DEFAULT_SUBTITLE_PREFS);
  assert.deepEqual(DEFAULT_SUBTITLE_PREFS, { size: "M", background: "shadow", offset: 0 });
  assert.deepEqual(subtitleStyleVars(DEFAULT_SUBTITLE_PREFS), { "--sub-scale": "1", "--sub-lift": "0%" });
});

test("preferences persist across a reload", () => {
  const s = mem();
  saveSubtitlePrefs({ size: "XL", background: "box", offset: 10 }, s);
  assert.ok(s.m.has(SUB_PREFS_KEY));
  assert.deepEqual(loadSubtitlePrefs(s), { size: "XL", background: "box", offset: 10 });
});

test("junk, corrupt JSON and out-of-range values fall back safely", () => {
  assert.deepEqual(parseSubtitlePrefs({ size: "huge", background: 3, offset: "x" }), DEFAULT_SUBTITLE_PREFS);
  assert.equal(parseSubtitlePrefs({ offset: 999 }).offset, 30);
  assert.equal(parseSubtitlePrefs({ offset: -999 }).offset, -5);
  const s = mem();
  s.setItem(SUB_PREFS_KEY, "{not json");
  assert.deepEqual(loadSubtitlePrefs(s), DEFAULT_SUBTITLE_PREFS);
  assert.deepEqual(loadSubtitlePrefs({ getItem: () => { throw new Error("blocked"); } }), DEFAULT_SUBTITLE_PREFS);
  assert.doesNotThrow(() => saveSubtitlePrefs(DEFAULT_SUBTITLE_PREFS, { setItem: () => { throw new Error("full"); } }));
});

test("size maps to a scale, position steps are bounded", () => {
  assert.equal(subtitleStyleVars({ ...DEFAULT_SUBTITLE_PREFS, size: "S" })["--sub-scale"], "0.8");
  assert.equal(subtitleStyleVars({ ...DEFAULT_SUBTITLE_PREFS, size: "XL" })["--sub-scale"], "1.7");
  assert.equal(subtitleStyleVars({ ...DEFAULT_SUBTITLE_PREFS, offset: 15 })["--sub-lift"], "15%");
  assert.equal(stepOffset(30, 1), 30);
  assert.equal(stepOffset(-5, -1), -5);
  assert.equal(stepOffset(0, 1), 5);
  assert.equal(clampOffset(7), 5);
  assert.equal(offsetLabel(0), "Default");
  assert.equal(offsetLabel(10), "+10%");
});
