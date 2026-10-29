import { test } from "node:test";
import assert from "node:assert/strict";

// lib/logoStyle listens for session changes on window
(globalThis as unknown as { window: EventTarget }).window = new EventTarget();
const { logoClass, logoOwner, registerLogoStyles } = await import("../src/lib/logoStyle.ts");

test("server style wins; unknown keeps each place's old look; junk ignored", () => {
  registerLogoStyles({ dogman: "mono", plain: "original", bad: "rainbow" });
  registerLogoStyles(null);
  assert.equal(logoClass("dogman", "white"), "logo-mono");
  assert.equal(logoClass("plain", "white"), "logo-original");
  assert.equal(logoClass("bad", "white"), "logo-white");
  assert.equal(logoClass("never-seen", "original"), "logo-original");
});

test("episodes draw their series' logo", () => {
  assert.equal(logoOwner({ Id: "ep", ParentLogoItemId: "series" }), "series");
  assert.equal(logoOwner({ Id: "movie", ImageTags: { Logo: "t" } }), "movie");
});
