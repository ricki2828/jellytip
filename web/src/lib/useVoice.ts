// Press-to-talk for a text box, on either speech engine (lib/speechEngine.ts). Self-contained so it can move with the input it
// serves. The state machine is lib/voiceController.ts (tested); the rules are chatVoice's, the same as the TV app (ChatVoice.kt).
import { useEffect, useMemo, useRef, useState } from "react";
import { debugEvent } from "./debugLog";
import { NativeSpeech, isNative } from "./native";
import { nativeEngine, webEngine, webSpeechCtor, type SpeechEngine } from "./speechEngine";
import { VoiceController } from "./voiceController";

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
  const ctl = useMemo(
    () =>
      new VoiceController(
        engine,
        {
          getText: () => o.current.getText(),
          setText: (t) => o.current.setText(t),
          onSend: (t) => o.current.onSend(t),
          setListening,
          setError,
          log,
        },
        { lang: engine.kind === "native" ? undefined : (typeof navigator !== "undefined" && navigator.language) || "en-US" },
      ),
    [engine],
  );

  useEffect(() => {
    let alive = true;
    void engine.available().then((ok) => alive && setAvailable(ok));
    const detach = ctl.attach(); // detaching also stops a window that is still listening (leaving the screen)
    return () => {
      alive = false;
      detach();
    };
  }, [engine, ctl]);

  // the box went busy while a press was live (the mic is disabled): drop it, never leave it "held"
  useEffect(() => {
    if (opts.disabled && ctl.listening) ctl.cancel();
  }, [opts.disabled, ctl]);

  const down = () => {
    if (!available || o.current.disabled) return;
    ctl.down();
  };
  return { available, phone: ctl.phone, listening, error, setError, down, up: () => ctl.up(), cancel: () => ctl.cancel() };
}
