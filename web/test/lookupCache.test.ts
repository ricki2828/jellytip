import { test } from "node:test";
import assert from "node:assert/strict";
import {
  createLookupCache,
  HIT_TTL_MS,
  MISS_TTL_MS,
} from "../src/lib/lookupCache.ts";

test("one user's hit is never served to another user", async () => {
  const c = createLookupCache<string>(() => 0);
  assert.equal(await c.get("a", "10", async () => "A-provider"), "A-provider");
  assert.equal(await c.get("b", "10", async () => null), null);
  assert.equal(await c.get("a", "10", async () => "x"), "A-provider");
});

test("clear (session change/logout) drops everything", async () => {
  const c = createLookupCache<string>(() => 0);
  await c.get("a", "10", async () => "A");
  c.clear();
  assert.equal(await c.get("a", "10", async () => null), null);
});

test("hits live 10 min, misses 2 min, failures count as misses", async () => {
  let t = 0;
  const c = createLookupCache<string>(() => t);
  let n = 0;
  const load = (v: string | null) => async () => (n++, v);
  await c.get("a", "1", load("hit"));
  await new Promise((r) => setTimeout(r, 0));
  t = HIT_TTL_MS - 1;
  await c.get("a", "1", load("again"));
  assert.equal(n, 1);
  t = HIT_TTL_MS + 1;
  assert.equal(await c.get("a", "1", load(null)), null);
  assert.equal(n, 2);
  await new Promise((r) => setTimeout(r, 0));
  t += MISS_TTL_MS - 1;
  await c.get("a", "1", load("late"));
  assert.equal(n, 2);
  t += 2;
  assert.equal(await c.get("a", "1", load("late")), "late");
  assert.equal(n, 3);
  assert.equal(
    await c.get("a", "2", async () => {
      throw new Error("x");
    }),
    null,
  );
});
