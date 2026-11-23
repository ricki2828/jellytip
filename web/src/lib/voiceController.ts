// The press-to-talk state machine over a speech engine (lib/speechEngine.ts), without React: lib/useVoice.ts wraps it, and
// test/voiceController.test.ts drives it with a fake engine and fake timers. The decisions are chatVoice's (pure, tested):
// - hold = keep listening across pauses until release (a window that ends while held is restarted, its words kept);
// - a press shorter than TAP_MS = listen until a pause (TAP_SILENCE_MS after the last words) or a second tap;
// - a silent press never re-sends what was typed;
// - once we asked the engine to stop (release, tap pause, second tap), a failure is the end of the utterance: what was heard is
//   sent rather than dropped behind an error.
import { afterEnd, errorText, heardNothing, isQuietError, isTap, joinSpeech, TAP_SILENCE_MS } from "./chatVoice.ts";
import type { SpeechEngine } from "./speechEngine.ts";

/** An engine that never answers stop(): send what we have after this long. */
export const STOP_WAIT_MS = 3000;

export interface VoiceHost {
  getText(): string;
  setText(t: string): void;
  onSend(t: string): void;
  setListening(on: boolean): void;
  setError(e: string | null): void;
  log(msg: string, data?: Record<string, unknown>): void;
}

export interface VoiceTimers {
  set(fn: () => void, ms: number): unknown;
  clear(h: unknown): void;
}

const realTimers: VoiceTimers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) };

export class VoiceController {
  private held = false;
  private tap = false;
  private active = false;
  /** We asked the engine to finish (release / tap pause / second tap): the next end, or a failure, ends the utterance. */
  private stopping = false;
  private committed = "";
  private live = "";
  private base = "";
  private restarts = 0;
  private downAt = 0;
  private quiet: unknown = null;
  /** One per press: a timer of an earlier press (or of this one after it ended) does nothing. */
  private press = 0;
  readonly phone: boolean;
  private engine: SpeechEngine;
  private host: VoiceHost;
  private opts: { lang?: string; now?: () => number; timers?: VoiceTimers };

  constructor(engine: SpeechEngine, host: VoiceHost, opts: { lang?: string; now?: () => number; timers?: VoiceTimers } = {}) {
    this.engine = engine;
    this.host = host;
    this.opts = opts;
    this.phone = engine.kind === "native";
  }

  private get timers() {
    return this.opts.timers ?? realTimers;
  }
  private now() {
    return (this.opts.now ?? Date.now)();
  }
  private clearQuiet() {
    if (this.quiet !== null) this.timers.clear(this.quiet);
    this.quiet = null;
  }

  get listening() {
    return this.active;
  }

  /** Subscribe to the engine; returns the unsubscribe (which also stops a live press). */
  attach(): () => void {
    const heard = ({ text }: { text: string }) => {
      if (!this.active) return;
      this.live = joinSpeech(this.committed, text);
      this.host.setText(this.live);
      if (this.tap && !this.stopping) this.armQuiet();
    };
    const offs = [
      this.engine.on("partial", heard),
      this.engine.on("final", heard),
      this.engine.on("error", ({ code }) => this.onError(code)),
      this.engine.on("end", () => this.onEnd()),
    ];
    return () => {
      offs.forEach((off) => off());
      this.cancel();
    };
  }

  private onError(code: string) {
    if (!this.active) {
      // the phone's permission answer arrives after the press it ended (speechEngine.nativeEngine)
      if (code === "mic-allowed" || code === "not-allowed") this.host.setError(errorText(code, this.phone));
      return;
    }
    this.host.log("window error", { code, n: this.restarts + 1, stopping: this.stopping });
    if (isQuietError(code)) return; // end follows
    if (this.stopping && !heardNothing(this.base, this.live)) return this.finish(true); // the words were heard: send them
    const text = this.live;
    this.cancel();
    if (text.trim()) this.host.setText(text);
    this.host.setError(errorText(code, this.phone));
  }

  private onEnd() {
    if (!this.active) return;
    this.committed = this.live;
    if (!this.stopping && afterEnd(this.held, this.tap, this.restarts) === "restart") {
      this.restarts++;
      this.host.log("restart", { n: this.restarts });
      this.listen();
    } else {
      this.finish(true);
    }
  }

  /** Stop the press, send nothing (Start over, leaving the screen, the box went busy). */
  cancel() {
    const was = this.active;
    this.active = this.held = this.tap = this.stopping = false;
    this.clearQuiet();
    this.engine.cancel(); // nothing from a stopped window reaches the box
    if (was) this.host.setListening(false);
  }

  // the utterance is over: send what was heard (never blank), or say nothing was heard
  private finish(send: boolean) {
    const text = this.live;
    this.host.log("finish", { chars: text.length, restarts: this.restarts, send });
    this.cancel();
    if (!send) return;
    if (heardNothing(this.base, text)) return this.host.setError("Didn't catch that"); // typed text stays, nothing is sent
    this.host.setText(text);
    this.host.onSend(text);
  }

  private listen() {
    this.host.log("window start", { n: this.restarts + 1 });
    this.engine.start({ lang: this.opts.lang });
  }

  /** Ask the engine for the last words, then end; send anyway if it never answers. */
  private requestStop() {
    this.stopping = true;
    this.clearQuiet();
    // the fallback first: an engine may end the window inside stop() (nothing was listening yet), and that finish must clear it
    this.quiet = this.later(() => this.finish(true), STOP_WAIT_MS);
    this.engine.stop();
  }

  /** A timer that only fires while the press that set it is still live. */
  private later(fn: () => void, ms: number): unknown {
    const press = this.press;
    return this.timers.set(() => {
      if (this.active && this.press === press) fn();
    }, ms);
  }

  // tap mode: a pause of TAP_SILENCE_MS after the last words ends it
  private armQuiet() {
    this.clearQuiet();
    this.quiet = this.later(() => this.requestStop(), TAP_SILENCE_MS);
  }

  /** Mic pressed (pointer down, Space / Enter down). */
  down() {
    if (this.active) {
      if (this.tap && !this.stopping) this.requestStop(); // a second tap while a tap-listen runs: send now
      return;
    }
    this.host.setError(null);
    this.press++;
    const t = this.host.getText();
    this.active = this.held = true;
    this.tap = this.stopping = false;
    this.restarts = 0;
    this.downAt = this.now();
    this.committed = this.live = this.base = t;
    this.host.setListening(true);
    this.host.log("press", { engine: this.engine.kind });
    this.listen();
  }

  /** Mic released (or the press was lost: pointer cancel, blur). */
  up() {
    if (!this.active || !this.held) return;
    this.held = false;
    if (isTap(this.now() - this.downAt)) {
      this.tap = true;
      this.armQuiet();
    } else {
      this.requestStop(); // the final words arrive, then end sends
    }
  }
}
