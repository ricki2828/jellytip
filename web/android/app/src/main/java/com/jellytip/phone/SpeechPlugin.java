package com.jellytip.phone;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;

/**
 * Chat voice for the phone app: Android's SpeechRecognizer behind the web app's speech engine (web/src/lib/speechEngine.ts). The
 * Android WebView has no SpeechRecognition, so the web chat mic uses this instead.
 *
 * Who decides what:
 * - The web side (lib/chatVoice.ts + lib/useVoice.ts) holds the press-to-talk rules for both the browser and the phone: it calls
 *   start() for every listening window, and again after each "end" while the mic is held (the restart rule lives only there).
 * - This plugin runs the window it is told to, on ONE SpeechRecognizer reused across the windows of a press, with a
 *   generation-guarded listener per window. Destroying and creating a recognizer per window lost the speaker's words on the TV
 *   (23-Nov). A fresh recognizer only after cancel(), a busy recognizer, or the first CLIENT / SERVER error right after a
 *   restart (the TV's "glitch": reported as {glitch: true}, a quiet end of that window).
 * - Exactly one end per window: a result or an error ends it (windowOpen), anything after that is dropped (e.g. AOSP answers a
 *   stopListening that raced a queued onResults with a trailing ERROR_CLIENT).
 * - Stale callbacks: SpeechRecognizer calls whichever listener is current when it handles a message, so the gen guard can't stop
 *   a trailing callback of window N that is handled after window N+1's setRecognitionListener. Such a callback lands before
 *   window N+1 has shown any sign of life (ready / beginning of speech / level), so until then results, partials and
 *   NO_MATCH / SPEECH_TIMEOUT are dropped as stale (no speech can have been recognised yet), and CLIENT goes through the glitch
 *   rule (a quiet end + a fresh recognizer, never a failure mid-hold; at most MAX_PRE_READY_GLITCHES in a row, reset by any sign
 *   of life, then CLIENT is a real error). Safety net for recognizers that never report ready / beginning / level: until some
 *   window of the press has shown life, nothing is treated as stale (results, partials and errors are taken as this window's,
 *   and logged as such), and stop() always asks for the last words.
 * - Windows always ask for long silences (a hold's): a tap is ended by the web side's quiet timer (stop()), not by these hints.
 *
 * Events, each with the session (press) it belongs to: partial {text} / final {text} = this window's text so far; error {error}
 * = the SpeechRecognizer.ERROR_* code (-1: the app was left while listening), mapped to browser codes on the web side; end {} =
 * the window is over (exactly one per started window, after any error). cancel() ends a window with no events.
 * Every SpeechRecognizer call runs on the main thread (plugin methods arrive on Capacitor's plugin thread).
 * Log tag "JellytipVoice": a "voice ..." line per window start, result, error, restart and cancel.
 */
@CapacitorPlugin(name = "JellytipSpeech", permissions = { @Permission(alias = SpeechPlugin.MIC, strings = { Manifest.permission.RECORD_AUDIO }) })
public class SpeechPlugin extends Plugin {
    static final String MIC = "microphone";
    private static final String TAG = "JellytipVoice";
    /** While held, ask for silences long enough that only the release ends the window (many recognizers ignore it: the web side restarts). */
    private static final long HOLD_SILENCE_MS = 30_000L;
    private static final int ERROR_SERVER_DISCONNECTED = 11; // SpeechRecognizer.ERROR_SERVER_DISCONNECTED (API 31)
    private static final int ERROR_INTERRUPTED = -1; // ours: the app went to the background while listening

    private final Handler main = new Handler(Looper.getMainLooper());

    // main thread only
    private SpeechRecognizer recognizer;
    private int gen = 0; // one per window: callbacks of an older window on the same (reused) recognizer are dropped
    private boolean windowOpen = false; // startListening was called and no result / error has ended that window yet
    private boolean recreate = false; // the next window builds a new recognizer (after busy / a glitch / a failed start)
    private int session = 0; // the press being listened to (from the web side); 0 = none
    private int windows = 0; // windows started in this session (for the log and the glitch rule)
    private boolean glitchUsed = false; // the tolerated post-restart CLIENT / SERVER error is spent; refilled by a result
    private String partial = "";
    private boolean windowReady = false; // the open window has shown a sign of life (see "Stale callbacks" above)
    private boolean sessionEverReady = false; // some window of this press has shown life: only then can a pre-ready callback be stale
    private int preReadyGlitches = 0; // CLIENT errors in a row before a restarted window showed life
    private static final int MAX_PRE_READY_GLITCHES = 2;
    private boolean paused = false; // the activity is paused: no window may start (the finger is off the mic)
    private int closedSession = 0; // a press stopped / cancelled with no window open: a late start for it opens nothing

    /** {available}: a speech recognition service is installed (needs the RecognitionService <queries> entry on Android 11+). */
    @PluginMethod
    public void available(PluginCall call) {
        main.post(() -> {
            boolean ok = false;
            try {
                ok = SpeechRecognizer.isRecognitionAvailable(getContext());
            } catch (Exception e) {
                Log.w(TAG, "voice availability check failed", e);
            }
            JSObject r = new JSObject();
            r.put("available", ok);
            call.resolve(r);
        });
    }

    /**
     * {session, lang?, hold}: start one listening window. Without the microphone permission it resolves {started: false,
     * permission: "needed"} at once and listens to nothing: the web side ends that press (the system dialog takes the finger off
     * the mic) and calls requestMic().
     */
    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState(MIC) != PermissionState.GRANTED) {
            Log.i(TAG, "voice start: no microphone permission yet");
            JSObject r = new JSObject();
            r.put("started", false);
            r.put("permission", "needed");
            call.resolve(r);
            return;
        }
        final int s = call.getInt("session", 0);
        final String lang = call.getString("lang", null);
        main.post(() -> {
            startWindow(s, lang);
            JSObject r = new JSObject();
            r.put("started", true);
            call.resolve(r);
        });
    }

    /** {granted}: the system's microphone prompt (Android answers at once, with no dialog, when it was refused for good). */
    @PluginMethod
    public void requestMic(PluginCall call) {
        if (getPermissionState(MIC) == PermissionState.GRANTED) {
            micPermission(call);
            return;
        }
        Log.i(TAG, "voice: asking for the microphone permission");
        requestPermissionForAlias(MIC, call, "micPermission");
    }

    @PermissionCallback
    private void micPermission(PluginCall call) {
        boolean granted = getPermissionState(MIC) == PermissionState.GRANTED;
        Log.i(TAG, "voice permission " + (granted ? "granted" : "denied"));
        JSObject r = new JSObject();
        r.put("granted", granted);
        call.resolve(r);
    }

    /**
     * {session}: finish the window: the last words arrive (final), then end. A window that hasn't started hearing yet, or no window
     * at all (between windows, or before the press's first start got here): end at once, and a late start for it opens nothing.
     */
    @PluginMethod
    public void stop(PluginCall call) {
        final int s = call.getInt("session", 0);
        main.post(() -> {
            if (s != 0 && s == session && recognizer != null && windowOpen && (windowReady || !sessionEverReady)) {
                Log.i(TAG, "voice stop: window " + windows + " (session " + s + ")");
                try {
                    recognizer.stopListening();
                } catch (Exception e) {
                    Log.w(TAG, "voice stopListening failed", e);
                    closeWindow(true);
                    emitEnd(s);
                }
            } else if (s != 0) {
                Log.i(TAG, "voice stop: nothing heard yet in session " + s + (windowOpen && s == session ? " (window dropped)" : ""));
                if (s == session && windowOpen) closeWindow(true);
                closedSession = s;
                emitEnd(s);
            }
            call.resolve();
        });
    }

    /** Drop the window (no events) and release the recognizer. */
    @PluginMethod
    public void cancel(PluginCall call) {
        main.post(() -> {
            if (session != 0) Log.i(TAG, "voice cancel (session " + session + ", " + windows + " windows)");
            if (session != 0) closedSession = session;
            session = 0;
            destroyRecognizer();
            call.resolve();
        });
    }

    /** Main thread (activity lifecycle). Between windows too (the restart beat): the press is over, the hook must hear it. */
    @Override
    protected void handleOnPause() {
        paused = true;
        if (session == 0) return;
        Log.i(TAG, "voice: app left during session " + session + (windowOpen ? " (window open)" : " (between windows)"));
        int s = session;
        destroyRecognizer();
        closedSession = s;
        session = 0;
        emitError(s, ERROR_INTERRUPTED, false);
        emitEnd(s);
    }

    @Override
    protected void handleOnResume() {
        paused = false;
    }

    @Override
    protected void handleOnDestroy() {
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
    }

    // ---- main thread ----

    private void startWindow(int s, String lang) {
        if (s == 0 || s == closedSession) {
            Log.i(TAG, "voice start ignored: session " + s + " already stopped");
            emitEnd(s);
            return;
        }
        if (paused) {
            Log.i(TAG, "voice start refused: app paused (session " + s + ")");
            emitError(s, ERROR_INTERRUPTED, false);
            emitEnd(s);
            return;
        }
        if (s != session) {
            // a new press: a window of the old one (should have been cancelled) must not leak into it
            if (windowOpen) destroyRecognizer();
            session = s;
            windows = 0;
            glitchUsed = false;
            sessionEverReady = false;
            preReadyGlitches = 0;
        } else if (windowOpen) {
            // start while this press's window still runs (shouldn't happen: the web side waits for end): end it silently
            closeWindow(true);
        }
        if (recognizer == null || recreate) {
            destroyRecognizer();
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            } catch (Exception e) {
                Log.w(TAG, "voice: recognizer create failed", e);
                recognizer = null;
            }
            recreate = false;
            if (recognizer == null) {
                emitError(s, SpeechRecognizer.ERROR_CLIENT, false);
                emitEnd(s);
                return;
            }
            Log.i(TAG, "voice: recognizer created (session " + s + ")");
        }
        gen++;
        final int myGen = gen;
        final SpeechRecognizer r = recognizer;
        partial = "";
        windowReady = false;
        r.setRecognitionListener(listener(r, myGen, s));
        try {
            r.startListening(intent(lang));
            windowOpen = true;
            windows++;
            Log.i(TAG, "voice window " + windows + " started (session " + s + ", gen " + myGen + (windows > 1 ? ", restart" : "") + ")");
        } catch (Exception e) {
            Log.w(TAG, "voice: startListening failed", e);
            recreate = true;
            emitError(s, SpeechRecognizer.ERROR_CLIENT, false);
            emitEnd(s);
        }
    }

    /** Always a hold's window: the web side's quiet timer ends a tap with stop(). */
    private Intent intent(String lang) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        if (lang != null && !lang.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        // hints only (many recognizers ignore them, and the web side restarts while held): don't end on a short silence
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, HOLD_SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, HOLD_SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, HOLD_SILENCE_MS);
        return i;
    }

    /** End the open window with no events; `cancel`: tell the recognizer too (it stays for reuse unless that fails). */
    private void closeWindow(boolean cancel) {
        gen++;
        windowOpen = false;
        windowReady = false;
        if (cancel && recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception e) {
                recreate = true;
            }
        }
    }

    private void destroyRecognizer() {
        SpeechRecognizer r = recognizer;
        recognizer = null; // first: callbacks from cancel() must find no valid recognizer
        windowOpen = false;
        recreate = false;
        gen++;
        if (r != null) {
            try {
                r.cancel();
                r.destroy();
            } catch (Exception e) {
                Log.w(TAG, "voice: recognizer destroy failed", e);
            }
        }
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = l == null || l.isEmpty() ? null : l.get(0);
        return t == null ? "" : t.trim();
    }

    private void emitText(String event, int s, String text) {
        JSObject e = new JSObject();
        e.put("session", s);
        e.put("text", text);
        notifyListeners(event, e);
    }

    private void emitError(int s, int error, boolean glitch) {
        JSObject e = new JSObject();
        e.put("session", s);
        e.put("error", error);
        e.put("glitch", glitch);
        notifyListeners("error", e);
    }

    private void emitEnd(int s) {
        JSObject e = new JSObject();
        e.put("session", s);
        notifyListeners("end", e);
    }

    private RecognitionListener listener(final SpeechRecognizer mine, final int myGen, final int s) {
        return new RecognitionListener() {
            /** This window, still open: a result or an error closes it, so each window ends exactly once. */
            private boolean valid() {
                return recognizer == mine && gen == myGen && session == s && windowOpen;
            }

            private void alive() {
                if (!valid()) return;
                windowReady = true;
                sessionEverReady = true;
                preReadyGlitches = 0;
            }

            /**
             * Before this window showed any life nothing can have been recognised: a result now is window N-1's, handled late. Unless
             * no window of this press ever showed life (a recognizer without those callbacks): then it is taken as this window's.
             */
            private boolean stale(String what) {
                if (windowReady) return false;
                if (!sessionEverReady) {
                    Log.i(TAG, "voice window " + windows + ": " + what + " with no ready yet in this press, taken as this window's");
                    return false;
                }
                Log.i(TAG, "voice window " + windows + ": stale " + what + " before ready, dropped");
                return true;
            }

            @Override
            public void onReadyForSpeech(Bundle params) {
                if (!valid()) return;
                alive();
                Log.i(TAG, "voice window " + windows + " ready (gen " + myGen + ")");
            }

            @Override
            public void onBeginningOfSpeech() {
                alive();
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                if (!windowReady) alive();
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!valid()) return;
                String p = first(partialResults);
                if (p.isEmpty()) return;
                // logged once per window (partials come several a second); a stale one is dropped every time
                if (!windowReady && (partial.isEmpty() ? stale("partial") : sessionEverReady)) return;
                partial = p;
                glitchUsed = false; // proof the recognizer works
                emitText("partial", s, p);
            }

            @Override
            public void onResults(Bundle results) {
                if (!valid() || stale("result")) return;
                String seg = first(results);
                if (seg.isEmpty()) seg = partial; // some recognizers answer stopListening with an empty final
                closeWindow(false);
                if (!seg.isEmpty()) glitchUsed = false;
                Log.i(TAG, "voice window " + windows + " result: " + seg.length() + " chars");
                emitText("final", s, seg);
                emitEnd(s);
            }

            @Override
            public void onError(int error) {
                if (!valid()) return;
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && stale("error " + error)) return;
                // pre-ready in a press that has shown life before: likely a late callback of the last window
                boolean preReady = !windowReady && sessionEverReady;
                closeWindow(false);
                // the first CLIENT / SERVER after a restart, or a CLIENT before a restarted window showed life (likely window N-1's
                // trailing answer to stopListening): a quiet end and a fresh recognizer, never a failure in the middle of a hold
                boolean glitch = windows > 1
                    && ((!glitchUsed && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == ERROR_SERVER_DISCONNECTED))
                        || (preReady && error == SpeechRecognizer.ERROR_CLIENT && preReadyGlitches < MAX_PRE_READY_GLITCHES));
                if (glitch) glitchUsed = true;
                if (glitch && preReady) preReadyGlitches++;
                if (glitch || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) recreate = true;
                Log.i(TAG, "voice window " + windows + " error " + error + (glitch ? " (glitch: new recognizer next)" : "") + " partial=" + partial.length() + " chars");
                emitError(s, error, glitch);
                emitEnd(s);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        };
    }
}
