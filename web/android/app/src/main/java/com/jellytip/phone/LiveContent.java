package com.jellytip.phone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stats · Plays · Odds · Chat for the landscape drawer and the portrait panel (plan "Live panel: pop, context,
 * performance"): split-bar stats in the team colours, leader cards with headshots, a play timeline (team dots, scoring
 * plays bold with "+3"), an odds bar with a 30-minute sparkline, and a per-sport context block from `layout` +
 * `situation` (NFL down & distance and the field, cricket batters / bowler / last six balls, basketball runs and
 * period scores, any {home, away} pair as a bar). Rows are keyed and reused: a poll rebinds the views it already has
 * (text only when it changed) and only reorders when the set of rows changed. No inflate per poll.
 */
final class LiveContent {

    private final Activity a;
    private final LinearLayout box;
    private final String apiBase;
    private final String token;
    private final Map<String, Object> holders = new HashMap<>();
    private final List<View> order = new ArrayList<>();
    private final Set<String> seenPlays = new HashSet<>();
    private boolean playsPrimed;

    LiveContent(Activity a, LinearLayout box, String apiBase, String token) {
        this.a = a;
        this.box = box;
        this.apiBase = apiBase;
        this.token = token;
    }

    void render(LiveFeed feed, String tab, LiveTeams t) {
        order.clear();
        JSONObject d = feed.last;
        if (d == null) {
            note("note:loading", feed.note == null ? "Loading live data…" : feed.note);
        } else {
            switch (tab) {
                case "Plays":
                    plays(d, t);
                    break;
                case "Odds":
                    odds(d, t);
                    break;
                case "Chat":
                    chat(d);
                    break;
                default:
                    stats(d, t);
            }
        }
        sync();
    }

    /** Children in [order]: untouched when they already are; else re-added (the same views, never new ones). */
    private void sync() {
        boolean same = box.getChildCount() == order.size();
        for (int i = 0; same && i < order.size(); i++) if (box.getChildAt(i) != order.get(i)) same = false;
        if (same) return;
        box.removeAllViews();
        for (View v : order) {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
            box.addView(v);
        }
    }

    // ---- Stats ---------------------------------------------------------------------------------------------------

    private void stats(JSONObject d, LiveTeams t) {
        JSONObject situation = d.optJSONObject("situation");
        if (situation != null) context(str(d, "layout"), situation, t);
        JSONObject wp = d.optJSONObject("winProb");
        if (wp != null && wp.has("home") && !wp.isNull("home")) {
            double p = wp.optDouble("home");
            stat("stat:winprob", "Win probability", LiveRender.pct(p), LiveRender.pct(1 - p), t);
        }
        JSONArray stats = d.optJSONArray("stats");
        for (int i = 0; stats != null && i < Math.min(stats.length(), 8); i++) {
            JSONObject s = stats.optJSONObject(i);
            if (s == null || str(s, "label") == null) continue;
            stat("stat:" + str(s, "label"), str(s, "label"), s.optString("home"), s.optString("away"), t);
        }
        JSONArray leaders = d.optJSONArray("leaders");
        if (leaders != null && leaders.length() > 0) {
            label("label:leaders", "Leaders");
            for (int i = 0; i < Math.min(leaders.length(), 4); i++) {
                JSONObject l = leaders.optJSONObject(i);
                if (l != null && str(l, "name") != null) leader(l, t);
                else if (l == null && !leaders.isNull(i)) note("leader:" + i, leaders.optString(i));
            }
        }
        if (order.isEmpty()) note("note:nostats", "No stats for this match yet.");
    }

    private void stat(String key, String label, String home, String away, LiveTeams t) {
        StatRow r = (StatRow) holders.get(key);
        if (r == null) {
            r = new StatRow();
            holders.put(key, r);
        }
        r.bind(label, home, away, t);
        order.add(r.root);
    }

    private final class StatRow {
        final LinearLayout root = new LinearLayout(a);
        final TextView home = LiveRender.text(a, "", 12, Color.WHITE);
        final TextView label = LiveRender.text(a, "", 11, 0xB3F2F2F0);
        final TextView away = LiveRender.text(a, "", 12, Color.WHITE);
        final SplitBar bar = new SplitBar(a);

        StatRow() {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(3), 0, dp(3));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            home.setTypeface(home.getTypeface(), android.graphics.Typeface.BOLD);
            away.setTypeface(away.getTypeface(), android.graphics.Typeface.BOLD);
            away.setGravity(Gravity.END);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            row.addView(home, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(away, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(row);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
            blp.topMargin = dp(3);
            root.addView(bar, blp);
        }

        void bind(String l, String h, String aw, LiveTeams t) {
            setText(label, l);
            setText(home, h);
            setText(away, aw);
            Double hv = num(h);
            Double av = num(aw);
            if (hv != null && av != null && hv >= 0 && av >= 0 && hv + av > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.set(new float[] {(float) (double) hv, (float) (double) av}, new int[] {t.side("home"), t.side("away")});
            } else {
                bar.setVisibility(View.GONE);
            }
        }
    }

    private void leader(JSONObject l, LiveTeams t) {
        String key = "leader:" + str(l, "name");
        LeaderRow r = (LeaderRow) holders.get(key);
        if (r == null) {
            r = new LeaderRow();
            holders.put(key, r);
        }
        r.bind(l, t);
        order.add(r.root);
    }

    private final class LeaderRow {
        final LinearLayout root = new LinearLayout(a);
        final ImageView img = new ImageView(a);
        final TextView name = LiveRender.text(a, "", 12, Color.WHITE);
        final TextView line = LiveRender.text(a, "", 11, 0xCCF2F2F0);
        final GradientDrawable bg = new GradientDrawable();

        LeaderRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(6), dp(6), dp(6), dp(6));
            bg.setCornerRadius(dp(8));
            root.setBackground(bg);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x33FFFFFF);
            img.setBackground(circle);
            root.addView(img, new LinearLayout.LayoutParams(dp(36), dp(36)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(8), 0, 0, 0);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            name.setMaxLines(1);
            line.setMaxLines(1);
            col.addView(name);
            col.addView(line);
            root.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            root.setLayoutParams(lp);
        }

        void bind(JSONObject l, LiveTeams t) {
            setText(name, str(l, "name"));
            String ln = str(l, "line") != null ? str(l, "line") : str(l, "stat");
            setText(line, ln == null ? "" : ln);
            bg.setColor((t.side(str(l, "team")) & 0x00FFFFFF) | 0x38000000);
            ImageCache.load(img, str(l, "headshot"), apiBase, token, dp(36));
        }
    }

    // ---- Per-sport context ---------------------------------------------------------------------------------------

    private void context(String layout, JSONObject s, LiveTeams t) {
        // NFL: down & distance, the field with the ball, red zone, drive
        String dd = downDistance(s);
        Double fieldPos = fieldPos(s);
        if (dd != null || fieldPos != null) {
            String yl = str(s, "yardLine") != null ? str(s, "yardLine") : str(s, "possessionText");
            boolean red = s.optBoolean("redZone", false) || s.optBoolean("isRedZone", false);
            TextView h = text("ctx:dd", (dd == null ? "" : dd) + (yl == null ? "" : "   " + yl) + (red ? "   RED ZONE" : ""), 15, Color.WHITE, true);
            h.setTextColor(red ? 0xFFFF6B6B : Color.WHITE);
            if (fieldPos != null) {
                FieldBar f = (FieldBar) holder("ctx:field", () -> new FieldBar(a));
                f.set(fieldPos.floatValue(), t.side(str(s, "possession")), "away".equals(str(s, "possession")));
                order.add(f);
            }
            JSONObject drive = s.optJSONObject("drive");
            String dr = drive != null ? str(drive, "summary") : str(s, "drive");
            if (dr != null) note("ctx:drive", "Drive: " + dr);
        }
        // Cricket: batters at the crease, the bowler, RR v RRR, the last six balls
        JSONArray batters = s.optJSONArray("batters");
        for (int i = 0; batters != null && i < Math.min(batters.length(), 2); i++) {
            JSONObject b = batters.optJSONObject(i);
            if (b == null || str(b, "name") == null) continue;
            boolean strike = b.optBoolean("onStrike", false) || b.optBoolean("striker", false);
            String runs = str(b, "runs");
            String balls = str(b, "balls");
            text("ctx:bat:" + i, str(b, "name") + (strike ? " *" : "") + "   " + (runs == null ? "" : runs) + (balls == null ? "" : " (" + balls + ")"), 13, Color.WHITE, strike);
        }
        JSONObject bowler = s.optJSONObject("bowler");
        if (bowler != null && str(bowler, "name") != null) {
            String fig = str(bowler, "figures");
            String overs = str(bowler, "overs");
            note("ctx:bowler", "Bowling: " + str(bowler, "name") + (fig == null ? "" : "  " + fig) + (overs == null ? "" : " (" + overs + ")"));
        }
        Double rr = dbl(s, "rr") != null ? dbl(s, "rr") : dbl(s, "runRate");
        Double rrr = dbl(s, "rrr") != null ? dbl(s, "rrr") : dbl(s, "requiredRunRate");
        if (rr != null || rrr != null) {
            String rates = (rr == null ? "" : "RR " + fmt(rr)) + (rr != null && rrr != null ? "  v  " : "") + (rrr == null ? "" : "RRR " + fmt(rrr));
            text("ctx:rates", rates, 12, 0xFFFFC94A, true);
        }
        JSONArray balls = s.optJSONArray("lastBalls") != null ? s.optJSONArray("lastBalls") : s.optJSONArray("lastSix");
        if (balls != null && balls.length() > 0) {
            BallsRow r = (BallsRow) holders.get("ctx:balls");
            if (r == null) {
                r = new BallsRow();
                holders.put("ctx:balls", r);
            }
            r.bind(balls);
            order.add(r.root);
        }
        // Basketball: the run, period scores
        JSONObject run = s.optJSONObject("run");
        String runText = run != null ? str(run, "text") : str(s, "run");
        if (runText != null) {
            TextView c = text("ctx:run", runText, 12, Color.WHITE, true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(run != null && str(run, "team") != null ? t.side(str(run, "team")) : 0xFFB3261E);
            c.setBackground(bg);
            c.setPadding(dp(8), dp(2), dp(8), dp(2));
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp == null || lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) c.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        JSONArray periods = s.optJSONArray("periods") != null ? s.optJSONArray("periods") : s.optJSONArray("quarters") != null ? s.optJSONArray("quarters") : s.optJSONArray("linescores");
        if (periods != null && periods.length() > 0) {
            String prefix = "basketball".equals(layout) || "nfl".equals(layout) ? "Q" : "P";
            StringBuilder head = new StringBuilder(String.format(Locale.US, "%-6s", ""));
            StringBuilder hr = new StringBuilder(String.format(Locale.US, "%-6s", cut(t.homeShort)));
            StringBuilder ar = new StringBuilder(String.format(Locale.US, "%-6s", cut(t.awayShort)));
            for (int i = 0; i < Math.min(periods.length(), 6); i++) {
                JSONObject p = periods.optJSONObject(i);
                if (p == null) continue;
                head.append(String.format(Locale.US, "%4s", prefix.equals("Q") && i >= 4 ? "OT" : prefix + (i + 1)));
                hr.append(String.format(Locale.US, "%4s", p.optString("home")));
                ar.append(String.format(Locale.US, "%4s", p.optString("away")));
            }
            TextView g = text("ctx:periods", head + "\n" + hr + "\n" + ar, 11, Color.WHITE, false);
            g.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        // Every {home, away} pair (possession, territory, fouls, shots, xG, cards, penalties…)
        Iterator<String> keys = s.keys();
        int pairs = 0;
        while (keys.hasNext() && pairs < 6) {
            String k = keys.next();
            if (CONSUMED.contains(k)) continue;
            JSONObject v = s.optJSONObject(k);
            if (v == null || !v.has("home") || !v.has("away")) continue;
            JSONObject h = v.optJSONObject("home");
            JSONObject aw = v.optJSONObject("away");
            if (h != null && aw != null) {
                Iterator<String> subs = h.keys();
                while (subs.hasNext() && pairs < 6) {
                    String sub = subs.next();
                    if (!aw.has(sub)) continue;
                    stat("pair:" + k + "." + sub, pairLabel(sub, k), h.optString(sub), aw.optString(sub), t);
                    pairs++;
                }
            } else {
                stat("pair:" + k, pairLabel(k, null), v.optString("home"), v.optString("away"), t);
                pairs++;
            }
        }
    }

    private static final Set<String> CONSUMED = new HashSet<>(java.util.Arrays.asList("batters", "bowler", "lastBalls", "lastSix", "run", "drive", "periods", "quarters", "linescores"));

    /** One line under the strip: "3rd & 7 · KC 35 · RED ZONE", "Need 42 off 30", "RRR 8.4", "12-2 run". */
    static String summary(JSONObject s) {
        if (s == null) return null;
        List<String> p = new ArrayList<>();
        String dd = downDistance(s);
        if (dd != null) p.add(dd);
        String yl = str(s, "yardLine") != null ? str(s, "yardLine") : str(s, "possessionText");
        if (yl != null) p.add(yl);
        if (s.optBoolean("redZone", false) || s.optBoolean("isRedZone", false)) p.add("RED ZONE");
        String eq = str(s, "equation") != null ? str(s, "equation") : str(s, "need");
        if (eq != null) p.add(eq);
        Double rrr = dbl(s, "rrr") != null ? dbl(s, "rrr") : dbl(s, "requiredRunRate");
        if (eq == null && rrr != null) p.add("RRR " + fmt(rrr));
        JSONObject run = s.optJSONObject("run");
        String runText = run != null ? str(run, "text") : str(s, "run");
        if (runText != null) p.add(runText);
        return p.isEmpty() ? null : android.text.TextUtils.join("  ·  ", p);
    }

    private static String downDistance(JSONObject s) {
        String dd = str(s, "downDistance") != null ? str(s, "downDistance") : str(s, "downDistanceText");
        if (dd != null) return dd;
        int down = s.optInt("down", 0);
        if (down < 1 || down > 4) return null;
        String[] ord = {"1st", "2nd", "3rd", "4th"};
        String dist = str(s, "distance");
        return ord[down - 1] + (dist == null ? "" : " & " + dist);
    }

    /** 0 = the home end zone (left), 1 = the away one. */
    private static Double fieldPos(JSONObject s) {
        Double f = dbl(s, "fieldPos");
        if (f != null) return Math.max(0, Math.min(1, f / 100));
        Double y = dbl(s, "yardsToEndzone");
        if (y == null) return null;
        double v = "away".equals(str(s, "possession")) ? y / 100 : (100 - y) / 100;
        return Math.max(0, Math.min(1, v));
    }

    private static String pairLabel(String key, String parent) {
        switch (key) {
            case "possession":
                return "Possession";
            case "territory":
                return "Territory";
            case "fouls":
            case "teamFouls":
                return "Team fouls";
            case "shotsOnTarget":
                return "On target";
            case "xg":
            case "xG":
                return "xG";
            case "yellow":
                return "Yellow cards";
            case "red":
                return "Red cards";
            default:
                String w = key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
                w = w.isEmpty() ? key : Character.toUpperCase(w.charAt(0)) + w.substring(1);
                return "cards".equals(parent) ? w + " cards" : w;
        }
    }

    private final class BallsRow {
        final LinearLayout root = new LinearLayout(a);
        final TextView[] chips = new TextView[6];

        BallsRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(0, dp(2), 0, dp(4));
            for (int i = 0; i < chips.length; i++) {
                TextView c = LiveRender.text(a, "", 10, Color.WHITE);
                c.setGravity(Gravity.CENTER);
                c.setTypeface(c.getTypeface(), android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
                lp.rightMargin = dp(4);
                root.addView(c, lp);
                chips[i] = c;
            }
        }

        void bind(JSONArray balls) {
            int start = Math.max(0, balls.length() - 6);
            for (int i = 0; i < chips.length; i++) {
                int j = start + i;
                TextView c = chips[i];
                if (j >= balls.length()) {
                    c.setVisibility(View.GONE);
                    continue;
                }
                c.setVisibility(View.VISIBLE);
                String b = balls.optString(j).toUpperCase(Locale.ROOT);
                setText(c, "0".equals(b) || ".".equals(b) ? "·" : b.length() > 2 ? b.substring(0, 2) : b);
                int bg = "W".equals(b) ? 0xFFD64545 : "4".equals(b) ? 0xFF2F7DE1 : "6".equals(b) ? 0xFF8E44E0 : "0".equals(b) || ".".equals(b) ? 0x1FFFFFFF : 0x40FFFFFF;
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(bg);
                c.setBackground(d);
            }
        }
    }

    // ---- Plays ---------------------------------------------------------------------------------------------------

    private void plays(JSONObject d, LiveTeams t) {
        JSONArray plays = d.optJSONArray("plays");
        if (plays == null || plays.length() == 0) {
            note("note:noplays", "No plays yet.");
            return;
        }
        boolean first = !playsPrimed;
        playsPrimed = true;
        for (int i = 0; i < Math.min(plays.length(), 12); i++) {
            JSONObject p = plays.optJSONObject(i);
            if (p == null || str(p, "text") == null) continue;
            String key = "play:" + (str(p, "id") != null ? str(p, "id") : str(p, "period") + "|" + str(p, "clock") + "|" + str(p, "text"));
            PlayRow r = (PlayRow) holders.get(key);
            if (r == null) {
                r = new PlayRow();
                holders.put(key, r);
            }
            r.bind(p, t);
            order.add(r.root);
            // A new play slides down with a short fade (not on the first render)
            if (seenPlays.add(key) && !first) {
                r.root.setAlpha(0f);
                r.root.setTranslationY(-dp(16));
                r.root.animate().alpha(1f).translationY(0).setDuration(280).start();
            }
        }
    }

    private final class PlayRow {
        final LinearLayout root = new LinearLayout(a);
        final View dot = new View(a);
        final GradientDrawable dotBg = new GradientDrawable();
        final TextView meta = LiveRender.text(a, "", 10, 0x8CF2F2F0);
        final TextView text = LiveRender.text(a, "", 12, 0xD9F2F2F0);
        final TextView points = LiveRender.text(a, "", 11, Color.WHITE);
        final GradientDrawable pointsBg = new GradientDrawable();

        PlayRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(0, dp(3), 0, dp(3));
            root.setBackground(new TimelineLine(dp(5), dp(2)));
            dotBg.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
            dlp.topMargin = dp(4);
            dlp.rightMargin = dp(8);
            root.addView(dot, dlp);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            text.setMaxLines(2);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(meta);
            col.addView(text);
            root.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            pointsBg.setCornerRadius(dp(10));
            points.setBackground(pointsBg);
            points.setTypeface(points.getTypeface(), android.graphics.Typeface.BOLD);
            points.setPadding(dp(8), dp(2), dp(8), dp(2));
            root.addView(points);
        }

        void bind(JSONObject p, LiveTeams t) {
            boolean scoring = p.optBoolean("scoring", false);
            int color = t.side(str(p, "team"));
            dotBg.setColor(color);
            String m = (str(p, "period") == null ? "" : str(p, "period")) + (str(p, "clock") == null ? "" : " " + str(p, "clock"));
            setText(meta, m.trim());
            meta.setVisibility(m.trim().isEmpty() ? View.GONE : View.VISIBLE);
            setText(text, str(p, "text"));
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scoring ? 14 : 12);
            text.setTextColor(scoring ? Color.WHITE : 0xD9F2F2F0);
            text.setTypeface(null, scoring ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            int pts = p.has("points") ? p.optInt("points", 0) : p.optInt("delta", 0);
            if (scoring && pts > 0) {
                setText(points, "+" + pts);
                pointsBg.setColor(color);
                points.setVisibility(View.VISIBLE);
            } else {
                points.setVisibility(View.GONE);
            }
        }
    }

    /** The timeline's vertical line at the dots' x (drawn behind each play row). */
    private static final class TimelineLine extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();
        private final float x;

        TimelineLine(int x, int width) {
            this.x = x;
            paint.setColor(0x26FFFFFF);
            paint.setStrokeWidth(width);
        }

        @Override
        public void draw(Canvas c) {
            c.drawLine(x, getBounds().top, x, getBounds().bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ---- Odds ----------------------------------------------------------------------------------------------------

    private void odds(JSONObject d, LiveTeams t) {
        JSONObject m = d.optJSONObject("market");
        if (m == null || (m.isNull("home") && m.isNull("away")) || (!m.has("home") && !m.has("away"))) {
            note("note:noodds", "No market for this match.");
            return;
        }
        double h = m.optDouble("home", 0);
        double aw = m.optDouble("away", 0);
        double dr = m.has("draw") && !m.isNull("draw") ? m.optDouble("draw", 0) : 0;
        SplitBar bar = (SplitBar) holder("odds:bar", () -> {
            SplitBar b = new SplitBar(a);
            b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));
            return b;
        });
        bar.set(dr > 0 ? new float[] {(float) h, (float) dr, (float) aw} : new float[] {(float) h, (float) aw}, dr > 0 ? new int[] {t.side("home"), 0xFF8A90A0, t.side("away")} : new int[] {t.side("home"), t.side("away")});
        order.add(bar);
        String labels = t.homeShort + " " + LiveRender.pct(h) + (dr > 0 ? "   ·   Draw " + LiveRender.pct(dr) : "") + "   ·   " + LiveRender.pct(aw) + " " + t.awayShort;
        text("odds:labels", labels, 13, Color.WHITE, true);
        JSONArray series = m.optJSONArray("series");
        if (series != null && series.length() >= 2) {
            label("odds:sparklabel", "Favourite · last 30 min");
            Sparkline s = (Sparkline) holder("odds:spark", () -> {
                Sparkline sp = new Sparkline(a);
                sp.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
                return sp;
            });
            s.set(series, h >= aw ? t.side("home") : t.side("away"));
            order.add(s);
        }
        List<String> meta = new ArrayList<>();
        if (str(m, "source") != null) meta.add(str(m, "source"));
        if (m.has("volume") && !m.isNull("volume")) meta.add(money(m.optDouble("volume")) + " traded");
        if (!meta.isEmpty()) note("odds:meta", android.text.TextUtils.join(", ", meta));
        if (str(m, "question") != null) note("odds:q", str(m, "question"));
    }

    // ---- Chat ----------------------------------------------------------------------------------------------------

    private void chat(JSONObject d) {
        JSONObject chat = d.optJSONObject("chat");
        JSONArray comments = chat == null ? null : chat.optJSONArray("comments");
        if (comments == null || comments.length() == 0) {
            note("note:nochat", "No match thread comments.");
            return;
        }
        for (int i = 0; i < Math.min(comments.length(), 25); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c == null) continue;
            String author = str(c, "author");
            String line = (author == null ? "" : author + ": ") + c.optString("text");
            TextView v = text("chat:" + line, line, 13, 0xDDF2F2F0, false);
            v.setPadding(0, dp(4), 0, dp(4));
        }
    }

    // ---- Views ---------------------------------------------------------------------------------------------------

    private interface Factory {
        View make();
    }

    private View holder(String key, Factory f) {
        Object o = holders.get(key);
        if (o == null) {
            o = f.make();
            holders.put(key, o);
        }
        return (View) o;
    }

    private TextView text(String key, String s, int sp, int color, boolean bold) {
        TextView v = (TextView) holder(key, () -> LiveRender.text(a, "", sp, color));
        setText(v, s);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        order.add(v);
        return v;
    }

    private void note(String key, String s) {
        TextView v = (TextView) holder(key, () -> LiveRender.muted(a, ""));
        setText(v, s);
        order.add(v);
    }

    private void label(String key, String s) {
        TextView v = (TextView) holder(key, () -> {
            TextView l = LiveRender.text(a, "", 10, 0x8CF2F2F0);
            l.setLetterSpacing(0.1f);
            l.setPadding(0, dp(10), 0, dp(2));
            return l;
        });
        setText(v, s.toUpperCase(Locale.ROOT));
        order.add(v);
    }

    private static void setText(TextView t, String s) {
        if (s == null) s = "";
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    /** Segments in proportion to [values], each in its colour, small gaps, rounded ends. */
    static final class SplitBar extends View {
        private float[] values = new float[0];
        private int[] colors = new int[0];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        SplitBar(Context c) {
            super(c);
        }

        void set(float[] v, int[] c) {
            if (java.util.Arrays.equals(v, values) && java.util.Arrays.equals(c, colors)) return;
            values = v;
            colors = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float sum = 0;
            for (float v : values) sum += Math.max(0, v);
            if (sum <= 0) return;
            float w = getWidth();
            float h = getHeight();
            float gap = h > 8 ? 2 : 1.5f;
            float x = 0;
            for (int i = 0; i < values.length; i++) {
                float seg = w * Math.max(0, values[i]) / sum;
                if (seg <= 0) continue;
                paint.setColor(colors[Math.min(i, colors.length - 1)]);
                r.set(x + (i > 0 ? gap / 2 : 0), 0, x + seg - (i < values.length - 1 ? gap / 2 : 0), h);
                canvas.drawRoundRect(r, h / 2, h / 2, paint);
                x += seg;
            }
        }
    }

    /** The field: end zones, 10-yard lines, the ball in the possessing team's colour with a tick towards its target. */
    static final class FieldBar extends View {
        private float pos = -1;
        private int color;
        private boolean leftward;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FieldBar(Context c) {
            super(c);
            setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(16 * c.getResources().getDisplayMetrics().density)));
        }

        void set(float p, int c, boolean towardsLeft) {
            if (p == pos && c == color && towardsLeft == leftward) return;
            pos = p;
            color = c;
            leftward = towardsLeft;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF1F5E33);
            canvas.drawRect(0, 0, w, h, paint);
            float ez = w * 0.08f;
            paint.setColor(0xFF17472A);
            canvas.drawRect(0, 0, ez, h, paint);
            canvas.drawRect(w - ez, 0, w, h, paint);
            float field = w - 2 * ez;
            paint.setColor(0x40FFFFFF);
            paint.setStrokeWidth(1);
            for (int i = 1; i <= 9; i++) {
                float x = ez + field * i / 10f;
                canvas.drawLine(x, 0, x, h, paint);
            }
            if (pos < 0) return;
            float x = ez + field * pos;
            paint.setColor(color);
            canvas.drawCircle(x, h / 2, h * 0.38f, paint);
            paint.setStrokeWidth(h * 0.15f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x, h / 2, x + (leftward ? -1 : 1) * h * 0.9f, h / 2, paint);
        }
    }

    /** The favourite's probability over time; a faint 50% line. */
    static final class Sparkline extends View {
        private long[] t = new long[0];
        private double[] p = new double[0];
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Sparkline(Context c) {
            super(c);
        }

        void set(JSONArray series, int c) {
            List<long[]> ts = new ArrayList<>();
            List<Double> ps = new ArrayList<>();
            for (int i = 0; i < series.length(); i++) {
                JSONObject o = series.optJSONObject(i);
                if (o == null || o.isNull("p")) continue;
                long when = PlayerPlugin.isoMs(o.optString("t"));
                if (when <= 0) continue;
                ts.add(new long[] {when});
                ps.add(o.optDouble("p"));
            }
            long[] nt = new long[ts.size()];
            double[] np = new double[ps.size()];
            for (int i = 0; i < nt.length; i++) {
                nt[i] = ts.get(i)[0];
                np[i] = ps.get(i);
            }
            if (java.util.Arrays.equals(nt, t) && java.util.Arrays.equals(np, p) && c == color) return;
            t = nt;
            p = np;
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (t.length < 2) return;
            float w = getWidth();
            float h = getHeight();
            double lo = 0.4;
            double hi = 0.6;
            for (double v : p) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            long t0 = t[0];
            long t1 = t[0];
            for (long v : t) {
                t0 = Math.min(t0, v);
                t1 = Math.max(t1, v);
            }
            float span = Math.max(1, t1 - t0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            paint.setColor(0x26FFFFFF);
            float mid = (float) (h * (1 - (0.5 - lo) / (hi - lo)));
            canvas.drawLine(0, mid, w, mid, paint);
            path.reset();
            for (int i = 0; i < t.length; i++) {
                float x = w * (t[i] - t0) / span;
                float y = (float) (h * (1 - (p[i] - lo) / (hi - lo)));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setColor(color);
            paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(path, paint);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String str(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static Double dbl(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        double v = o.optDouble(k);
        return Double.isNaN(v) ? null : v;
    }

    private static final java.util.regex.Pattern NUM = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static Double num(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = NUM.matcher(s);
        return m.find() ? Double.valueOf(m.group()) : null;
    }

    private static String fmt(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 5 ? s.substring(0, 5) : s;
    }

    private static String money(double v) {
        if (v >= 1_000_000) return String.format(Locale.US, "$%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "$%.0fK", v / 1_000);
        return String.format(Locale.US, "$%.0f", v);
    }

    private int dp(int v) {
        return LiveRender.dp(a, v);
    }
}
