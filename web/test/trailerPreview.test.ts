import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { acquirePreview, iptvPlayable, jfTarget, previewGate, previewHolder, settle, targetKey, TrailerCache, type PreviewEnv } from "../src/lib/trailerPreview.ts";

const calm: PreviewEnv = { touch: false, reducedMotion: false, saveData: false, hidden: false };
const rel = (p: string) => `https://c.example/${p.replace(/^\//, "")}`;

test("source selection: Jellyfin items use their own or their series' trailer", () => {
  assert.deepEqual(jfTarget({ Id: "m1", Type: "Movie" }), { kind: "jf", id: "m1" });
  assert.deepEqual(jfTarget({ Id: "e1", Type: "Episode", SeriesId: "s1" }), { kind: "jf", id: "s1" });
  assert.deepEqual(jfTarget({ Id: "se1", Type: "Season", SeriesId: "s1" }), { kind: "jf", id: "s1" });
  assert.equal(jfTarget({ Id: "e2", Type: "Episode" }), null);
  assert.equal(jfTarget({ Id: "p1", Type: "Person" }), null);
  assert.notEqual(targetKey({ kind: "jf", id: "x" }), targetKey({ kind: "iptv", id: "x" }));
});

test("source selection: IPTV trailer must be a playable video, not a web page", () => {
  assert.equal(iptvPlayable("v1/trailers/abc.mp4", rel), "https://c.example/v1/trailers/abc.mp4");
  assert.equal(iptvPlayable("/v1/trailers/abc", rel), "https://c.example/v1/trailers/abc");
  assert.equal(iptvPlayable("https://cdn.example/t.webm?x=1", rel), "https://cdn.example/t.webm?x=1");
  assert.equal(iptvPlayable("https://www.youtube.com/watch?v=1", rel), null);
  assert.equal(iptvPlayable("javascript:alert(1)", rel), null);
  assert.equal(iptvPlayable("//evil.example/t.mp4", rel), null);
  assert.equal(iptvPlayable(null, rel), null);
  assert.equal(iptvPlayable("", rel), null);
});

test("gate: allowed when calm; each blocker blocks with its reason", () => {
  assert.deepEqual(previewGate({ env: calm, trigger: "focus" }), { ok: true });
  assert.deepEqual(previewGate({ autoplayPref: true, env: calm, trigger: "passive" }), { ok: true });
  assert.deepEqual(previewGate({ autoplayPref: false, env: calm, trigger: "focus" }), { ok: false, reason: "pref" });
  assert.deepEqual(previewGate({ env: calm, trigger: "focus", rotating: true }), { ok: false, reason: "rotating" });
  assert.deepEqual(previewGate({ env: { ...calm, reducedMotion: true }, trigger: "focus" }), { ok: false, reason: "reduced-motion" });
  assert.deepEqual(previewGate({ env: { ...calm, saveData: true }, trigger: "focus" }), { ok: false, reason: "save-data" });
  assert.deepEqual(previewGate({ env: { ...calm, hidden: true }, trigger: "focus" }), { ok: false, reason: "hidden" });
});

test("gate: touch autoplays like desktop (muted); the pref still turns it off", () => {
  const touch = { ...calm, touch: true };
  assert.deepEqual(previewGate({ env: touch, trigger: "focus" }), { ok: true });
  assert.deepEqual(previewGate({ env: touch, trigger: "passive" }), { ok: true });
  assert.deepEqual(previewGate({ autoplayPref: false, env: touch, trigger: "focus" }), { ok: false, reason: "pref" });
});

test("cache: Jellyfin-style final answers are never re-asked", () => {
  const c = new TrailerCache(2);
  assert.equal(c.shouldFetch("jf:1"), true);
  c.record("jf:1", null, true);
  assert.equal(c.shouldFetch("jf:1"), false);
  c.record("jf:2", "u", true);
  assert.equal(c.shouldFetch("jf:2"), false);
  assert.equal(c.get("jf:2"), "u");
});

test("cache: an IPTV miss is re-checked once on a later focus, then left alone", () => {
  const c = new TrailerCache(2);
  assert.equal(c.shouldFetch("iptv:a"), true);
  c.record("iptv:a", null); // companion has not cached it yet
  assert.equal(c.shouldFetch("iptv:a"), true); // the one re-check
  c.record("iptv:a", null);
  assert.equal(c.shouldFetch("iptv:a"), false);
  assert.equal(c.get("iptv:a"), null);
  // the re-check finds it
  c.record("iptv:b", null);
  c.record("iptv:b", "u");
  assert.equal(c.shouldFetch("iptv:b"), false);
  assert.equal(c.get("iptv:b"), "u");
  // a web link will never play inline: final at once
  c.record("iptv:c", null, true);
  assert.equal(c.shouldFetch("iptv:c"), false);
  c.clear();
  assert.equal(c.shouldFetch("iptv:a"), true); // per session
});

test("debounce: passing titles quickly never fires; resting does", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const fired: string[] = [];
    let cancel = settle(2500, () => fired.push("a"));
    mock.timers.tick(1000);
    cancel(); // moved to the next tile
    cancel = settle(2500, () => fired.push("b"));
    mock.timers.tick(1000);
    cancel();
    cancel = settle(2500, () => fired.push("c"));
    mock.timers.tick(2499);
    assert.deepEqual(fired, []);
    mock.timers.tick(1);
    assert.deepEqual(fired, ["c"]);
  } finally {
    mock.timers.reset();
  }
});

test("one preview at a time: a new holder stops the previous, release is safe after takeover", () => {
  const stops: string[] = [];
  const relA = acquirePreview("hero-a", () => stops.push("a"));
  assert.equal(previewHolder(), "hero-a");
  const relB = acquirePreview("details", () => stops.push("b"));
  assert.deepEqual(stops, ["a"]);
  assert.equal(previewHolder(), "details");
  relA(); // stale release must not evict the new holder
  assert.equal(previewHolder(), "details");
  acquirePreview("details", () => stops.push("b2")); // same id re-acquiring does not stop itself
  assert.deepEqual(stops, ["a"]);
  relB();
  assert.equal(previewHolder(), "details");
});
