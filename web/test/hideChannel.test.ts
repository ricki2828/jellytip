import { test } from "node:test";
import assert from "node:assert/strict";
import { hideTarget, neighbourOf, restoreHidden } from "../src/lib/hideChannel.ts";

const ch = (id: string, group = "pp000001:7", providerId: string | number = "pp000001") => ({ id, providerId, group });

test("owner only, ids split", () => {
  assert.deepEqual(hideTarget(ch("pp000001:123"), new Set(["pp000001"])), { pid: "pp000001", cat: "7", sid: "123" });
  assert.equal(hideTarget(ch("pp000001:123"), new Set(["pp000002"])), null); // shared provider
  assert.equal(hideTarget(ch("pp000001:123"), null), null); // not loaded
  assert.equal(hideTarget(ch("pp000001:123", "fav"), new Set(["pp000001"])), null);
  assert.equal(hideTarget(ch("pp000002:123"), new Set(["pp000001"])), null); // id from another provider
  assert.deepEqual(hideTarget(ch("pp000001:a:b"), new Set(["pp000001"])), { pid: "pp000001", cat: "7", sid: "a:b" });
  assert.deepEqual(hideTarget(ch("5:9", "5:2", 5), new Set(["5"])), { pid: "5", cat: "2", sid: "9" }); // numeric provider id
});

test("undo puts it back where it was", () => {
  const a = ch("pp000001:a");
  const b = ch("pp000001:b");
  const c = ch("pp000001:c");
  assert.deepEqual(restoreHidden([a, c], { ch: b, index: 1, group: "g" }), [a, b, c]);
  assert.deepEqual(restoreHidden([a, c], { ch: b, index: 9, group: "g" }), [a, c, b]);
  assert.deepEqual(restoreHidden([a, b], { ch: b, index: 0, group: null }), [a, b]);
});

test("neighbour: next row, else the one above", () => {
  assert.equal(neighbourOf(["a", "b", "c"], "b"), "c");
  assert.equal(neighbourOf(["a", "b"], "b"), "a");
  assert.equal(neighbourOf(["b"], "b"), null);
  assert.equal(neighbourOf(["a"], "x"), null);
});
