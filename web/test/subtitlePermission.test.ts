import { test } from "node:test";
import assert from "node:assert/strict";
import { createPermissionLookup } from "../src/lib/subtitlePermission.ts";
import { readFileSync } from "node:fs";
import { applySessionPatch, SESSION_META_EVENT } from "../src/lib/sessionPatch.ts";
import { shouldLookupPolicy } from "../src/lib/remoteSubtitles.ts";

type S = { token: string; userId: string; isAdmin?: boolean; canManageSubtitles?: boolean };
const setup = (start: S, policy = { EnableSubtitleManagement: true } as { IsAdministrator?: boolean; EnableSubtitleManagement?: boolean }) => {
  let cur: S | null = start;
  const calls = { fetch: 0, patch: [] as unknown[] };
  let release: () => void = () => undefined;
  const gate = new Promise<void>((r) => (release = r));
  const lookup = createPermissionLookup({
    current: () => cur,
    fetchPolicy: async () => { calls.fetch++; await gate; return policy; },
    patch: (who, p) => { calls.patch.push([who, p]); return !!cur && cur.token === who.token && cur.userId === who.userId; },
  });
  return { lookup, calls, release, set: (s: S | null) => (cur = s) };
};

test("policy lookup rules: unknown always, a stored no once per app start, yes and admin never", () => {
  assert.equal(shouldLookupPolicy({}, false), true);
  assert.equal(shouldLookupPolicy({ canManageSubtitles: false }, false), true);
  assert.equal(shouldLookupPolicy({ canManageSubtitles: false }, true), false);
  assert.equal(shouldLookupPolicy({ canManageSubtitles: true }, false), false);
  assert.equal(shouldLookupPolicy({ isAdmin: true, canManageSubtitles: false }, false), false);
  assert.equal(shouldLookupPolicy(null, false), false);
});

test("concurrent callers (Details + Player) share one request", async () => {
  const t = setup({ token: "t", userId: "u" });
  const a = t.lookup();
  const b = t.lookup();
  t.release();
  await Promise.all([a, b]);
  assert.equal(t.calls.fetch, 1);
  assert.equal(t.calls.patch.length, 1);
  assert.deepEqual(t.calls.patch[0], [{ token: "t", userId: "u" }, { isAdmin: false, canManageSubtitles: true }]);
});

test("a stored 'no' is refreshed once per app start, then left alone", async () => {
  const t = setup({ token: "t", userId: "u", canManageSubtitles: false });
  t.release();
  await t.lookup();
  await t.lookup();
  assert.equal(t.calls.fetch, 1);
  const known = setup({ token: "t", userId: "u", canManageSubtitles: true });
  known.release();
  await known.lookup();
  assert.equal(known.calls.fetch, 0);
});

test("a failed lookup writes nothing and is retried on the next visit, not in a loop", async () => {
  let n = 0;
  const patched: unknown[] = [];
  const lookup = createPermissionLookup({ current: () => ({ token: "t", userId: "u" }), fetchPolicy: async () => { n++; throw new Error("net"); }, patch: (w, p) => (patched.push(p), true) });
  await lookup();
  assert.equal(n, 1);
  await lookup();
  assert.equal(n, 2);
  assert.equal(patched.length, 0);
});

test("lookups are per token+user: a different session is looked up on its own", async () => {
  const t = setup({ token: "t1", userId: "u1" });
  t.release();
  await t.lookup();
  t.set({ token: "t2", userId: "u2" });
  await t.lookup();
  assert.equal(t.calls.fetch, 2);
  assert.deepEqual((t.calls.patch[1] as unknown[])[0], { token: "t2", userId: "u2" });
});

test("a session replaced mid-request is not patched (the patch callback re-checks)", async () => {
  const t = setup({ token: "t", userId: "u" });
  const p = t.lookup();
  t.set({ token: "other", userId: "u" });
  t.release();
  await p;
  // the lookup asked to patch the ORIGINAL session; the real patchSession refuses it because the token differs
  assert.deepEqual((t.calls.patch[0] as unknown[])[0], { token: "t", userId: "u" });
});

test("session patch: same session only, identity fields cannot change, a distinct event name", () => {
  const cur = { token: "t", userId: "u", isAdmin: false };
  assert.deepEqual(applySessionPatch(cur, { token: "t", userId: "u" }, { canManageSubtitles: true }), { token: "t", userId: "u", isAdmin: false, canManageSubtitles: true });
  assert.equal(applySessionPatch(cur, { token: "x", userId: "u" }, { canManageSubtitles: true }), null);
  assert.equal(applySessionPatch(cur, { token: "t", userId: "v" }, { canManageSubtitles: true }), null);
  assert.equal(applySessionPatch(null, { token: "t", userId: "u" }, {}), null);
  assert.deepEqual(applySessionPatch(cur, { token: "t", userId: "u" }, { token: "evil" } as never)?.token, "t");
  assert.equal(cur.isAdmin, false); // input untouched
  assert.equal(SESSION_META_EVENT, "jellytip-session-meta");
  assert.notEqual(SESSION_META_EVENT, "jellytip-session");
});

test("the permission lookup never goes through setSession / the jellytip-session event", () => {
  const src = (f: string) => readFileSync(new URL(f, import.meta.url), "utf8");
  const api = src("../src/api/subtitles.ts");
  assert.doesNotMatch(api, /setSession/);
  assert.match(api, /patch: patchSession/);
  const jf = src("../src/api/jellyfin.ts");
  const body = jf.slice(jf.indexOf("export function patchSession"), jf.indexOf("export function authHeader"));
  assert.match(body, /SESSION_META_EVENT/);
  assert.doesNotMatch(body, /"jellytip-session"/);
  assert.doesNotMatch(body, /setSession\(/);
});
