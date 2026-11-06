import { test } from "node:test";
import assert from "node:assert/strict";
import { MAX_RECENT, addRecent, clearRecent, loadRecent, type Store } from "../src/lib/searchRecent.ts";

const mem = (): Store & { m: Map<string, string> } => {
  const m = new Map<string, string>();
  return { m, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v), removeItem: (k) => void m.delete(k) };
};

test("newest first, deduped case-insensitively, trimmed", () => {
  const s = mem();
  addRecent(s, "u1", "heat");
  addRecent(s, "u1", "  dune ");
  assert.deepEqual(addRecent(s, "u1", "HEAT"), ["HEAT", "dune"]);
});

test("capped at 10", () => {
  const s = mem();
  for (let i = 0; i < 15; i++) addRecent(s, "u1", `query ${i}`);
  const r = loadRecent(s, "u1");
  assert.equal(r.length, MAX_RECENT);
  assert.equal(r[0], "query 14");
  assert.equal(r[9], "query 5");
});

test("scoped per user; clearing one leaves the other", () => {
  const s = mem();
  addRecent(s, "u1", "heat");
  addRecent(s, "u2", "dune");
  assert.deepEqual(loadRecent(s, "u1"), ["heat"]);
  assert.deepEqual(loadRecent(s, "u2"), ["dune"]);
  assert.deepEqual(clearRecent(s, "u1"), []);
  assert.deepEqual(loadRecent(s, "u1"), []);
  assert.deepEqual(loadRecent(s, "u2"), ["dune"]);
});

test("no user id: nothing stored or read; short queries ignored; corrupt storage tolerated", () => {
  const s = mem();
  assert.deepEqual(addRecent(s, "", "heat"), []);
  assert.equal(s.m.size, 0);
  assert.deepEqual(addRecent(s, "u1", "a"), []);
  s.setItem("jt.search.recent.u1", "{not json");
  assert.deepEqual(loadRecent(s, "u1"), []);
  s.setItem("jt.search.recent.u1", JSON.stringify(["ok", 5, "", null]));
  assert.deepEqual(loadRecent(s, "u1"), ["ok"]);
});
