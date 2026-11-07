import { test } from "node:test";
import assert from "node:assert/strict";
import { atLabel, parseClipPicks, pickState } from "../src/lib/clipPicks.ts";

test("atLabel formats m:ss and clamps", () => {
  assert.equal(atLabel(0), "0:00");
  assert.equal(atLabel(65), "1:05");
  assert.equal(atLabel(-4), "0:00");
  assert.equal(atLabel(59.6), "1:00");
});

test("pickState precedence: library, then IPTV, then admin Get it, else none", () => {
  const iptv = { id: "p:m:1", title: "X" } as never;
  assert.equal(pickState({ itemId: "a", iptv }, true, false), "library");
  assert.equal(pickState({ itemId: "a" }, false, false), "none"); // item the client cannot load
  assert.equal(pickState({ iptv }, false, false), "iptv");
  assert.equal(pickState({ tmdbId: 5 }, false, true), "get");
  assert.equal(pickState({ tmdbId: 5 }, false, false), "none");
  assert.equal(pickState({}, false, true), "none");
});

test("parseClipPicks drops junk and keeps picks without iptv", () => {
  const out = parseClipPicks<{ title: string }>([null, 3, { year: 1 }, { title: "Heat", atSec: 12 }, { title: "Alien", iptv: "junk" }]);
  assert.deepEqual(out.map((p) => p.title), ["Heat", "Alien"]);
  assert.equal(out[0].iptv, null);
  assert.equal(out[1].iptv, null);
  assert.deepEqual(parseClipPicks("nope"), []);
});
