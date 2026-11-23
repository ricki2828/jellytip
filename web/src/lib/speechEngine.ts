// One speech engine interface for the chat mic, so the browser and the phone app share the press-to-talk rules (lib/chatVoice.ts,
// wired in lib/useVoice.ts):
// - web: the browser's SpeechRecognition (a new recognizer per window, continuous + interim results).
// - native: the phone app's SpeechPlugin.java around Android's SpeechRecognizer (the Android WebView has no SpeechRecognition).
//   The plugin reuses ONE recognizer across the windows of a press (a destroy / create per window lost words on the TV).
//
// The contract, the same for both engines:
// - `partial` / `final` carry THIS window's text so far (each replaces the previous one; the hook appends it to what earlier
//   windows heard). Browser results are already cumulative per window; Android's partial and final results replace each other.
// - every start() ends in exactly one `end`; an `error` always comes before its `end`. cancel() ends a window with no events.
// - error codes are the browser's (no-speech, aborted, not-allowed, audio-capture, network, ...) plus the phone's "mic-prompt"
//   (no microphone permission yet: the press ends and the system prompt opens), "mic-allowed" / "not-allowed" (the prompt's
//   answer, sent after that press ended) and "interrupted" (the app was left while listening).
// Restarts are decided only by chatVoice.afterEnd (the hook calls start() again); the engines never restart by themselves. The
// native engine only paces them like the TV (RESTART_DELAY_MS, RECREATE_DELAY_MS after a glitch / busy recognizer).
// This file imports nothing from Capacitor (the plugin object is passed in), so it runs under node --test.
import { sessionText } from "./chatVoice.ts";

export type VoiceEventMap = { partial: { text: string }; final: { text: string }; end: Record<string, never>; error: { code: string } };
export type VoiceEventName = keyof VoiceEventMap;

export interface SpeechEngine {
  readonly kind: "web" | "native";
  /** The engine can listen on this device (native: asks the system, so it is async). */
  available(): Promise<boolean>;
  /** One listening window. `hold`: the mic is held (ask for long silences); a tap ends on the recognizer's own pause. */
  start(o: { lang?: string; hold: boolean }): void;
  /** Finish the window: the last words arrive (final), then end. */
  stop(): void;
  /** Drop the window: nothing more is delivered. */
  cancel(): void;
  on<K extends VoiceEventName>(event: K, cb: (e: VoiceEventMap[K]) => void): () => void;
}

/** A tiny typed emitter (the engines' `on`). */
export class VoiceEmitter {
  private cbs: { [K in VoiceEventName]: Set<(e: VoiceEventMap[K]) => void> } = { partial: new Set(), final: new Set(), end: new Set(), error: new Set() };
  on<K extends VoiceEventName>(event: K, cb: (e: VoiceEventMap[K]) => void): () => void {
    const set = this.cbs[event] as Set<(e: VoiceEventMap[K]) => void>;
    set.add(cb);
    return () => void set.delete(cb);
  }
  emit<K extends VoiceEventName>(event: K, e: VoiceEventMap[K]): void {
    for (const cb of [...(this.cbs[event] as Set<(e: VoiceEventMap[K]) => void>)]) cb(e);
  }
}

// ---- web: SpeechRecognition ----

export type WebRecognition = {
  abort(): void; stop(): void; start(): void; lang: string; interimResults: boolean; continuous: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null; onend: (() => void) | null; onerror: ((e: { error?: string }) => void) | null;
};
export type WebRecognitionCtor = new () => WebRecognition;

/** The browser's recognizer constructor, or null (none, or the phone app: its WebView has none that works). */
export function webSpeechCtor(w: unknown): WebRecognitionCtor | null {
  if (!w) return null;
  const x = w as { SpeechRecognition?: WebRecognitionCtor; webkitSpeechRecognition?: WebRecognitionCtor };
  return x.SpeechRecognition ?? x.webkitSpeechRecognition ?? null;
}

export function webEngine(Ctor: WebRecognitionCtor | null): SpeechEngine {
  const em = new VoiceEmitter();
  let cur: WebRecognition | null = null;
  const drop = () => {
    const r = cur;
    cur = null;
    if (r) r.onresult = r.onend = r.onerror = null; // nothing from a dropped recognizer reaches the hook
    return r;
  };
  return {
    kind: "web",
    available: () => Promise.resolve(!!Ctor),
    start({ lang, hold: _hold }) {
      // the browser has no silence settings: continuous listening, and the hook's quiet timer ends a tap
      const old = drop();
      try {
        old?.abort();
      } catch {
        /* already stopped */
      }
      if (!Ctor) {
        em.emit("error", { code: "audio-capture" });
        em.emit("end", {});
        return;
      }
      const r = new Ctor();
      cur = r;
      r.lang = lang || "en-US";
      r.continuous = true;
      r.interimResults = true;
      r.onresult = (e) => cur === r && em.emit("partial", { text: sessionText(e.results) });
      r.onerror = (e) => cur === r && em.emit("error", { code: e?.error ?? "" });
      r.onend = () => {
        if (cur !== r) return;
        drop();
        em.emit("end", {});
      };
      try {
        r.start();
      } catch {
        drop();
        em.emit("error", { code: "start-failed" });
        em.emit("end", {});
      }
    },
    stop() {
      try {
        cur?.stop(); // onresult (last words), then onend
      } catch {
        /* already stopped */
      }
    },
    cancel() {
      const r = drop();
      try {
        r?.abort();
      } catch {
        /* already stopped */
      }
    },
    on: (event, cb) => em.on(event, cb),
  };
}

// ---- native: the phone app's SpeechPlugin.java ----

/** Events as SpeechPlugin.java sends them: every one carries the session (press) it belongs to. */
export type NativeSpeechEvent = { session: number; text?: string; error?: number; glitch?: boolean };
type Handle = Promise<{ remove: () => Promise<void> }>;

/** android/app/src/main/java/com/jellytip/phone/SpeechPlugin.java ("JellytipSpeech"). */
export interface JellytipSpeechPlugin {
  available(): Promise<{ available: boolean }>;
  /**
   * One listening window for `session` (the same recognizer is reused while the session lasts). {started: false, permission:
   * "needed"} without the microphone permission: nothing is listening (ask with requestMic()).
   */
  start(o: { session: number; lang?: string; hold: boolean }): Promise<{ started: boolean; permission?: "needed" | "denied" }>;
  /** The system's microphone prompt (answers at once when it was refused for good). */
  requestMic(): Promise<{ granted: boolean }>;
  stop(o: { session: number }): Promise<void>;
  /** Ends the window with no events and releases the recognizer. */
  cancel(): Promise<void>;
  addListener(event: "partial" | "final" | "end" | "error", cb: (e: NativeSpeechEvent) => void): Handle;
}

// SpeechRecognizer.ERROR_* (android.speech.SpeechRecognizer); -1 = the plugin's own "the app was left while listening".
export const ANDROID_ERRORS = {
  NETWORK_TIMEOUT: 1, NETWORK: 2, AUDIO: 3, SERVER: 4, CLIENT: 5, SPEECH_TIMEOUT: 6, NO_MATCH: 7, RECOGNIZER_BUSY: 8,
  INSUFFICIENT_PERMISSIONS: 9, TOO_MANY_REQUESTS: 10, SERVER_DISCONNECTED: 11, LANGUAGE_NOT_SUPPORTED: 12, LANGUAGE_UNAVAILABLE: 13,
  CANNOT_CHECK_SUPPORT: 14, CANNOT_LISTEN_TO_DOWNLOAD_EVENTS: 15, INTERRUPTED: -1,
} as const;

/**
 * An Android error as the browser code chatVoice understands. `glitch`: the plugin saw the recognizer fail right after a
 * restart (TV lesson: some recognizers answer CLIENT / SERVER to a window started at once after the last) and will use a
 * fresh recognizer next: that window just ended, like the browser's "aborted". Busy is the same (the plugin recreates).
 */
export function androidErrorCode(error: number, glitch = false): string {
  const E = ANDROID_ERRORS;
  if (glitch || error === E.RECOGNIZER_BUSY) return "aborted";
  switch (error) {
    case E.NO_MATCH:
    case E.SPEECH_TIMEOUT:
      return "no-speech";
    case E.INSUFFICIENT_PERMISSIONS:
      return "not-allowed";
    case E.AUDIO:
      return "audio-capture";
    case E.NETWORK:
    case E.NETWORK_TIMEOUT:
    case E.SERVER:
    case E.SERVER_DISCONNECTED:
      return "network";
    case E.LANGUAGE_NOT_SUPPORTED:
    case E.LANGUAGE_UNAVAILABLE:
      return "language-not-supported";
    case E.INTERRUPTED:
      return "interrupted";
    default:
      return "client"; // CLIENT, TOO_MANY_REQUESTS, anything new: "voice didn't work"
  }
}

/**
 * What start()'s answer means: null = listening; otherwise the error that ends the press. "mic-prompt": no permission yet, the
 * engine asks for it now (the dialog takes the finger off the mic, so that press can't go on).
 */
export function startOutcome(res: { started?: boolean; permission?: string } | null | undefined): string | null {
  if (res?.started !== false) return null;
  return res.permission === "denied" ? "not-allowed" : "mic-prompt";
}

/** The answer to the permission prompt, reported after the press it ended (the hook shows it as a notice). */
export const permissionNotice = (granted: boolean): string => (granted ? "mic-allowed" : "not-allowed");

/** The TV's restart pacing (ChatVoice.kt): the next window on the same recognizer after a beat, a fresh one after a longer one. */
export const RESTART_DELAY_MS = 40;
export const RECREATE_DELAY_MS = 150;
export const restartDelay = (firstWindow: boolean, lastCode: string | null): number =>
  firstWindow ? 0 : lastCode === "aborted" ? RECREATE_DELAY_MS : RESTART_DELAY_MS;

export function nativeEngine(plugin: JellytipSpeechPlugin): SpeechEngine {
  const em = new VoiceEmitter();
  let session = 0; // the press being listened to; 0 = none (events of any other session are dropped)
  let next = 1;
  let lastCode: string | null = null; // the last error of this press: "aborted" = the plugin builds a fresh recognizer next
  const mine = (e: NativeSpeechEvent | null | undefined) => session !== 0 && e?.session === session;
  // Capacitor drops events nobody listens to: every start waits until the four listeners are attached
  const ready = Promise.all([
    plugin.addListener("partial", (e) => mine(e) && em.emit("partial", { text: e.text ?? "" })),
    plugin.addListener("final", (e) => mine(e) && em.emit("final", { text: e.text ?? "" })),
    plugin.addListener("error", (e) => {
      if (!mine(e)) return;
      lastCode = androidErrorCode(e.error ?? 0, !!e.glitch);
      em.emit("error", { code: lastCode });
    }),
    plugin.addListener("end", (e) => mine(e) && em.emit("end", {})),
  ]).catch(() => []);
  const fail = (s: number, code: string) => {
    if (session !== s) return;
    em.emit("error", { code });
    em.emit("end", {});
  };
  return {
    kind: "native",
    available: () => plugin.available().then((r) => !!r?.available, () => false),
    start({ lang, hold }) {
      // a restart within a press keeps its session (the plugin reuses that recognizer); the hook cancels between presses
      const first = session === 0;
      if (first) session = next++;
      const s = session;
      const delay = restartDelay(first, lastCode);
      lastCode = null;
      const wait = delay ? new Promise<void>((r) => setTimeout(r, delay)) : Promise.resolve();
      void Promise.all([ready, wait])
        .then(() => (session === s ? plugin.start({ session: s, lang, hold }) : null))
        .then(
          (res) => {
            if (res === null) return; // cancelled (or released) before it went out
            const code = startOutcome(res);
            if (!code) return;
            fail(s, code);
            // ask now; the answer comes after the press ended, so it is sent outside any session (the hook shows it as a notice)
            if (code === "mic-prompt")
              void plugin.requestMic().then(
                (r) => em.emit("error", { code: permissionNotice(!!r?.granted) }),
                () => em.emit("error", { code: "not-allowed" }),
              );
          },
          () => fail(s, "start-failed"),
        );
    },
    stop() {
      const s = session;
      if (s !== 0) void plugin.stop({ session: s }).catch(() => fail(s, "start-failed"));
    },
    cancel() {
      session = 0;
      void plugin.cancel().catch(() => {});
    },
    on: (event, cb) => em.on(event, cb),
  };
}
