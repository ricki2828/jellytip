package com.jellytip.phone;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone's in-event drawer (~/ai/plans/jellytip-live-sport.md, "In-event panel"): a right-hand panel over the
 * native player with Stats · Plays · Odds · Chat, polling GET {apiBase}/v1/sport/match/{id}/live every 15 s with the
 * caller's Jellyfin token. It slides in from the right (260 ms) and out (200 ms); the video keeps playing. A missing
 * route (404) shows a note.
 */
final class LiveStatsDrawer {

    private static final long REFRESH_MS = 15_000;
    private static final String[] TABS = {"Stats", "Plays", "Odds", "Chat"};

    private final Activity activity;
    private final Handler handler;
    private final String apiBase;
    private final String token;
    private final String matchId;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FrameLayout panel;
    private final LinearLayout content;
    private final TextView[] tabViews = new TextView[TABS.length];
    private int tab = 0;
    private boolean open;
    private JSONObject last;
    private String note = "Loading live data…";

    private final Runnable poll =
        new Runnable() {
            @Override
            public void run() {
                if (!open) return;
                fetch();
                handler.postDelayed(this, REFRESH_MS);
            }
        };

    LiveStatsDrawer(Activity activity, FrameLayout root, Handler handler, String apiBase, String token, String matchId) {
        this.activity = activity;
        this.handler = handler;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.token = token;
        this.matchId = matchId;

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(0xE6101014);
        panel.setClickable(true); // taps on the panel don't reach the player underneath
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(16));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Live", 16, Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeView = text("✕", 20, Color.WHITE);
        closeView.setPadding(dp(12), dp(4), dp(4), dp(4));
        closeView.setOnClickListener(v -> close());
        head.addView(closeView);
        col.addView(head);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(10), 0, dp(10));
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = text(TABS[i], 13, Color.WHITE);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setOnClickListener(v -> selectTab(index));
            tabViews[i] = t;
            tabs.addView(t);
        }
        col.addView(tabs);

        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(col);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, lp);
        panel.setVisibility(View.GONE);
        // ~40% of the width in landscape, most of it in portrait
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            int want = w > h ? (int) (w * 0.4f) : (int) (w * 0.92f);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (p.width != want) {
                p.width = want;
                panel.setLayoutParams(p);
            }
        });
        selectTab(0);
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        open = true;
        panel.setVisibility(View.VISIBLE);
        panel.post(() -> {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    void close() {
        if (!open) return;
        open = false;
        handler.removeCallbacks(poll);
        panel.animate().translationX(panel.getWidth()).setDuration(200).setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    void release() {
        open = false;
        handler.removeCallbacks(poll);
        io.shutdownNow();
    }

    private void selectTab(int index) {
        tab = index;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setBackgroundColor(i == index ? 0xFFF2F2F0 : Color.TRANSPARENT);
            tabViews[i].setTextColor(i == index ? Color.BLACK : Color.WHITE);
        }
        render();
    }

    private void fetch() {
        io.execute(() -> {
            JSONObject got = null;
            String error = null;
            HttpURLConnection c = null;
            try {
                URL url = new URL(apiBase + "/v1/sport/match/" + URLEncoder.encode(matchId, "UTF-8") + "/live");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(8_000);
                if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + token + "\"");
                int code = c.getResponseCode();
                if (code == 404) {
                    error = "Live stats aren't available for this match.";
                } else if (code == 200) {
                    got = new JSONObject(read(c.getInputStream()));
                } else {
                    error = "Live stats aren't available right now.";
                }
            } catch (Exception e) {
                error = "Live stats aren't available right now.";
            } finally {
                if (c != null) c.disconnect();
            }
            final JSONObject result = got;
            final String err = error;
            handler.post(() -> {
                if (result != null) {
                    last = result;
                    note = null;
                } else if (last == null) {
                    note = err;
                }
                render();
            });
        });
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private void render() {
        content.removeAllViews();
        if (last == null) {
            content.addView(muted(note == null ? "Loading live data…" : note));
            return;
        }
        JSONObject sc = last.optJSONObject("score");
        if (sc != null && !sc.optString("home").isEmpty()) {
            List<String> parts = new ArrayList<>();
            parts.add(sc.optString("home") + " – " + sc.optString("away"));
            if (!last.optString("period").isEmpty() && !last.isNull("period")) parts.add(last.optString("period"));
            if (!last.optString("clock").isEmpty() && !last.isNull("clock")) parts.add(last.optString("clock"));
            content.addView(text(android.text.TextUtils.join("  ·  ", parts), 15, Color.WHITE));
        }
        switch (tab) {
            case 0:
                renderStats();
                break;
            case 1:
                renderPlays();
                break;
            case 2:
                renderOdds();
                break;
            default:
                renderChat();
        }
    }

    private void renderStats() {
        JSONArray stats = last.optJSONArray("stats");
        if (stats == null || stats.length() == 0) {
            content.addView(muted("No stats for this match yet."));
        } else {
            for (int i = 0; i < Math.min(stats.length(), 14); i++) {
                JSONObject s = stats.optJSONObject(i);
                if (s == null) continue;
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(text(s.optString("home"), 13, Color.WHITE), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));
                TextView label = text(s.optString("label"), 12, 0x99F2F2F0);
                label.setGravity(Gravity.CENTER);
                row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f));
                TextView away = text(s.optString("away"), 13, Color.WHITE);
                away.setGravity(Gravity.END);
                row.addView(away, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));
                row.setPadding(0, dp(3), 0, dp(3));
                content.addView(row);
            }
        }
        JSONObject wp = last.optJSONObject("winProb");
        if (wp != null && wp.has("home")) content.addView(muted("Win probability (home): " + pct(wp.optDouble("home"))));
    }

    private void renderPlays() {
        JSONArray plays = last.optJSONArray("plays");
        if (plays == null || plays.length() == 0) {
            content.addView(muted("No plays yet."));
            return;
        }
        for (int i = 0; i < Math.min(plays.length(), 15); i++) {
            JSONObject p = plays.optJSONObject(i);
            if (p == null) continue;
            String when = (p.optString("period", "") + " " + p.optString("clock", "")).trim().replace("null", "").trim();
            TextView t = text((when.isEmpty() ? "" : when + "  ") + p.optString("text"), 13, p.optBoolean("scoring") ? Color.WHITE : 0xCCF2F2F0);
            if (p.optBoolean("scoring")) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setPadding(0, dp(3), 0, dp(3));
            content.addView(t);
        }
    }

    private void renderOdds() {
        JSONObject m = last.optJSONObject("market");
        if (m == null || (!m.has("home") && !m.has("away"))) {
            content.addView(muted("No market for this match."));
            return;
        }
        List<String> parts = new ArrayList<>();
        if (m.has("home") && !m.isNull("home")) parts.add("Home " + pct(m.optDouble("home")));
        if (m.has("draw") && !m.isNull("draw")) parts.add("Draw " + pct(m.optDouble("draw")));
        if (m.has("away") && !m.isNull("away")) parts.add("Away " + pct(m.optDouble("away")));
        content.addView(text(android.text.TextUtils.join(" · ", parts), 16, Color.WHITE));
        List<String> meta = new ArrayList<>();
        if (!m.optString("source").isEmpty()) meta.add(m.optString("source"));
        if (m.has("volume") && !m.isNull("volume")) meta.add(money(m.optDouble("volume")) + " traded");
        content.addView(muted(android.text.TextUtils.join(", ", meta)));
        if (!m.optString("question").isEmpty() && !m.isNull("question")) content.addView(muted(m.optString("question")));
    }

    private void renderChat() {
        JSONObject chat = last.optJSONObject("chat");
        JSONArray comments = chat == null ? null : chat.optJSONArray("comments");
        if (comments == null || comments.length() == 0) {
            content.addView(muted("No match thread comments."));
            return;
        }
        for (int i = 0; i < Math.min(comments.length(), 25); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c == null) continue;
            String author = c.optString("author", "");
            TextView t = text((author.isEmpty() || "null".equals(author) ? "" : author + ": ") + c.optString("text"), 13, 0xDDF2F2F0);
            t.setPadding(0, dp(4), 0, dp(4));
            content.addView(t);
        }
    }

    private static String pct(double p) {
        return Math.round(p * 100) + "%";
    }

    private static String money(double v) {
        if (v >= 1_000_000) return String.format(Locale.US, "$%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "$%.0fK", v / 1_000);
        return String.format(Locale.US, "$%.0f", v);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private TextView muted(String s) {
        TextView t = text(s, 12, 0x99F2F2F0);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
