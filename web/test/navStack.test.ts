import { test } from "node:test";
import assert from "node:assert/strict";
import { createNavStack } from "../src/lib/navStack.ts";

/** Browser history with hash entries: push = location.hash, replace = location.replace, back = traversal. */
function fakeHistory() {
  const entries: { url: string; state: unknown }[] = [{ url: "#/home", state: null }];
  let i = 0;
  const h = {
    get state() {
      return entries[i].state;
    },
    replaceState(s: unknown) {
      entries[i].state = s;
    },
    back() {
      if (i > 0) i--;
    },
    push(url: string) {
      entries.splice(i + 1);
      entries.push({ url, state: null });
      i++;
    },
    replace(url: string) {
      entries[i] = { url, state: null };
    },
    get url() {
      return entries[i].url;
    },
  };
  return h;
}

test("collection → details → Back returns to the collection at its scroll, and the root knows it is the root", () => {
  const h = fakeHistory();
  const nav = createNavStack(h);
  nav.init();
  assert.equal(nav.canGoBack(), false);

  h.push("#/movies");
  assert.equal(nav.onHashChange(), null);
  h.push("#/collection/645");
  nav.onHashChange();
  nav.save(1240, "Goldfinger");
  h.push("#/item/abc");
  assert.equal(nav.onHashChange(), null);
  assert.equal(nav.depth(), 3);

  nav.back();
  const back = nav.onHashChange();
  assert.equal(h.url, "#/collection/645");
  assert.deepEqual(back, { depth: 2, scrollY: 1240, focus: "Goldfinger" });

  nav.back();
  nav.onHashChange();
  nav.back();
  nav.onHashChange();
  assert.equal(h.url, "#/home");
  assert.equal(nav.canGoBack(), false);
});

test("a replaced entry keeps the depth (search typing, episode prev/next)", () => {
  const h = fakeHistory();
  const nav = createNavStack(h);
  nav.init();
  h.push("#/search");
  nav.onHashChange();
  for (const q of ["a", "al", "ali"]) {
    nav.markReplace();
    h.replace(`#/search?q=${q}`);
    nav.onHashChange();
  }
  assert.equal(nav.depth(), 1);
  h.push("#/item/x");
  nav.onHashChange();
  nav.back();
  nav.onHashChange();
  assert.equal(h.url, "#/search?q=ali");
});

test("a deep link opens at depth 0: Back has nowhere in-app to go", () => {
  const h = fakeHistory();
  h.replace("#/item/deep");
  const nav = createNavStack(h);
  nav.init();
  assert.equal(nav.canGoBack(), false);
});
