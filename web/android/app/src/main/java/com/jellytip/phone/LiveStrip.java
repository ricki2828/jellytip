package com.jellytip.phone;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * The live views' scoreboard strip (plan "Live panel: pop, context, performance"): both teams' colours on a diagonal,
 * crests, the score (pulses when it changes), period and clock with a breathing live dot, a one-line situation under
 * it, and a flash of the team colour with a label on big moments. Built once; [bind] only updates what changed.
 */
final class LiveStrip {

    final LinearLayout view;
    private final Activity a;
    private final String apiBase;
    private final String token;
    private final FrameLayout strip;
    private final ImageView homeCrest;
    private final ImageView awayCrest;
    private final TextView homeName;
    private final TextView awayName;
    private final TextView score;
    private final TextView clock;
    private final View dot;
    private final TextView flash;
    private final TextView summary;
    private ObjectAnimator dotAnim;
    private int boundHome;
    private int boundAway;

    LiveStrip(Activity a, String apiBase, String token) {
        this.a = a;
        this.apiBase = apiBase;
        this.token = token;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        strip = new FrameLayout(a);
        strip.setClipToOutline(true);
        strip.setOutlineProvider(
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
                }
            }
        );
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        homeCrest = crest();
        awayCrest = crest();
        homeName = LiveRender.text(a, "", 13, Color.WHITE);
        awayName = LiveRender.text(a, "", 13, Color.WHITE);
        for (TextView t : new TextView[] {homeName, awayName}) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        homeName.setPadding(dp(6), 0, 0, 0);
        awayName.setPadding(0, 0, dp(6), 0);
        awayName.setGravity(Gravity.END);
        LinearLayout centre = new LinearLayout(a);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER_HORIZONTAL);
        score = LiveRender.text(a, "v", 20, Color.WHITE);
        score.setTypeface(score.getTypeface(), android.graphics.Typeface.BOLD);
        centre.addView(score);
        LinearLayout clockRow = new LinearLayout(a);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFB3261E);
        dot.setBackground(d);
        clockRow.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        clock = LiveRender.text(a, "LIVE", 10, 0xD9FFFFFF);
        clock.setPadding(dp(4), 0, 0, 0);
        clockRow.addView(clock);
        centre.addView(clockRow);
        row.addView(homeCrest, new LinearLayout.LayoutParams(dp(30), dp(30)));
        row.addView(homeName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(centre, new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(awayName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(awayCrest, new LinearLayout.LayoutParams(dp(30), dp(30)));
        strip.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        flash = LiveRender.text(a, "", 20, Color.WHITE);
        flash.setTypeface(flash.getTypeface(), android.graphics.Typeface.BOLD);
        flash.setLetterSpacing(0.15f);
        flash.setGravity(Gravity.CENTER);
        flash.setVisibility(View.GONE);
        strip.addView(flash, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        summary = LiveRender.text(a, "", 12, 0xFFFFC94A);
        summary.setTypeface(summary.getTypeface(), android.graphics.Typeface.BOLD);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setPadding(0, dp(6), 0, 0);
        summary.setVisibility(View.GONE);
        view.addView(summary);
        bindTeams(LiveTeams.NONE);
    }

    private ImageView crest() {
        ImageView v = new ImageView(a);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    void bindTeams(LiveTeams t) {
        if (t.homeColor != boundHome || t.awayColor != boundAway) {
            boundHome = t.homeColor;
            boundAway = t.awayColor;
            strip.setBackground(new MatchStyle.SplitDrawable(MatchStyle.darken(t.homeColor), MatchStyle.darken(t.awayColor), 0));
        }
        setText(homeName, t.homeShort);
        setText(awayName, t.awayShort);
        ImageCache.load(homeCrest, t.homeLogo, apiBase, token, dp(30));
        ImageCache.load(awayCrest, t.awayLogo, apiBase, token, dp(30));
    }

    /** The latest /live answer: score (pulses on change), period · clock, the situation line. */
    void bind(JSONObject live) {
        if (live == null) return;
        JSONObject sc = live.optJSONObject("score");
        String s = sc != null && !sc.isNull("home") && !sc.isNull("away") ? sc.optString("home") + " – " + sc.optString("away") : "v";
        if (!s.contentEquals(score.getText())) {
            boolean had = !"v".contentEquals(score.getText());
            score.setText(s);
            if (had) {
                score.animate().scaleX(1.15f).scaleY(1.15f).setDuration(140).withEndAction(() -> score.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
            }
        }
        String status = str(live, "status");
        String period = str(live, "period");
        String c = str(live, "clock");
        if (period == null && sc != null) period = str(sc, "period");
        if (c == null && sc != null) c = str(sc, "clock");
        String line = period != null && c != null ? period + " · " + c : period != null ? period : c != null ? c : "post".equals(status) ? "FT" : "LIVE";
        setText(clock, line);
        dot.setVisibility(status == null || "in".equals(status) ? View.VISIBLE : View.GONE);
        String sum = LiveContent.summary(live.optJSONObject("situation"));
        setText(summary, sum == null ? "" : sum);
        summary.setVisibility(sum == null ? View.GONE : View.VISIBLE);
    }

    /** The dot breathes only while the strip is on screen. */
    void setActive(boolean on) {
        if (on && dotAnim == null) {
            dotAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.3f);
            dotAnim.setDuration(800);
            dotAnim.setRepeatCount(ValueAnimator.INFINITE);
            dotAnim.setRepeatMode(ValueAnimator.REVERSE);
            dotAnim.start();
        } else if (!on && dotAnim != null) {
            dotAnim.cancel();
            dotAnim = null;
            dot.setAlpha(1f);
        }
    }

    /** One flash of [color] with [label] (alpha and scale only). */
    void flash(String label, int color) {
        flash.setText(label);
        flash.setBackgroundColor(color);
        flash.setVisibility(View.VISIBLE);
        flash.setAlpha(0f);
        flash.setScaleX(0.94f);
        flash.setScaleY(0.94f);
        flash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).withEndAction(() ->
            flash.animate().alpha(0f).setStartDelay(1_400).setDuration(420).withEndAction(() -> {
                flash.setVisibility(View.GONE);
                flash.animate().setStartDelay(0);
            }).start()
        ).start();
    }

    private static void setText(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    private static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private int dp(int v) {
        return LiveRender.dp(a, v);
    }
}
