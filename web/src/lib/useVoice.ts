// Press-to-talk for a text box, on either speech engine (lib/speechEngine.ts). Self-contained so it can move with the input it
// serves. The rules are chatVoice's (pure, tested): hold = keep listening across pauses until release (a window that ends while
// held is restarted, its words kept); a press shorter than TAP_MS = listen until a pause (TAP_SILENCE_MS after the last words)
// or a second tap; a silent press never re-sends what was typed. The same rules as the TV app (ChatVoice.kt).
import { useEffect, useMemo, useRef, useState } from "react";
import { afterEnd, errorText, heardNothing, isQuietError, isTap, joinSpeech, TAP_SILENCE_MS } from "./chatVoice";
import { debugEvent } from "./debugLog";
import { NativeSpeech, isNative } from "./native";
import { nativeEngine, webEngine, webSpeechCtor, type SpeechEngine } from "./speechEngine";

let shared: SpeechEngine | null = null;
/** One engine for the app (the native one attaches its plugin listeners once). */
export function speechEngine(): SpeechEngine {
  if (!shared) shared = isNative ? nativeEngine(NativeSpeech) : webEngine(typeof window === "undefined" ? null : webSpeechCtor(window));
  return shared;
}

export interface VoiceOptions {
  /** What is in the box now (speech is appended to it). */
  getText: () => string;
  /** The live text: what was there + everything heard so far. */
  setText: (t: string) => void;
  /** The utterance is complete: send it (never blank, never just the typed text). */
  onSend: (t: string) => void;
  /** The mic can't be used now (e.g. a turn is pending): a live press is dropped. */
  disabled: boolean;
}

export interface Voice {
  available: boolean;
  /** The phone app (Android's recognizer), for wording. */
  phone: boolean;
  listening: boolean;
  error: string | null;
  setError: (e: string | null) => void;
  /** Mic pressed (pointer down, Space / Enter down). */
  down: () => void;
  /** Mic released (or the press was lost: pointer cancel, blur). */
  up: () => void;
  /** Drop everything (Start over, leaving the screen): nothing is sent. */
  cancel: () => void;
}

const log = (msg: string, data: Record<string, unknown> = {}) => debugEvent("voice", msg, data, "info");

export function useVoice(opts: VoiceOptions): Voice {
  const engine = useMemo(speechEngine, []);
  const o = useRef(opts);
  o.current = opts;
  const [available, setAvailable] = useState(false);
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // refs: the engine's callbacks must see the current values
  const v = useRef({ held: false, tap: false, active: false, committed: "", live: "", restarts: 0, downAt: 0, base: "", quiet: null as ReturnType<typeof setTimeout> | null });
  const phone = engine.kind === "native";

  const stopVoice = () => {
    const s = v.current;
    s.active = s.held = s.tap = false;
    if (s.quiet) clearTimeout(s.quiet);
    s.quiet = null;
    engine.cancel(); // nothing from a stopped window reaches the box
    setListening(false);
  };
  // the utterance is over: send what was heard (never blank), or say nothing was heard
  const finish = (send: boolean) => {
    const s = v.current;
    const text = s.live;
    log("finish", { chars: text.length, restarts: s.restarts, send });
    stopVoice();
    if (!send) return;
    if (heardNothing(s.base, text)) return setError("Didn't catch that"); // typed text stays in the box, nothing is sent
    o.current.setText(text);
    o.current.onSend(text);
  };
  const listen = () => {
    const s = v.current;
    log("window start", { n: s.restarts + 1, hold: s.held && !s.tap });
    engine.start({ lang: phone ? undefined : navigator.language || "en-US", hold: s.held && !s.tap });
  };
  // tap mode: a pause of TAP_SILENCE_MS after the last words ends it (stop() lets the final words arrive, then end sends)
  const armQuiet = () => {
    const s = v.current;
    if (s.quiet) clearTimeout(s.quiet);
    s.quiet = setTimeout(() => {
      engine.stop();
      s.quiet = setTimeout(() => finish(true), 3000); // end never came: send what we have
    }, TAP_SILENCE_MS);
  };

  useEffect(() => {
    let alive = true;
    void engine.available().then((ok) => alive && setAvailable(ok));
    const heard = ({ text }: { text: string }) => {
      const s = v.current;
      if (!s.active) return;
      s.live = joinSpeech(s.committed, text);
      o.current.setText(s.live);
      if (s.tap) armQuiet();
    };
    const offs = [
      engine.on("partial", heard),
      engine.on("final", heard),
      engine.on("error", ({ code }) => {
        const s = v.current;
        if (!s.active) return;
        log("window error", { code, n: s.restarts + 1 });
        if (isQuietError(code)) return; // end follows
        const text = s.live;
        stopVoice();
        if (text.trim()) o.current.setText(text);
        setError(errorText(code, phone));
      }),
      engine.on("end", () => {
        const s = v.current;
        if (!s.active) return;
        s.committed = s.live;
        if (afterEnd(s.held, s.tap, s.restarts) === "restart") {
          s.restarts++;
          log("restart", { n: s.restarts });
          listen();
        } else {
          finish(true);
        }
      }),
    ];
    return () => {
      alive = false;
      offs.forEach((off) => off());
      stopVoice(); // leaving the screen stops a window that is still listening
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // the box went busy while a press was live (the mic is disabled): drop it, never leave it "held"
  useEffect(() => {
    if (opts.disabled && v.current.active) stopVoice();
  }, [opts.disabled]); // eslint-disable-line react-hooks/exhaustive-deps

  const down = () => {
    const s = v.current;
    if (!available || o.current.disabled) return;
    if (s.active) {
      if (s.tap) engine.stop(); // a second tap while a tap-listen runs: send now
      return;
    }
    setError(null);
    const t = o.current.getText();
    Object.assign(s, { active: true, held: true, tap: false, restarts: 0, downAt: Date.now(), committed: t, live: t, base: t });
    setListening(true);
    log("press", { engine: engine.kind });
    listen();
  };
  const up = () => {
    const s = v.current;
    if (!s.active || !s.held) return;
    s.held = false;
    if (isTap(Date.now() - s.downAt)) {
      s.tap = true;
      armQuiet();
    } else {
      engine.stop(); // the final words arrive, then end sends
      if (s.quiet) clearTimeout(s.quiet);
      s.quiet = setTimeout(() => finish(true), 3000); // an engine that never answers stop()
    }
  };

  return { available, phone, listening, error, setError, down, up, cancel: stopVoice };
}
