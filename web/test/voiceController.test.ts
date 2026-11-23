import { test } from "node:test";
import assert from "node:assert/strict";
import { STOP_WAIT_MS, VoiceController, type VoiceTimers } from "../src/lib/voiceController.ts";
import { MAX_RESTARTS, TAP_MS, TAP_SILENCE_MS } from "../src/lib/chatVoice.ts";
import { VoiceEmitter, type SpeechEngine, type VoiceEventMap, type VoiceEventName } from "../src/lib/speechEngine.ts";

/** A fake engine: records calls; the test emits its events. */
function fakeEngine(kind: "web" | "native" = "native") {
  const em = new VoiceEmitter();
  const calls: string[] = [];
  const engine: SpeechEngine = {
    kind,
    available: () => Promise.resolve(true),
    start: () => void calls.push("start"),
    stop: () => void calls.push("stop"),
    cancel: () => void calls.push("cancel"),
    on: (e, cb) => em.on(e, cb),
  };
  const emit = <K extends VoiceEventName>(e: K, p: VoiceEventMap[K]) => em.emit(e, p);
  return { engine, calls, emit };
}

/** Manual clock + timers. */
function fakeTime() {
  let now = 0;
  let id = 0;
  const due = new Map<number, { at: number; fn: () => void }>();
  const timers: VoiceTimers = {
    set: (fn, ms) => {
      due.set(++id, { at: now + ms, fn });
      return id;
    },
    clear: (h) => void due.delete(h as number),
  };
  const advance = (ms: number) => {
    const end = now + ms;
    for (;;) {
      const next = [...due.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      due.delete(next[0]);
      now = next[1].at;
      next[1].fn();
    }
    now = end;
  };
  return { timers, now: () => now, advance };
}

function setup(typed = "", kind: "web" | "native" = "native") {
  const { engine, calls, emit } = fakeEngine(kind);
  const t = fakeTime();
  const out = { text: typed, sent: [] as string[], error: null as string | null, listening: false };
  const ctl = new VoiceController(
    engine,
    {
      getText: () => out.text,
      setText: (x) => (out.text = x),
      onSend: (x) => out.sent.push(x),
      setListening: (on) => (out.listening = on),
      setError: (e) => (out.error = e),
      log: () => {},
    },
    { now: t.now, timers: t.timers },
  );
  const detach = ctl.attach();
  return { ctl, calls, emit, out, ...t, detach };
}

test("hold: pauses restart the window, words accumulate, release sends", () => {
  const { ctl, calls, emit, out, advance } = setup();
  ctl.down();
  emit("partial", { text: "something" });
  emit("final", { text: "something funny" });
  emit("end", {});
  assert.deepEqual(calls, ["start", "start"]); // restarted while held
  emit("partial", { text: "for the kids" });
  assert.equal(out.text, "something funny for the kids");
  advance(2000);
  ctl.up();
  assert.deepEqual(calls.slice(2), ["stop"]);
  emit("final", { text: "for the kids" });
  emit("end", {});
  assert.deepEqual(out.sent, ["something funny for the kids"]);
  assert.equal(out.listening, false);
});

test("a failure after the stop we asked for still sends what was heard (P2a)", () => {
  const { ctl, emit, out, advance } = setup();
  ctl.down();
  emit("partial", { text: "under 90 minutes" });
  advance(1500);
  ctl.up(); // stop requested
  emit("error", { code: "client" }); // e.g. a recognizer that answers stopListening with ERROR_CLIENT
  assert.deepEqual(out.sent, ["under 90 minutes"]);
  assert.equal(out.error, null);
});

test("a failure after the stop with nothing heard is still an error; mid-hold failures keep the words unsent", () => {
  {
    const { ctl, emit, out, advance } = setup("typed");
    ctl.down();
    advance(1500);
    ctl.up();
    emit("error", { code: "client" });
    assert.deepEqual(out.sent, []);
    assert.match(out.error ?? "", /type it/);
    assert.equal(out.text, "typed");
  }
  {
    const { ctl, emit, out } = setup();
    ctl.down();
    emit("partial", { text: "half a" });
    emit("error", { code: "network" }); // held, not stopping: a real failure
    assert.deepEqual(out.sent, []);
    assert.equal(out.text, "half a");
    assert.match(out.error ?? "", /network/);
  }
});

test("quiet errors mid-hold restart; the restart cap ends the hold", () => {
  const { ctl, calls, emit, out } = setup();
  ctl.down();
  emit("partial", { text: "hi" });
  for (let i = 0; i < MAX_RESTARTS; i++) {
    emit("error", { code: "no-speech" });
    emit("end", {});
  }
  assert.equal(calls.filter((c) => c === "start").length, MAX_RESTARTS + 1);
  emit("end", {});
  assert.deepEqual(out.sent, ["hi"]);
});

test("tap: listens until TAP_SILENCE_MS after the last words, then stops and sends", () => {
  const { ctl, calls, emit, out, advance } = setup();
  ctl.down();
  advance(TAP_MS - 50);
  ctl.up(); // a tap
  emit("partial", { text: "something" });
  advance(TAP_SILENCE_MS - 100);
  emit("partial", { text: "something funny" }); // re-arms the quiet timer
  advance(TAP_SILENCE_MS - 100);
  assert.ok(!calls.includes("stop"));
  advance(200);
  assert.deepEqual(calls, ["start", "stop"]);
  emit("partial", { text: "something funny" }); // a late word after the stop must not re-arm (and lose the fallback)
  emit("end", {});
  assert.deepEqual(out.sent, ["something funny"]);
});

test("tap: an engine that never answers stop() still sends after STOP_WAIT_MS", () => {
  const { ctl, emit, out, advance } = setup();
  ctl.down();
  ctl.up();
  emit("partial", { text: "hello" });
  advance(TAP_SILENCE_MS + STOP_WAIT_MS + 10);
  assert.deepEqual(out.sent, ["hello"]);
});

test("a second tap sends now; a silent press never re-sends typed text", () => {
  {
    const { ctl, calls, emit, out } = setup();
    ctl.down();
    ctl.up();
    emit("partial", { text: "now" });
    ctl.down();
    assert.deepEqual(calls, ["start", "stop"]);
    emit("end", {});
    assert.deepEqual(out.sent, ["now"]);
  }
  {
    const { ctl, emit, out, advance } = setup("under 90 minutes");
    ctl.down();
    advance(1000);
    ctl.up();
    emit("end", {});
    assert.deepEqual(out.sent, []);
    assert.equal(out.error, "Didn't catch that");
  }
});

test("phone permission: the prompt ends the press; its answer shows after; cancel/detach drop everything", () => {
  const { ctl, calls, emit, out, detach } = setup();
  ctl.down();
  emit("error", { code: "mic-prompt" });
  emit("end", {});
  assert.match(out.error ?? "", /Allow the microphone/);
  assert.equal(out.listening, false);
  emit("error", { code: "mic-allowed" });
  assert.match(out.error ?? "", /allowed/);
  ctl.down();
  emit("partial", { text: "x" });
  detach();
  assert.equal(calls.at(-1), "cancel");
  emit("end", {});
  assert.deepEqual(out.sent, []);
});

test("interrupted (the app was left) between windows ends a hold with the words kept, not sent", () => {
  const { ctl, emit, out } = setup();
  ctl.down();
  emit("final", { text: "nearly" });
  emit("end", {}); // restart beat
  emit("error", { code: "interrupted" });
  assert.equal(out.listening, false);
  assert.equal(out.text, "nearly");
  assert.deepEqual(out.sent, []);
  assert.match(out.error ?? "", /app was left/);
});
