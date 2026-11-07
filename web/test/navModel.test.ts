import { test } from "node:test";
import assert from "node:assert/strict";
import { LIVE_ROUTES, liveDefault, liveTabFor, liveTabHref, liveTabs, navActive } from "../src/lib/navModel.ts";

const none = { liveTv: false, fantasyLinked: false, hasRecs: false, hasUpcoming: false };

test("Live sub-tabs: channels + sport always, the rest by condition, in order", () => {
  assert.deepEqual(liveTabs(none), ["channels", "sport"]);
  assert.deepEqual(liveTabs({ liveTv: true, fantasyLinked: true, hasRecs: true, hasUpcoming: true }), ["guide", "channels", "sport", "fantasy", "recordings"]);
  assert.deepEqual(liveTabs({ ...none, hasRecs: true }), ["channels", "sport", "recordings"]);
});

test("#/live default: Guide when opted in, else My Channels", () => {
  assert.equal(liveDefault(true), "livetv");
  assert.equal(liveDefault(false), "channels");
});

test("every old live route lights Live and maps to a tab", () => {
  for (const r of LIVE_ROUTES) assert.equal(navActive(r), "live", r);
  for (const r of LIVE_ROUTES.filter((x) => x !== "live")) assert.ok(liveTabFor(r), r);
  assert.equal(liveTabFor("games"), "sport");
});

test("tab hrefs point at routes that map back to the same tab", () => {
  for (const k of ["guide", "channels", "sport", "fantasy", "recordings"] as const) {
    assert.equal(liveTabFor(liveTabHref(k).slice(2)), k);
  }
});

test("other routes", () => {
  assert.equal(navActive("ask"), "search");
  assert.equal(navActive("journey"), "collections");
  assert.equal(navActive("manage"), "you");
  assert.equal(navActive("iptv"), "you");
  assert.equal(navActive("item"), null);
  assert.equal(navActive("home"), "home");
});
