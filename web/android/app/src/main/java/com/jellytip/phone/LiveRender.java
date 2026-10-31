package com.jellytip.phone;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stats · Plays · Odds · Chat from a /live answer, for the landscape drawer and the portrait panel alike. */
final class LiveRender {

    static final String[] TABS = {"Stats", "Plays", "Odds", "Chat"};

    private LiveRender() {}

    /** The last /live answer carries a `chat` block; without one the Chat tab is hidden (no match-thread source). */
    static boolean hasChat(LiveFeed feed) {
        return feed.last != null && feed.last.has("chat") && !feed.last.isNull("chat");
    }

    /** Fills [content] (cleared first) with [tab] of [feed]'s last answer, or its note while there is none. */
    static void render(Context ctx, LinearLayout content, LiveFeed feed, String tab) {
        content.removeAllViews();
        JSONObject last = feed.last;
        if (last == null) {
            content.addView(muted(ctx, feed.note == null ? "Loading live data…" : feed.note));
            return;
        }
        JSONObject sc = last.optJSONObject("score");
        if (sc != null && !sc.optString("home").isEmpty()) {
            List<String> parts = new ArrayList<>();
            parts.add(sc.optString("home") + " – " + sc.optString("away"));
            if (!last.isNull("period") && !last.optString("period").isEmpty()) parts.add(last.optString("period"));
            if (!last.isNull("clock") && !last.optString("clock").isEmpty()) parts.add(last.optString("clock"));
            content.addView(text(ctx, android.text.TextUtils.join("  ·  ", parts), 15, Color.WHITE));
        }
        switch (tab) {
            case "Stats":
                renderStats(ctx, content, last);
                break;
            case "Plays":
                renderPlays(ctx, content, last);
                break;
            case "Odds":
                renderOdds(ctx, content, last);
                break;
            default:
                renderChat(ctx, content, last);
        }
    }

    private static void renderStats(Context ctx, LinearLayout content, JSONObject last) {
        JSONArray stats = last.optJSONArray("stats");
        if (stats == null || stats.length() == 0) {
            content.addView(muted(ctx, "No stats for this match yet."));
        } else {
            for (int i = 0; i < Math.min(stats.length(), 14); i++) {
                JSONObject s = stats.optJSONObject(i);
                if (s == null) continue;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(text(ctx, s.optString("home"), 13, Color.WHITE), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));
                TextView label = text(ctx, s.optString("label"), 12, 0x99F2F2F0);
                label.setGravity(Gravity.CENTER);
                row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f));
                TextView away = text(ctx, s.optString("away"), 13, Color.WHITE);
                away.setGravity(Gravity.END);
                row.addView(away, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f));
                row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
                content.addView(row);
            }
        }
        JSONObject wp = last.optJSONObject("winProb");
        if (wp != null && wp.has("home")) content.addView(muted(ctx, "Win probability (home): " + pct(wp.optDouble("home"))));
    }

    private static void renderPlays(Context ctx, LinearLayout content, JSONObject last) {
        JSONArray plays = last.optJSONArray("plays");
        if (plays == null || plays.length() == 0) {
            content.addView(muted(ctx, "No plays yet."));
            return;
        }
        for (int i = 0; i < Math.min(plays.length(), 15); i++) {
            JSONObject p = plays.optJSONObject(i);
            if (p == null) continue;
            String when = (p.optString("period", "") + " " + p.optString("clock", "")).trim().replace("null", "").trim();
            TextView t = text(ctx, (when.isEmpty() ? "" : when + "  ") + p.optString("text"), 13, p.optBoolean("scoring") ? Color.WHITE : 0xCCF2F2F0);
            if (p.optBoolean("scoring")) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
            content.addView(t);
        }
    }

    private static void renderOdds(Context ctx, LinearLayout content, JSONObject last) {
        JSONObject m = last.optJSONObject("market");
        if (m == null || (!m.has("home") && !m.has("away"))) {
            content.addView(muted(ctx, "No market for this match."));
            return;
        }
        List<String> parts = new ArrayList<>();
        if (m.has("home") && !m.isNull("home")) parts.add("Home " + pct(m.optDouble("home")));
        if (m.has("draw") && !m.isNull("draw")) parts.add("Draw " + pct(m.optDouble("draw")));
        if (m.has("away") && !m.isNull("away")) parts.add("Away " + pct(m.optDouble("away")));
        content.addView(text(ctx, android.text.TextUtils.join(" · ", parts), 16, Color.WHITE));
        List<String> meta = new ArrayList<>();
        if (!m.optString("source").isEmpty()) meta.add(m.optString("source"));
        if (m.has("volume") && !m.isNull("volume")) meta.add(money(m.optDouble("volume")) + " traded");
        content.addView(muted(ctx, android.text.TextUtils.join(", ", meta)));
        if (!m.isNull("question") && !m.optString("question").isEmpty()) content.addView(muted(ctx, m.optString("question")));
    }

    private static void renderChat(Context ctx, LinearLayout content, JSONObject last) {
        JSONObject chat = last.optJSONObject("chat");
        JSONArray comments = chat == null ? null : chat.optJSONArray("comments");
        if (comments == null || comments.length() == 0) {
            content.addView(muted(ctx, "No match thread comments."));
            return;
        }
        for (int i = 0; i < Math.min(comments.length(), 25); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c == null) continue;
            String author = c.optString("author", "");
            TextView t = text(ctx, (author.isEmpty() || "null".equals(author) ? "" : author + ": ") + c.optString("text"), 13, 0xDDF2F2F0);
            t.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
            content.addView(t);
        }
    }

    static String pct(double p) {
        return Math.round(p * 100) + "%";
    }

    private static String money(double v) {
        if (v >= 1_000_000) return String.format(Locale.US, "$%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "$%.0fK", v / 1_000);
        return String.format(Locale.US, "$%.0f", v);
    }

    static TextView text(Context ctx, String s, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    static TextView muted(Context ctx, String s) {
        TextView t = text(ctx, s, 12, 0x99F2F2F0);
        t.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        return t;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
