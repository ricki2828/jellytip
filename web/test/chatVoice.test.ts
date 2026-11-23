import { test } from "node:test";
import assert from "node:assert/strict";
import { afterEnd, errorText, heardNothing, isQuietError, isTap, joinSpeech, MAX_RESTARTS, sessionText, TAP_MS } from "../src/lib/chatVoice.ts";

const r = (...ts: string[]) => ts.map((transcript) => [{ transcript }]);

test("joinSpeech: one space, whitespace collapsed, empty sides add nothing", () => {
  assert.equal(joinSpeech("something", "funny"), "something funny");
  assert.equal(joinSpeech("  a ", "  b  "), "a b");
  assert.equal(joinSpeech("kept", ""), "kept");
  assert.equal(joinSpeech("", "heard"), "heard");
  assert.equal(joinSpeech("", "  "), "");
  assert.equal(joinSpeech("typed", "then\n spoken"), "typed then spoken");
});

test("sessionText: final and interim results joined in order", () => {
  assert.equal(sessionText(r("something funny", " for the kids")), "something funny for the kids");
  assert.equal(sessionText([]), "");
  assert.equal(sessionText([[], [{ transcript: "x" }]]), "x");
});

test("a pause while held restarts and appends; release ends it", () => {
  let committed = "";
  committed = joinSpeech(committed, sessionText(r("something funny")));
  assert.equal(afterEnd(true, false, 0), "restart");
  committed = joinSpeech(committed, sessionText(r("for the kids")));
  assert.equal(afterEnd(false, false, 1), "finish");
  assert.equal(committed, "something funny for the kids");
});

test("tap mode and the restart cap end the utterance", () => {
  assert.equal(afterEnd(false, true, 0), "finish");
  assert.equal(afterEnd(true, true, 0), "finish");
  assert.equal(afterEnd(true, false, MAX_RESTARTS), "finish");
  assert.equal(afterEnd(true, false, MAX_RESTARTS - 1), "restart");
});

test("tap threshold, quiet errors, error text", () => {
  assert.ok(isTap(0) && isTap(TAP_MS - 1));
  assert.ok(!isTap(TAP_MS) && !isTap(2500));
  assert.ok(isQuietError("no-speech") && isQuietError("aborted"));
  assert.ok(!isQuietError("not-allowed") && !isQuietError("network"));
  assert.match(errorText("not-allowed"), /blocked/);
  assert.match(errorText("weird"), /type it/);
});

test("a silent press never re-sends typed text; new speech (even the same words) counts", () => {
  assert.ok(heardNothing("under 90 minutes", "under 90 minutes"));
  assert.ok(heardNothing("  under  90 minutes ", "under 90 minutes"));
  assert.ok(heardNothing("", "") && heardNothing("", "   "));
  assert.ok(!heardNothing("under 90 minutes", "under 90 minutes with Paul Rudd"));
  assert.ok(!heardNothing("", "something funny"));
  assert.ok(!heardNothing("funny", joinSpeech("funny", "funny")));
});

test("error text: the phone points at Android settings; permission prompt and leaving the app have their own words", () => {
  assert.match(errorText("not-allowed"), /browser/);
  assert.match(errorText("not-allowed", true), /Android settings/);
  assert.doesNotMatch(errorText("not-allowed", true), /Jellytip/); // the refusal may be the speech service's, not ours
  assert.doesNotMatch(errorText("not-allowed", true), /browser/);
  assert.match(errorText("mic-allowed", true), /hold the mic/);
  assert.match(errorText("mic-prompt", true), /Allow the microphone/);
  assert.match(errorText("interrupted", true), /app was left/);
  assert.match(errorText("client", true), /type it/);
  assert.ok(!isQuietError("mic-allowed") && !isQuietError("mic-prompt") && !isQuietError("interrupted"));
});
