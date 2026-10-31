package com.jellytip.phone;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * The live views' score strip in the Broadcast look (plan "In-match visual direction: DECIDED 31-Oct", mockup A (c)):
 * two angled full-colour slabs with crests, condensed names and scores (the score slides up on change), then a
 * situation row (gold box with down & distance or the period, the situation, the clock and a live dot). Moments: the
 * team's colour band wipes across the strip from the left with a gold leading edge (300 ms), holds 2 s and wipes off
 * to the right; the score swaps underneath while it's covered. Built once; [bind] only updates what changed.
 */
final class LiveStrip {

    private static final long WIPE_MS = 300;
    private static final long HOLD_MS = 2_000;

    final LinearLayout view;
    private final Activity a;
    private final String apiBase;
    private final String token;
    private final FrameLayout strip;
    private final Broadcast.Slab homeSlab = new Broadcast.Slab(0xFF2A303B, true);
    private final Broadcast.Slab awaySlab = new Broadcast.Slab(0xFF1C212A, false);
    private final ImageView homeCrest;
    private final ImageView awayCrest;
    private final TextView homeName;
    private final TextView awayName;
    private final TextView homeScore;
    private final TextView awayScore;
    private final TextView box;
    private final TextView situation;
    private final TextView clock;
    private final View dot;
    private final LinearLayout band;
    private final TextView bandLabel;
    private final TextView bandDetail;
    private ObjectAnimator dotAnim;
    private boolean wiping;
    private String pendingHome;
    private String pendingAway;
    private LiveTeams teams = LiveTeams.NONE;
    private final LinearLayout slabs;
    /** Events (F1, golf): one full-width slab in the series colour with the title instead of the two teams. */
    private final LinearLayout eventSlab;
    private final Broadcast.Slab eventDrawable = new Broadcast.Slab(0xFF2A303B, true);
    private final TextView eventTitle;
    private final TextView eventLeague;
    /** F1 flag state (yellow, safety car, red, chequered) as a banner under the situation row. */
    private final TextView flagBanner;

    LiveStrip(Activity a, String apiBase, String token) {
        this.a = a;
        this.apiBase = apiBase;
        this.token = token;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        strip = new FrameLayout(a);
        strip.setClipChildren(true);
        slabs = new LinearLayout(a);
        slabs.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setBackground(homeSlab);
        left.setPadding(dp(10), 0, dp(14), 0);
        homeCrest = crest();
        homeName = Broadcast.cond(a, "", 18, 0xFFFFFFFF, 700);
        homeName.setPadding(dp(6), 0, 0, 0);
        homeScore = Broadcast.cond(a, "", 32, 0xFFFFFFFF, 700);
        left.addView(homeCrest, new LinearLayout.LayoutParams(dp(28), dp(28)));
        left.addView(homeName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(homeScore);

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setBackground(awaySlab);
        right.setPadding(dp(18), 0, dp(10), 0);
        awayScore = Broadcast.cond(a, "", 32, 0xFFFFFFFF, 700);
        awayName = Broadcast.cond(a, "", 18, 0xFFFFFFFF, 700);
        awayName.setPadding(dp(6), 0, dp(6), 0);
        awayName.setGravity(Gravity.END);
        awayCrest = crest();
        right.addView(awayScore);
        right.addView(awayName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        right.addView(awayCrest, new LinearLayout.LayoutParams(dp(28), dp(28)));

        slabs.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = -dp(8);
        slabs.addView(right, rlp);
        strip.addView(slabs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        eventSlab = new LinearLayout(a);
        eventSlab.setOrientation(LinearLayout.VERTICAL);
        eventSlab.setGravity(Gravity.CENTER_VERTICAL);
        eventSlab.setPadding(dp(12), 0, dp(24), 0);
        eventSlab.setBackground(eventDrawable);
        eventTitle = Broadcast.cond(a, "", 20, 0xFFFFFFFF, 700);
        eventLeague = Broadcast.body(a, "", 12, 0xFFFFFFFF, 600);
        eventSlab.addView(eventTitle);
        eventSlab.addView(eventLeague);
        eventSlab.setVisibility(View.GONE);
        strip.addView(eventSlab, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // The moment band: team colour with a gold leading edge, off to the left until a moment
        band = new LinearLayout(a);
        band.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout bandText = new LinearLayout(a);
        bandText.setOrientation(LinearLayout.VERTICAL);
        bandText.setGravity(Gravity.CENTER_VERTICAL);
        bandText.setPadding(dp(12), 0, dp(8), 0);
        bandLabel = Broadcast.cond(a, "", 27, 0xFF111111, 700);
        bandDetail = Broadcast.body(a, "", 13, 0xFF111111, 600);
        bandDetail.setMaxLines(1);
        bandText.addView(bandLabel);
        bandText.addView(bandDetail);
        band.addView(bandText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        View edge = new View(a);
        edge.setBackgroundColor(Broadcast.GOLD);
        band.addView(edge, new LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT));
        band.setVisibility(View.GONE);
        strip.addView(band, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        // Situation row: gold box, the situation, the clock and a live dot
        LinearLayout sit = new LinearLayout(a);
        sit.setOrientation(LinearLayout.HORIZONTAL);
        sit.setGravity(Gravity.CENTER_VERTICAL);
        sit.setPadding(0, dp(8), 0, 0);
        box = Broadcast.cond(a, "", 20, Broadcast.INK, 700);
        box.setBackgroundColor(Broadcast.GOLD);
        box.setPadding(dp(8), dp(1), dp(8), dp(1));
        situation = Broadcast.body(a, "", 14, Broadcast.BODY, 600);
        situation.setMaxLines(1);
        situation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        situation.setPadding(dp(8), 0, dp(6), 0);
        dot = new View(a);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Broadcast.LIVE);
        dot.setBackground(d);
        clock = Broadcast.cond(a, "", 18, Broadcast.TEXT, 700);
        clock.setPadding(dp(6), 0, 0, 0);
        sit.addView(box);
        sit.addView(situation, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sit.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        sit.addView(clock);
        view.addView(sit);
        flagBanner = Broadcast.cond(a, "", 15, Broadcast.INK, 700);
        flagBanner.setGravity(Gravity.CENTER);
        flagBanner.setLetterSpacing(0.08f);
        flagBanner.setPadding(0, dp(3), 0, dp(3));
        flagBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(6);
        view.addView(flagBanner, flp);
        bindTeams(LiveTeams.NONE);
    }

    private ImageView crest() {
        ImageView v = new ImageView(a);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    void bindTeams(LiveTeams t) {
        teams = t;
        homeSlab.setColor(t.homeColor);
        awaySlab.setColor(t.awayColor);
        int onHome = Broadcast.on(t.homeColor);
        int onAway = Broadcast.on(t.awayColor);
        homeName.setTextColor(onHome);
        homeScore.setTextColor(onHome);
        awayName.setTextColor(onAway);
        awayScore.setTextColor(onAway);
        // Tennis: a flag in front of the name when there's no crest
        String hf = t.homeLogo == null ? Broadcast.flag(t.homeCountry) : null;
        String af = t.awayLogo == null ? Broadcast.flag(t.awayCountry) : null;
        set(homeName, hf == null ? t.homeShort : hf + " " + t.homeShort);
        set(awayName, af == null ? t.awayShort : t.awayShort + " " + af);
        slabs.setVisibility(t.event ? View.GONE : View.VISIBLE);
        eventSlab.setVisibility(t.event ? View.VISIBLE : View.GONE);
        if (t.event) {
            eventDrawable.setColor(t.homeColor);
            int on = Broadcast.on(t.homeColor);
            eventTitle.setTextColor(on);
            eventLeague.setTextColor(on);
            set(eventTitle, t.title != null ? t.title : t.league != null ? t.league : "Live");
            set(eventLeague, t.title != null && t.league != null ? t.league : "");
            eventLeague.setVisibility(t.title != null && t.league != null ? View.VISIBLE : View.GONE);
        }
        // The situation row sits on the wash: text picked by contrast with the blended colour
        int washLeft = t.known ? Broadcast.blend(t.homeColor, 0.22f) : Broadcast.BASE;
        int washRight = t.known ? Broadcast.blend(t.awayColor, 0.22f) : Broadcast.BASE;
        situation.setTextColor(Broadcast.on(washLeft) == Broadcast.INK ? Broadcast.INK : Broadcast.BODY);
        clock.setTextColor(Broadcast.on(washRight) == Broadcast.INK ? Broadcast.INK : Broadcast.TEXT);
        ImageCache.load(homeCrest, t.homeLogo, apiBase, token, dp(28));
        ImageCache.load(awayCrest, t.awayLogo, apiBase, token, dp(28));
    }

    /** The latest /live answer: scores (slide on change; held while a moment covers the strip), the situation row. */
    void bind(JSONObject live) {
        if (live == null) return;
        JSONObject sc = live.optJSONObject("score");
        String h = sc == null || sc.isNull("home") ? "" : sc.optString("home");
        String aw = sc == null || sc.isNull("away") ? "" : sc.optString("away");
        if (wiping) {
            pendingHome = h;
            pendingAway = aw;
        } else {
            score(homeScore, h);
            score(awayScore, aw);
        }
        String status = str(live, "status");
        String period = str(live, "period");
        String c = str(live, "clock");
        if (period == null && sc != null) period = str(sc, "period");
        if (c == null && sc != null) c = str(sc, "clock");
        JSONObject s = live.optJSONObject("situation");
        String dd = s == null ? null : LiveContent.downDistance(s);
        boolean post = "post".equals(status);
        String lap = str(s, "lap");
        String laps = str(s, "laps");
        String round = str(s, "round");
        String session = str(s, "session");
        String boxText = post ? "FT" : lap != null ? "Lap " + lap + (laps == null ? "" : "/" + laps) : round != null ? "R" + round : dd != null ? dd : period;
        set(box, boxText == null ? "" : boxText);
        box.setVisibility(boxText == null ? View.GONE : View.VISIBLE);
        String sum = lap != null || round != null ? session : LiveContent.summary(s, dd != null);
        set(situation, sum == null ? "" : sum);
        String clk = post ? "" : lap != null || round != null ? "LIVE" : (dd != null && period != null ? period + " " : "") + (c == null ? "LIVE" : c);
        set(clock, clk);
        // F1 flag banner
        String flag = str(s, "flag");
        if (flag == null || "green".equalsIgnoreCase(flag)) {
            flagBanner.setVisibility(View.GONE);
        } else {
            String f = flag.toLowerCase(java.util.Locale.ROOT);
            flagBanner.setText("red".equals(f) ? "Red flag" : "sc".equals(f) ? "Safety car" : "vsc".equals(f) ? "Virtual safety car" : "chequered".equals(f) ? "Chequered flag" : "yellow".equals(f) ? "Yellow flag" : f);
            flagBanner.setBackgroundColor("red".equals(f) ? 0xFFE10600 : "chequered".equals(f) ? 0xFFFFFFFF : 0xFFFFD400);
            flagBanner.setTextColor("red".equals(f) ? 0xFFFFFFFF : Broadcast.INK);
            flagBanner.setVisibility(View.VISIBLE);
        }
        dot.setVisibility(post ? View.GONE : View.VISIBLE);
    }

    private void score(TextView t, String s) {
        if (s.contentEquals(t.getText())) return;
        if (t.getText().length() == 0 || t.getHeight() == 0) {
            t.setText(s);
            return;
        }
        // Odometer feel: the old number slides up and out, the new one slides in (220 ms)
        float h = t.getHeight();
        t.animate().translationY(-h).alpha(0f).setDuration(110).withEndAction(() -> {
            t.setText(s);
            t.setTranslationY(h);
            t.animate().translationY(0).alpha(1f).setDuration(110).start();
        }).start();
    }

    /** The dot breathes only while the strip is on screen. */
    void setActive(boolean on) {
        if (on && dotAnim == null) {
            dotAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f);
            dotAnim.setDuration(1_000);
            dotAnim.setRepeatCount(ValueAnimator.INFINITE);
            dotAnim.setRepeatMode(ValueAnimator.REVERSE);
            dotAnim.start();
        } else if (!on && dotAnim != null) {
            dotAnim.cancel();
            dotAnim = null;
            dot.setAlpha(1f);
        }
    }

    /** The 2-second moment wipe in [color] (translation only); the score swaps underneath once covered. */
    void flash(String label, String detail, int color) {
        if (strip.getWidth() == 0) return;
        bandLabel.setText(label);
        bandDetail.setText(detail == null ? "" : detail);
        bandDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        int on = Broadcast.on(color);
        bandLabel.setTextColor(on);
        bandDetail.setTextColor(on);
        band.getChildAt(0).setBackgroundColor(color);
        float w = strip.getWidth();
        wiping = true;
        band.animate().cancel();
        band.setVisibility(View.VISIBLE);
        band.setTranslationX(-w * 1.02f);
        band.animate().translationX(0).setStartDelay(0).setDuration(WIPE_MS).withEndAction(() -> {
            if (pendingHome != null) score(homeScore, pendingHome);
            if (pendingAway != null) score(awayScore, pendingAway);
            pendingHome = null;
            pendingAway = null;
            band.animate().translationX(w * 1.02f).setStartDelay(HOLD_MS).setDuration(WIPE_MS).withEndAction(() -> {
                band.setVisibility(View.GONE);
                band.animate().setStartDelay(0);
                wiping = false;
            }).start();
        }).start();
    }

    private static void set(TextView t, String s) {
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
