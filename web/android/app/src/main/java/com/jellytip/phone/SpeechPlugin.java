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
 *   generation-guarded listener per window (callbacks of an older window are dropped). Destroying and creating a recognizer per
 *   window lost the speaker's words on the TV (23-Nov). A fresh recognizer only after cancel(), a busy recognizer, or the first
 *   CLIENT / SERVER error right after a restart (the TV's "glitch": reported as {glitch: true}, a quiet end of that window).
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
        final boolean hold = Boolean.TRUE.equals(call.getBoolean("hold", false));
        main.post(() -> {
            startWindow(s, lang, hold);
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

    /** {session}: finish the window: the last words arrive (final), then end. Between windows: end at once. */
    @PluginMethod
    public void stop(PluginCall call) {
        final int s = call.getInt("session", 0);
        main.post(() -> {
            if (s == session && recognizer != null && windowOpen) {
                Log.i(TAG, "voice stop: window " + windows + " (session " + s + ")");
                try {
                    recognizer.stopListening();
                } catch (Exception e) {
                    Log.w(TAG, "voice stopListening failed", e);
                    windowOpen = false;
                    gen++;
                    emitEnd(s);
                }
            } else if (s == session && s != 0) {
                emitEnd(s); // nothing is listening: the press is over now
            }
            call.resolve();
        });
    }

    /** Drop the window (no events) and release the recognizer. */
    @PluginMethod
    public void cancel(PluginCall call) {
        main.post(() -> {
            if (session != 0) Log.i(TAG, "voice cancel (session " + session + ", " + windows + " windows)");
            session = 0;
            destroyRecognizer();
            call.resolve();
        });
    }

    @Override
    protected void handleOnPause() {
        main.post(() -> {
            if (session == 0 || recognizer == null) return;
            Log.i(TAG, "voice: app left while listening (session " + session + ")");
            int s = session;
            boolean open = windowOpen;
            destroyRecognizer();
            if (open) {
                emitError(s, ERROR_INTERRUPTED, false);
                emitEnd(s);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
    }

    // ---- main thread ----

    private void startWindow(int s, String lang, boolean hold) {
        if (s != session) {
            // a new press: a window of the old one (should have been cancelled) must not leak into it
            if (windowOpen) destroyRecognizer();
            session = s;
            windows = 0;
            glitchUsed = false;
        } else if (windowOpen) {
            // start while this press's window still runs (shouldn't happen: the web side waits for end): end it silently
            gen++;
            try {
                recognizer.cancel();
            } catch (Exception e) {
                recreate = true;
            }
            windowOpen = false;
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
        r.setRecognitionListener(listener(r, myGen, s));
        try {
            r.startListening(intent(lang, hold));
            windowOpen = true;
            windows++;
            Log.i(TAG, "voice window " + windows + " started (session " + s + ", gen " + myGen + ", " + (hold ? "hold" : "tap") + (windows > 1 ? ", restart" : "") + ")");
        } catch (Exception e) {
            Log.w(TAG, "voice: startListening failed", e);
            recreate = true;
            emitError(s, SpeechRecognizer.ERROR_CLIENT, false);
            emitEnd(s);
        }
    }

    private Intent intent(String lang, boolean hold) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        if (lang != null && !lang.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        // hints only (many recognizers ignore them): held = don't end on silence; tap = end a couple of seconds after the words
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, hold ? HOLD_SILENCE_MS : 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, hold ? HOLD_SILENCE_MS : 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, hold ? HOLD_SILENCE_MS : 2000L);
        return i;
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
            private boolean valid() {
                return recognizer == mine && gen == myGen && session == s;
            }

            @Override
            public void onReadyForSpeech(Bundle params) {
                if (valid()) Log.i(TAG, "voice window " + windows + " ready (gen " + myGen + ")");
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!valid()) return;
                String p = first(partialResults);
                if (p.isEmpty()) return;
                partial = p;
                glitchUsed = false; // proof the recognizer works
                emitText("partial", s, p);
            }

            @Override
            public void onResults(Bundle results) {
                if (!valid()) return;
                String seg = first(results);
                if (seg.isEmpty()) seg = partial; // some recognizers answer stopListening with an empty final
                windowOpen = false;
                if (!seg.isEmpty()) glitchUsed = false;
                Log.i(TAG, "voice window " + windows + " result: " + seg.length() + " chars");
                emitText("final", s, seg);
                emitEnd(s);
            }

            @Override
            public void onError(int error) {
                if (!valid()) return;
                windowOpen = false;
                boolean glitch = windows > 1 && !glitchUsed
                    && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == ERROR_SERVER_DISCONNECTED);
                if (glitch) glitchUsed = true;
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
