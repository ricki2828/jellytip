import { test } from "node:test";
import assert from "node:assert/strict";
import { aboutLine, cleanSha, formatBuiltAt, versionString, BUILD } from "../src/lib/buildInfo.ts";

test("versionString is 0.1.0+sha and sanitises", () => {
  assert.equal(versionString("abc1234"), "0.1.0+abc1234");
  assert.equal(versionString('ab"c; x'), "0.1.0+dev");
  assert.equal(versionString(""), "0.1.0+dev");
  assert.equal(BUILD.sha, "dev"); // no define under node
});

test("cleanSha", () => {
  assert.equal(cleanSha("  AbC1234 "), "AbC1234");
  assert.equal(cleanSha(undefined), "dev");
  assert.equal(cleanSha("abc"), "dev");
});

test("formatBuiltAt local time, tolerant", () => {
  const iso = new Date(2026, 10, 6, 18, 20).toISOString();
  assert.equal(formatBuiltAt(iso), "6 Nov 18:20");
  assert.equal(formatBuiltAt(new Date(2026, 0, 2, 3, 4).toISOString()), "2 Jan 03:04");
  assert.equal(formatBuiltAt(""), "");
  assert.equal(formatBuiltAt("nope"), "");
});

test("aboutLine", () => {
  const iso = new Date(2026, 10, 6, 18, 20).toISOString();
  assert.equal(aboutLine({ sha: "abc1234", builtAt: iso }), "Version 0.1.0 · build abc1234 · built 6 Nov 18:20");
  assert.equal(aboutLine({ sha: "dev", builtAt: "" }), "Version 0.1.0 · build dev");
});
