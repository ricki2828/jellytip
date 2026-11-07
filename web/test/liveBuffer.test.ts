import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DEBOUNCE_MS, GRACE_MS, SPEC, STEP_DOWN_MS, TOAST_EVERY_MS, chaseTarget, effectiveProfile, hlsConfig, holdTarget, initAuto, loadChannelLevel, loadDefaultMode,
  modeLabel, onStall, onTick, saveChannelLevel, saveDefaultMode, toastDue, type KV,
} from "../src/lib/liveBuffer.ts";

const mem = (): KV & { m: Map<string, string> } => {
  const m = new Map<string, string>();
  return { m, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v), removeItem: (k) => void m.delete(k) };
};

test("auto starts Normal, steps up one per stall, caps at High", () => {
  let s = initAuto(null, 0);
  assert.equal(s.level, "normal");
  let r = onStall(s, 10_000); assert.equal(r.stepped, true); s = r.state; assert.equal(s.level, "high");
  r = onStall(s, 10_000 + GRACE_MS + 1); assert.equal(r.stepped, false); assert.equal(r.counted, true); assert.equal(r.state.level, "high");
});

test("stall inside the grace window after a step is counted but holds", () => {
  let s = onStall(initAuto(null, 0), 5_000).state;
  const r = onStall(s, 5_000 + DEBOUNCE_MS + 1);
  assert.equal(r.counted, true); assert.equal(r.stepped, false); assert.equal(r.state.level, "high"); assert.equal(r.state.stalls, 2);
});

test("flapping rebuffer events within the debounce are one stall", () => {
  const a = onStall(initAuto(null, 0), 1_000);
  const b = onStall(a.state, 1_500);
  assert.equal(b.counted, false); assert.equal(b.state, a.state);
});

test("steps down one after 10 min without a stall, floor Normal, never earlier", () => {
  let s = onStall(onStall(initAuto("high", 0), 1000).state, 1000 + GRACE_MS + DEBOUNCE_MS).state; // high, last stall recorded
  const t0 = s.lastStall;
  assert.equal(onTick(s, t0 + STEP_DOWN_MS - 1).stepped, false);
  const d = onTick(s, t0 + STEP_DOWN_MS);
  assert.equal(d.stepped, true); assert.equal(d.state.level, "normal");
  assert.equal(onTick(d.state, t0 + 3 * STEP_DOWN_MS).stepped, false);
});

test("a stall resets the step-down clock", () => {
  const s = initAuto("high", 0);
  const stalled = onStall(s, STEP_DOWN_MS - 1000).state;
  assert.equal(onTick(stalled, STEP_DOWN_MS + 5000).stepped, false);
});

test("remembered level seeds auto but is clamped to Normal..High", () => {
  assert.equal(initAuto("high", 0).level, "high");
  assert.equal(initAuto("max", 0).level, "high");
  assert.equal(initAuto("low", 0).level, "normal");
});

test("manual modes ignore the auto state; auto label shows the live level", () => {
  const s = initAuto("high", 0);
  assert.equal(effectiveProfile("max", s), "max");
  assert.equal(effectiveProfile("low", s), "low");
  assert.equal(effectiveProfile("auto", s), "high");
  assert.equal(modeLabel("auto", "high"), "Auto (High)");
  assert.equal(modeLabel("low", "high"), "Low");
});

test("per-channel memory round-trips; Normal clears it; junk is ignored", () => {
  const kv = mem();
  assert.equal(loadChannelLevel(kv, "7:42"), null);
  saveChannelLevel(kv, "7:42", "high");
  assert.equal(loadChannelLevel(kv, "7:42"), "high");
  assert.equal(loadChannelLevel(kv, "7:43"), null);
  saveChannelLevel(kv, "7:42", "normal");
  assert.equal(kv.m.size, 0);
  kv.setItem("jellytip.liveBuffer.ch.7:9", "banana");
  assert.equal(loadChannelLevel(kv, "7:9"), null);
  assert.equal(loadChannelLevel(null, "7:9"), null);
  assert.equal(loadChannelLevel(kv, null), null);
});

test("default mode defaults to auto and rejects junk", () => {
  const kv = mem();
  assert.equal(loadDefaultMode(kv), "auto");
  saveDefaultMode(kv, "high");
  assert.equal(loadDefaultMode(kv), "high");
  kv.setItem("jellytip.liveBuffer.default", "nope");
  assert.equal(loadDefaultMode(kv), "auto");
});

test("toast at most once per 5 min", () => {
  assert.equal(toastDue(0, 1), true);
  assert.equal(toastDue(1000, 1000 + TOAST_EVERY_MS - 1), false);
  assert.equal(toastDue(1000, 1000 + TOAST_EVERY_MS), true);
});

test("mapping: profiles are strictly increasing and hls config is valid", () => {
  const order = ["low", "normal", "high", "max"] as const;
  for (let i = 1; i < order.length; i++) {
    const a = SPEC[order[i - 1]], b = SPEC[order[i]];
    assert.ok(b.behindSec > a.behindSec && b.chaseTo >= a.chaseTo && b.stallHold >= a.stallHold && b.syncCount > a.syncCount && b.maxBufferLength >= a.maxBufferLength);
  }
  for (const p of order) {
    const h = hlsConfig(p);
    assert.ok(h.liveMaxLatencyDurationCount > h.liveSyncDurationCount);
    assert.ok(SPEC[p].chaseMax > SPEC[p].chaseTo);
    assert.ok(h.maxMaxBufferLength >= h.maxBufferLength);
  }
  assert.equal(SPEC.low.chaseMax, 6); assert.equal(SPEC.low.chaseTo, 1.5); // old behaviour preserved
});

test("chase and hold decisions", () => {
  assert.equal(chaseTarget("normal", 100, 90), null);
  assert.equal(chaseTarget("normal", 100, 80), 92);
  assert.equal(chaseTarget("low", 100, 90), 98.5);
  assert.equal(holdTarget("low", "stall", 0), 0);
  assert.equal(holdTarget("normal", "stall", 0.2), 6);
  assert.equal(holdTarget("normal", "stall", 6), 0);
  assert.equal(holdTarget("max", "start", 1), 6);
  assert.equal(holdTarget("high", "start", 1), 6);
  assert.equal(holdTarget("normal", "start", 0), 0); // channel changes stay instant
  assert.equal(holdTarget("low", "start", 0), 0);
});
