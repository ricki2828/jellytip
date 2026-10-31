package com.jellytip.phone;

import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * One poller of GET {apiBase}/v1/sport/match/{id}/live for the whole player: the landscape drawer, the portrait panel
 * and the flip alerts all read it. Every 15 s while something shows it, every 30 s otherwise (flip alerts only).
 */
final class LiveFeed {

    interface Listener {
        void onLive();
    }

    private static final long FAST_MS = 15_000;
    private static final long SLOW_MS = 30_000;

    private final Handler handler;
    private final String apiBase;
    private final String token;
    private final String matchId;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean fast;
    private boolean running;
    private boolean released;
    /** The last good answer (null until one arrives). */
    JSONObject last;
    /** Why there's nothing to show (null while loading or once data arrived). */
    String note;
    /** The route is missing on this server (404): polling stops. */
    boolean missing;

    private final Runnable poll =
        new Runnable() {
            @Override
            public void run() {
                if (released || missing) return;
                fetch();
                handler.postDelayed(this, fast ? FAST_MS : SLOW_MS);
            }
        };

    LiveFeed(Handler handler, String apiBase, String token, String matchId) {
        this.handler = handler;
        this.apiBase = apiBase;
        this.token = token;
        this.matchId = matchId;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void start() {
        if (running || released) return;
        running = true;
        handler.post(poll);
    }

    /** Something on screen shows live data: poll every 15 s (and once now when it speeds up). */
    void setFast(boolean f) {
        if (fast == f) return;
        fast = f;
        if (f && running) {
            handler.removeCallbacks(poll);
            handler.post(poll);
        }
    }

    void release() {
        released = true;
        handler.removeCallbacks(poll);
        io.shutdownNow();
    }

    private void fetch() {
        io.execute(() -> {
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, "v1/sport/match/" + CompanionHttp.enc(matchId) + "/live");
            JSONObject got = null;
            if (a.body != null) {
                try {
                    got = new JSONObject(a.body);
                } catch (org.json.JSONException ignored) {
                    // shown as unavailable
                }
            }
            final JSONObject result = got;
            final int code = a.code;
            handler.post(() -> {
                if (released) return;
                if (result != null) {
                    last = result;
                    note = null;
                } else if (code == 404) {
                    missing = true;
                    if (last == null) note = "Live stats aren't available for this match.";
                } else if (last == null) {
                    note = "Live stats aren't available right now.";
                }
                for (Listener l : listeners) l.onLive();
            });
        });
    }
}
