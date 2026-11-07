import { test } from "node:test";
import assert from "node:assert/strict";
import { filterHub, inTab, parseHub } from "../src/lib/collections.ts";

const hub = parseHub({
  sections: [
    { key: "franchises", title: "Franchises", items: [
      { id: "a", title: "A", mediaType: "movie" },
      { id: "b", title: "B", mediaType: "tv" },
      { id: "c", title: "C" },
    ] },
    { key: "shows", title: "Shows", items: [{ id: "d", title: "D", mediaType: "tv" }] },
  ],
});

test("inTab: movie/tv exclusive, unknown on both, all everything", () => {
  assert.equal(inTab({ mediaType: "movie" }, "tv"), false);
  assert.equal(inTab({ mediaType: "tv" }, "tv"), true);
  assert.equal(inTab({ mediaType: null }, "movie"), true);
  assert.equal(inTab({ mediaType: null }, "tv"), true);
  assert.equal(inTab({ mediaType: "tv" }, "all"), true);
});

test("filterHub: filters cards, recounts, drops empty sections", () => {
  const m = filterHub(hub, "movie");
  assert.deepEqual(m.map((s) => [s.key, s.count]), [["franchises", 2]]);
  const t = filterHub(hub, "tv");
  assert.deepEqual(t.map((s) => [s.key, s.items.map((i) => i.id)]), [["franchises", ["b", "c"]], ["shows", ["d"]]]);
  assert.equal(filterHub(hub, "all"), hub);
  assert.deepEqual(filterHub([], "tv"), []);
});
