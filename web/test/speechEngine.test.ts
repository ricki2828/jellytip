import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ANDROID_ERRORS as E, androidErrorCode, nativeEngine, permissionNotice, RECREATE_DELAY_MS, RESTART_DELAY_MS, restartDelay, startOutcome, VoiceEmitter, webEngine, webSpeechCtor,
  type JellytipSpeechPlugin, type NativeSpeechEvent, type SpeechEngine, type WebRecognition,
} from "../src/lib/speechEngine.ts";
import { isQuietError } from "../src/lib/chatVoice.ts";

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

/** Every event an engine sends, as "name:payload" strings. */
function record(engine: SpeechEngine): string[] {
  const out: string[] = [];
  engine.on("partial", (e) => out.push(`partial:${e.text}`));
  engine.on("final", (e) => out.push(`final:${e.text}`));
  engine.on("error", (e) => out.push(`error:${e.code}`));
  engine.on("end", () => out.push("end"));
  return out;
}

// ---- pure parts ----

test("androidErrorCode: Android errors as the browser codes chatVoice understands", () => {
  assert.equal(androidErrorCode(E.NO_MATCH), "no-speech");
  assert.equal(androidErrorCode(E.SPEECH_TIMEOUT), "no-speech");
  assert.equal(androidErrorCode(E.INSUFFICIENT_PERMISSIONS), "not-allowed");
  assert.equal(androidErrorCode(E.AUDIO), "audio-capture");
  for (const n of [E.NETWORK, E.NETWORK_TIMEOUT, E.SERVER]) assert.equal(androidErrorCode(n), "network");
  assert.equal(androidErrorCode(E.SERVER_DISCONNECTED), "client"); // the speech service went away: not a network problem
  assert.equal(androidErrorCode(E.RECOGNIZER_BUSY), "aborted");
  assert.equal(androidErrorCode(E.INTERRUPTED), "interrupted");
  assert.equal(androidErrorCode(E.CLIENT), "client");
  assert.equal(androidErrorCode(E.TOO_MANY_REQUESTS), "client");
  assert.equal(androidErrorCode(99), "client");
  // a glitch right after a restart is just the end of that window, whatever the error
  assert.equal(androidErrorCode(E.CLIENT, true), "aborted");
  assert.equal(androidErrorCode(E.SERVER, true), "aborted");
});

test("quiet vs failure: only the window-is-over codes keep a held press going", () => {
  const quiet = [E.NO_MATCH, E.SPEECH_TIMEOUT, E.RECOGNIZER_BUSY].map((n) => androidErrorCode(n));
  assert.ok(quiet.every(isQuietError));
  assert.ok(isQuietError(androidErrorCode(E.CLIENT, true)));
  for (const n of [E.INSUFFICIENT_PERMISSIONS, E.AUDIO, E.NETWORK, E.CLIENT, E.INTERRUPTED]) assert.ok(!isQuietError(androidErrorCode(n)), String(n));
});

test("startOutcome: listening, or no permission yet (the prompt ends the press)", () => {
  assert.equal(startOutcome({ started: true }), null);
  assert.equal(startOutcome({}), null);
  assert.equal(startOutcome(undefined), null);
  assert.equal(startOutcome({ started: false, permission: "needed" }), "mic-prompt");
  assert.equal(startOutcome({ started: false }), "mic-prompt");
  assert.equal(startOutcome({ started: false, permission: "denied" }), "not-allowed");
  assert.equal(permissionNotice(true), "mic-allowed");
  assert.equal(permissionNotice(false), "not-allowed");
});

test("restartDelay: the TV's pacing (first window at once, a beat on reuse, longer for a fresh recognizer)", () => {
  assert.equal(restartDelay(true, null), 0);
  assert.equal(restartDelay(true, "aborted"), 0);
  assert.equal(restartDelay(false, null), RESTART_DELAY_MS);
  assert.equal(restartDelay(false, "no-speech"), RESTART_DELAY_MS);
  assert.equal(restartDelay(false, "aborted"), RECREATE_DELAY_MS);
});

test("VoiceEmitter: typed listeners, unsubscribe", () => {
  const em = new VoiceEmitter();
  const got: string[] = [];
  const off = em.on("partial", (e) => got.push(e.text));
  em.emit("partial", { text: "a" });
  off();
  em.emit("partial", { text: "b" });
  assert.deepEqual(got, ["a"]);
});

test("webSpeechCtor: standard, webkit, none", () => {
  class A {}
  class B {}
  assert.equal(webSpeechCtor({ SpeechRecognition: A, webkitSpeechRecognition: B }), A);
  assert.equal(webSpeechCtor({ webkitSpeechRecognition: B }), B);
  assert.equal(webSpeechCtor({}), null);
  assert.equal(webSpeechCtor(null), null);
});

// ---- web engine over a fake SpeechRecognition ----

function fakeWeb() {
  const made: (WebRecognition & { started: boolean; stopped: boolean; aborted: boolean })[] = [];
  class Fake {
    lang = "";
    interimResults = false;
    continuous = false;
    onresult: WebRecognition["onresult"] = null;
    onend: WebRecognition["onend"] = null;
    onerror: WebRecognition["onerror"] = null;
    started = false;
    stopped = false;
    aborted = false;
    constructor() {
      made.push(this);
    }
    start() {
      this.started = true;
    }
    stop() {
      this.stopped = true;
    }
    abort() {
      this.aborted = true;
    }
  }
  return { Ctor: Fake, made };
}
const results = (...ts: string[]) => ({ results: ts.map((transcript) => [{ transcript }]) });

test("web engine: window text is the session's results so far; one end per start; error before end", async () => {
  const { Ctor, made } = fakeWeb();
  const eng = webEngine(Ctor);
  assert.equal(await eng.available(), true);
  const ev = record(eng);
  eng.start({ lang: "en-NZ" });
  const r = made[0]!;
  assert.ok(r.started && r.continuous && r.interimResults);
  assert.equal(r.lang, "en-NZ");
  r.onresult!(results("something"));
  r.onresult!(results("something", " funny"));
  r.onerror!({ error: "no-speech" });
  r.onend!();
  r.onend?.(); // a second onend from the browser is dropped
  assert.deepEqual(ev, ["partial:something", "partial:something funny", "error:no-speech", "end"]);
});

test("web engine: a restart is a new recognizer; cancel delivers nothing more", () => {
  const { Ctor, made } = fakeWeb();
  const eng = webEngine(Ctor);
  const ev = record(eng);
  eng.start({});
  made[0]!.onend!();
  eng.start({});
  assert.equal(made.length, 2);
  const second = made[1]!;
  eng.cancel();
  assert.ok(second.aborted);
  assert.equal(second.onresult, null); // handlers detached: a late result can't reach the hook
  assert.deepEqual(ev, ["end"]);
  eng.stop(); // nothing running: no throw, no events
  assert.deepEqual(ev, ["end"]);
});

test("web engine: stop asks the recognizer to finish (the last words, then end)", () => {
  const { Ctor, made } = fakeWeb();
  const eng = webEngine(Ctor);
  const ev = record(eng);
  eng.start({});
  eng.stop();
  assert.ok(made[0]!.stopped);
  made[0]!.onresult!(results("last words"));
  made[0]!.onend!();
  assert.deepEqual(ev, ["partial:last words", "end"]);
});

test("web engine without SpeechRecognition: unavailable, and a start fails cleanly", async () => {
  const eng = webEngine(null);
  assert.equal(await eng.available(), false);
  const ev = record(eng);
  eng.start({});
  assert.deepEqual(ev, ["error:audio-capture", "end"]);
});

// ---- native engine over a fake SpeechPlugin ----

function fakePlugin(
  startAnswer: () => Promise<{ started: boolean; permission?: "needed" | "denied" }> = () => Promise.resolve({ started: true }),
  granted = true,
) {
  const ls: Record<string, ((e: NativeSpeechEvent) => void)[]> = {};
  const calls: string[] = [];
  const plugin: JellytipSpeechPlugin = {
    available: () => Promise.resolve({ available: true }),
    start: (o) => {
      calls.push(`start:${o.session}`);
      return startAnswer();
    },
    requestMic: () => {
      calls.push("requestMic");
      return Promise.resolve({ granted });
    },
    stop: (o) => {
      calls.push(`stop:${o.session}`);
      return Promise.resolve();
    },
    cancel: () => {
      calls.push("cancel");
      return Promise.resolve();
    },
    addListener: (event, cb) => {
      (ls[event] ??= []).push(cb);
      return Promise.resolve({ remove: () => Promise.resolve() });
    },
  };
  const send = (event: string, e: NativeSpeechEvent) => ls[event]?.forEach((cb) => cb(e));
  return { plugin, calls, send };
}

test("native engine: restarts keep the press's session; events of other sessions are dropped", async () => {
  const { plugin, calls, send } = fakePlugin();
  const eng = nativeEngine(plugin);
  assert.equal(await eng.available(), true);
  const ev = record(eng);
  eng.start({});
  await tick();
  send("partial", { session: 1, text: "something" });
  send("final", { session: 1, text: "something funny" });
  send("end", { session: 1 });
  eng.start({}); // restart while held: same session (the plugin reuses its recognizer), after a beat
  await tick();
  assert.deepEqual(calls, ["start:1"]);
  await tick(RESTART_DELAY_MS + 20);
  send("partial", { session: 9, text: "stale" });
  send("error", { session: 1, error: E.NO_MATCH });
  send("end", { session: 1 });
  assert.deepEqual(calls, ["start:1", "start:1"]);
  assert.deepEqual(ev, ["partial:something", "final:something funny", "end", "error:no-speech", "end"]);
});

test("native engine: cancel ends the session; the next press is a new one and old events can't reach it", async () => {
  const { plugin, calls, send } = fakePlugin();
  const eng = nativeEngine(plugin);
  const ev = record(eng);
  eng.start({});
  await tick();
  eng.cancel();
  send("end", { session: 1 }); // a late end of the cancelled press
  eng.start({});
  await tick();
  send("end", { session: 1 });
  send("final", { session: 2, text: "hi" });
  send("end", { session: 2 });
  eng.stop();
  assert.deepEqual(calls, ["start:1", "cancel", "start:2", "stop:2"]);
  assert.deepEqual(ev, ["final:hi", "end"]);
});

test("native engine: a cancel before the start went out sends no start", async () => {
  const { plugin, calls } = fakePlugin();
  const eng = nativeEngine(plugin);
  eng.start({});
  eng.cancel();
  await tick();
  assert.deepEqual(calls, ["cancel"]);
});

test("native engine: no permission ends the press at once, then asks; the answer comes as a notice after the press", async () => {
  for (const [granted, notice] of [[true, "mic-allowed"], [false, "not-allowed"]] as const) {
    const { plugin, calls } = fakePlugin(() => Promise.resolve({ started: false, permission: "needed" }), granted);
    const eng = nativeEngine(plugin);
    const ev = record(eng);
    eng.start({});
    await tick();
    eng.cancel(); // the hook ends the press on the error
    await tick();
    assert.deepEqual(ev, ["error:mic-prompt", "end", `error:${notice}`]);
    assert.deepEqual(calls, ["start:1", "requestMic", "cancel"]);
  }
});

test("native engine: a release during the restart beat sends no start for the dropped window", async () => {
  const { plugin, calls, send } = fakePlugin();
  const eng = nativeEngine(plugin);
  eng.start({});
  await tick();
  send("end", { session: 1 });
  eng.start({}); // restart, delayed
  eng.cancel(); // the press finished before the beat ran out
  await tick(RESTART_DELAY_MS + 20);
  assert.deepEqual(calls, ["start:1", "cancel"]);
});

test("native engine: after a glitch / busy end the restart waits longer (a fresh recognizer)", async () => {
  const { plugin, calls, send } = fakePlugin();
  const eng = nativeEngine(plugin);
  eng.start({});
  await tick();
  send("error", { session: 1, error: E.RECOGNIZER_BUSY });
  send("end", { session: 1 });
  eng.start({});
  await tick(RESTART_DELAY_MS + 20);
  assert.deepEqual(calls, ["start:1"]);
  await tick(RECREATE_DELAY_MS);
  assert.deepEqual(calls, ["start:1", "start:1"]);
});

test("native engine: a rejected start fails the press; a glitch error is quiet", async () => {
  const { plugin, send } = fakePlugin(() => Promise.reject(new Error("bridge")));
  const eng = nativeEngine(plugin);
  const ev = record(eng);
  eng.start({});
  await tick();
  assert.deepEqual(ev, ["error:start-failed", "end"]);
  const p2 = fakePlugin();
  const eng2 = nativeEngine(p2.plugin);
  const ev2 = record(eng2);
  eng2.start({});
  await tick();
  p2.send("error", { session: 1, error: E.CLIENT, glitch: true });
  p2.send("end", { session: 1 });
  assert.deepEqual(ev2, ["error:aborted", "end"]);
});

test("native engine: a release while the restart is still waiting ends the window at once, and no start goes out", async () => {
  const { plugin, calls, send } = fakePlugin();
  const eng = nativeEngine(plugin);
  const ev = record(eng);
  eng.start({});
  await tick();
  send("end", { session: 1 });
  eng.start({}); // restart beat
  eng.stop();
  assert.deepEqual(ev, ["end", "end"]);
  await tick(RESTART_DELAY_MS + 20);
  assert.deepEqual(calls, ["start:1"]);
});

test("native engine: a failed stop is labelled stop-failed", async () => {
  const p = fakePlugin();
  p.plugin.stop = () => Promise.reject(new Error("bridge"));
  const eng = nativeEngine(p.plugin);
  const ev = record(eng);
  eng.start({});
  await tick();
  eng.stop();
  await tick();
  assert.deepEqual(ev, ["error:stop-failed", "end"]);
});
