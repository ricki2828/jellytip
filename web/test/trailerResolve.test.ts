import { test } from "node:test";
import assert from "node:assert/strict";
import { createResolver, type Fetchers } from "../src/lib/trailerResolve.ts";
import { TrailerCache, TRANSIENT_MS } from "../src/lib/trailerPreview.ts";

const rel = (p: string) => `https://c.example/${p.replace(/^\//, "")}`;
function setup(over: Partial<Fetchers> = {}) {
  const calls = { jf: 0, iptv: 0 };
  let t = 1_000_000;
  const base: Fetchers = {
    jf: async () => "jf-url",
    iptv: async () => ({ status: 200, trailer: "v1/t.mp4" }),
    rel,
    ...over,
  };
  const f: Fetchers = { ...base, jf: (id) => (calls.jf++, base.jf(id)), iptv: (id) => (calls.iptv++, base.iptv(id)) };
  const r = createResolver(f, new TrailerCache(2), () => t);
  return { r, calls, tick: (ms: number) => (t += ms) };
}
const JF = { kind: "jf", id: "a" } as const;
const IP = { kind: "iptv", id: "p:m:1" } as const;

test("jf: success is cached for good; a library answer with no trailer is final", async () => {
  const s = setup({ jf: async () => "u" });
  assert.equal(await s.r.resolve(JF), "u");
  assert.equal(await s.r.resolve(JF), "u");
  assert.equal(s.calls.jf, 1);
  const none = setup({ jf: async () => null });
  assert.equal(await none.r.resolve(JF), null);
  assert.equal(await none.r.resolve(JF), null);
  assert.equal(none.calls.jf, 1);
});

test("jf: a thrown error (401, network) is transient: backs off ~1 min, then asks again", async () => {
  let fail = true;
  const s = setup({ jf: async () => { if (fail) throw new Error("401"); return "u"; } });
  assert.equal(await s.r.resolve(JF), null);
  assert.equal(await s.r.resolve(JF), null); // inside the back-off: no new request
  assert.equal(s.calls.jf, 1);
  s.tick(TRANSIENT_MS + 1);
  fail = false;
  assert.equal(await s.r.resolve(JF), "u");
  assert.equal(s.calls.jf, 2);
});

test("iptv: 200 with no trailer spends the budget (one re-check, then stop)", async () => {
  const s = setup({ iptv: async () => ({ status: 200, trailer: null }) });
  await s.r.resolve(IP);
  await s.r.resolve(IP);
  await s.r.resolve(IP);
  assert.equal(s.calls.iptv, 2);
});

test("iptv: 429, 5xx and throws are transient and do not spend the budget", async () => {
  for (const status of [429, 500, 404]) {
    const s = setup({ iptv: async () => ({ status }) });
    assert.equal(await s.r.resolve(IP), null);
    assert.equal(await s.r.resolve(IP), null);
    assert.equal(s.calls.iptv, 1, `status ${status} backs off`);
  }
  const answers: ({ status: number; trailer?: string } | Error)[] = [new Error("net"), { status: 429 }, { status: 200 }, { status: 200 }, { status: 200, trailer: "v1/t.mp4" }];
  const s = setup({ iptv: async () => { const a = answers.shift()!; if (a instanceof Error) throw a; return a; } });
  for (let i = 0; i < 5; i++) { await s.r.resolve(IP); s.tick(TRANSIENT_MS + 1); }
  // throw + 429 did not count; two 200-empties used the whole budget, so the fifth answer is never asked
  assert.equal(s.calls.iptv, 4);
  assert.equal(await s.r.resolve(IP), null);
});

test("iptv: a playable path resolves and caches; a web link is final", async () => {
  const s = setup({ iptv: async () => ({ status: 200, trailer: "v1/t.mp4" }) });
  assert.equal(await s.r.resolve(IP), "https://c.example/v1/t.mp4");
  await s.r.resolve(IP);
  assert.equal(s.calls.iptv, 1);
  const w = setup({ iptv: async () => ({ status: 200, trailer: "https://youtube.com/watch?v=1" }) });
  assert.equal(await w.r.resolve(IP), null);
  await w.r.resolve(IP);
  assert.equal(w.calls.iptv, 1);
});

test("session change while a request is in flight: nothing is cached or returned", async () => {
  let release!: (u: string) => void;
  const s = setup({ jf: () => new Promise<string>((res) => (release = res)) });
  const p = s.r.resolve(JF);
  s.r.reset(); // another user signs in
  release("old-user-url?api_key=OLD");
  assert.equal(await p, null);
  assert.equal(s.calls.jf, 1);
  s.r.reset();
  const again = setup({ jf: async () => "new" });
  assert.equal(await again.r.resolve(JF), "new");
});

test("start(): a late answer after cancel never reaches the player", async () => {
  let release!: (u: string) => void;
  const s = setup({ jf: () => new Promise<string>((res) => (release = res)) });
  const got: (string | null)[] = [];
  const cancel = s.r.start(JF, (u) => got.push(u));
  cancel(); // moved to another title
  release("late");
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(got, []);
  const s2 = setup({ jf: async () => "now" });
  s2.r.start(JF, (u) => got.push(u));
  await new Promise((r) => setTimeout(r, 0));
  assert.deepEqual(got, ["now"]);
});
