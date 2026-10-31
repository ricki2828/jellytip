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

/** Small shared helpers for the live views (tab names, text views, chips, percentages). */
final class LiveRender {

    static final String[] TABS = {"Stats", "Plays", "Odds", "Chat"};

    private LiveRender() {}

    /** The last /live answer carries a `chat` block; without one the Chat tab is hidden (no match-thread source). */
    static boolean hasChat(LiveFeed feed) {
        return feed.last != null && feed.last.has("chat") && !feed.last.isNull("chat");
    }

    /** A pill tab chip with its icon; the selected one is filled (plan "Live panel: pop, context, performance"). */
    static void styleTab(Context ctx, TextView t, String name, boolean on) {
        String label = icon(name) + "  " + name;
        if (!label.contentEquals(t.getText())) t.setText(label);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(on ? 0xFFFFC94A : 0x1AFFFFFF);
        t.setBackground(bg);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
        t.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
    }

    static boolean isLiveTab(String tab) {
        for (String t : TABS) if (t.equals(tab)) return true;
        return false;
    }

    static String icon(String tab) {
        switch (tab) {
            case "Info":
                return "\u24D8";
            case "Stats":
                return "\uD83D\uDCCA";
            case "Plays":
                return "\u23F1";
            case "Odds":
                return "\uD83D\uDCC8";
            case "Chat":
                return "\uD83D\uDCAC";
            case "Fantasy":
                return "\uD83C\uDFC8";
            default:
                return "\u2022";
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
