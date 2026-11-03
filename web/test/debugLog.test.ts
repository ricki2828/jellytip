import { test } from "node:test";
import assert from "node:assert/strict";
import { clean } from "../src/lib/debugPrivacy.ts";

test("debug data redacts secrets in nested diagnostics", () => {
  const result = clean({
    streamUrl: "https://host/s/secret",
    nested: { authorization: "Bearer secret", events: ["failed at /v1/iptv/s/signed-value", { token: "signed-value" }] },
    elapsedMs: 1200,
  });
  const serialized = JSON.stringify(result);
  assert.equal(result.elapsedMs, 1200);
  assert.doesNotMatch(serialized, /secret|signed-value|Bearer/);
  assert.match(serialized, /redacted/);
});
