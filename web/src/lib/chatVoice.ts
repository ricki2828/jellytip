// Press-to-talk decisions for the chat mic (pure, tested). The wiring lives in lib/useVoice.ts, over a speech engine
// (lib/speechEngine.ts): the browser's SpeechRecognition, or Android's SpeechRecognizer in the phone app (SpeechPlugin.java).
// These rules are the only place a restart is decided, for both engines: the phone plugin just runs the window it is told to,
// on one reused recognizer.
// Hold the mic (pointer down, or Space / Enter on the focused mic) to talk, release to send. While it is held a recognizer that ends by
// itself (Chrome ends a session after a pause or about a minute) is restarted and the text so far kept, so a pause never cuts the
// speaker off. A press shorter than TAP_MS is a tap: listen until a pause (TAP_SILENCE_MS after the last words) or a second tap.

export const TAP_MS = 300;
export const TAP_SILENCE_MS = 2500;
export const MAX_RESTARTS = 40;

/** Segments appended with one space, whitespace collapsed; an empty side adds nothing. */
export const joinSpeech = (a: string, b: string): string => `${a} ${b}`.replace(/\s+/g, " ").trim();

/** Everything one recognition session has heard so far (final and interim results, in order). */
export function sessionText(results: ArrayLike<ArrayLike<{ transcript: string }>>): string {
  let out = "";
  for (let i = 0; i < results.length; i++) out = joinSpeech(out, results[i]?.[0]?.transcript ?? "");
  return out;
}

export const isTap = (heldMs: number): boolean => heldMs < TAP_MS;

/** The recognizer ended by itself: restart while held on a hold press (up to MAX_RESTARTS per hold), otherwise the utterance is over. */
export const afterEnd = (held: boolean, tap: boolean, restarts: number): "restart" | "finish" => (held && !tap && restarts < MAX_RESTARTS ? "restart" : "finish");

/** "no-speech" and "aborted" just mean that listening window is over (onend follows); anything else is a failure to tell the person about. */
export const isQuietError = (code: string): boolean => code === "no-speech" || code === "aborted";

/** What to tell the person about a failed press. `phone`: the Android app (its permission lives in the system settings, not a browser). */
export function errorText(code: string, phone = false): string {
  if (code === "not-allowed" || code === "service-not-allowed")
    return phone ? "Microphone blocked: check the app's microphone permission in Android settings" : "Microphone blocked: allow it in your browser to talk";
  if (code === "mic-prompt") return "Allow the microphone, then hold the mic to talk"; // the permission prompt ended that press
  if (code === "mic-allowed") return "Microphone allowed: hold the mic to talk";
  if (code === "audio-capture") return "No microphone found";
  if (code === "network") return "Voice needs a network connection";
  if (code === "interrupted") return "Voice stopped when the app was left: hold the mic to talk again";
  return "Voice didn't work: type it instead";
}

/** A silent press: nothing was added to what was in the box at press time, so there is nothing new to send (never re-send typed text). */
export const heardNothing = (base: string, text: string): boolean => !text.trim() || joinSpeech(text, "") === joinSpeech(base, "");
