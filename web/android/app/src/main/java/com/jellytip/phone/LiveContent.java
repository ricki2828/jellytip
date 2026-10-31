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
        // RedZone: the multi-game scoreboard instead
        JSONArray games = d.optJSONArray("games");
        if ("multi".equals(str(d, "layout")) || (games != null && games.length() > 0)) {
            for (int i = 0; games != null && i < Math.min(games.length(), 12); i++) {
                JSONObject g = games.optJSONObject(i);
                if (g == null || str(g, "matchId") == null) continue;
                String key = "game:" + str(g, "matchId");
                GameRow r = (GameRow) holders.get(key);
                if (r == null) {
                    r = new GameRow();
                    holders.put(key, r);
                }
                r.bind(g);
                order.add(r.root);
            }
            if (order.isEmpty()) note("note:nogames", "No games on right now.");
            return;
        }
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
        final TextView home = Broadcast.cond(a, "", 16, Broadcast.TEXT, 700);
        final TextView label = Broadcast.cond(a, "", 12, Broadcast.SECONDARY, 600);
        final TextView away = Broadcast.cond(a, "", 16, Broadcast.TEXT, 700);
        final SplitBar bar = new SplitBar(a);

        StatRow() {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(3), 0, dp(3));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.BOTTOM);
            away.setGravity(Gravity.END);
            label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.06f);
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
        final TextView name = Broadcast.cond(a, "", 15, Broadcast.TEXT, 700);
        final TextView line = Broadcast.body(a, "", 12.5f, Broadcast.BODY, 500);
        final GradientDrawable ring = new GradientDrawable();

        LeaderRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, dp(4), 0, dp(4));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            ring.setShape(GradientDrawable.OVAL);
            img.setBackground(ring);
            img.setPadding(dp(2), dp(2), dp(2), dp(2));
            root.addView(img, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(8), 0, 0, 0);
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
            int c = t.side(str(l, "team"));
            ring.setColor((c & 0x00FFFFFF) | 0x59000000);
            ring.setStroke(dp(2), c);
            ImageCache.load(img, str(l, "headshot"), apiBase, token, dp(32));
        }
    }

    // ---- Per-sport context ---------------------------------------------------------------------------------------

    private void context(String layout, JSONObject s, LiveTeams t) {
        // F1 / tennis / golf (plan "Live coverage round 2")
        JSONArray standings = s.optJSONArray("standings");
        if (standings != null && standings.length() > 0) timingTower(standings, s);
        JSONArray sets = s.optJSONArray("sets");
        if ((sets != null && sets.length() > 0) || s.optJSONObject("game") != null) {
            tennisRow("tn:home", "home", sets, s, t.homeShort, t.homeCountry);
            tennisRow("tn:away", "away", sets, s, t.awayShort, t.awayCountry);
        }
        // A tennis tournament: its matches (on court first), each a compact set grid under its round and court
        JSONArray matches = s.optJSONArray("matches");
        for (int i = 0; matches != null && i < Math.min(matches.length(), 6); i++) {
            JSONObject mt = matches.optJSONObject(i);
            if (mt == null || str(mt, "id") == null) continue;
            String id = str(mt, "id");
            String status = str(mt, "status");
            List<String> head = new ArrayList<>();
            if (str(mt, "round") != null) head.add(str(mt, "round"));
            if (str(mt, "court") != null) head.add(str(mt, "court"));
            if (status != null && !"in".equals(status)) head.add("post".equals(status) ? "Final" : status);
            note("tm:" + id + ":head", android.text.TextUtils.join(" · ", head));
            JSONArray ms = mt.optJSONArray("sets");
            tennisRow("tm:" + id + ":home", "home", ms, mt, playerName(mt, "home"), playerCountry(mt, "home"));
            tennisRow("tm:" + id + ":away", "away", ms, mt, playerName(mt, "away"), playerCountry(mt, "away"));
        }
        JSONArray board = s.optJSONArray("leaderboard");
        JSONArray favs = s.optJSONArray("favourites");
        if ((board != null && board.length() > 0) || (favs != null && favs.length() > 0)) leaderboard(board, favs);
        // NFL: down & distance, the field with the ball, red zone, drive
        String dd = downDistance(s);
        Double fieldPos = fieldPos(s);
        if (dd != null || fieldPos != null) {
            String yl = str(s, "yardLine") != null ? str(s, "yardLine") : str(s, "possessionText");
            // Down & distance sits in the strip's gold box; here the field: end zones, the ball, the line to gain
            boolean red = s.optBoolean("redZone", false) || s.optBoolean("isRedZone", false);
            if (red) text("ctx:red", "Red zone", 13, 0xFFFF6B6B, true);
            if (fieldPos != null) {
                FieldBar f = (FieldBar) holder("ctx:field", () -> new FieldBar(a));
                int dist = -1;
                if (dd != null && dd.contains("&")) {
                    try {
                        dist = Integer.parseInt(dd.substring(dd.indexOf('&') + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // "Goal" etc.
                    }
                }
                f.set(fieldPos.floatValue(), t.side("home"), t.side("away"), "away".equals(str(s, "possession")), dist);
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
            // Mockup A: a dark bar with the running team's colour on the left edge
            TextView c = text("ctx:run", runText, 14, Color.WHITE, true);
            int bar = run != null && str(run, "team") != null ? t.side(str(run, "team")) : Broadcast.GOLD;
            android.graphics.drawable.LayerDrawable bg =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {new android.graphics.drawable.ColorDrawable(Broadcast.CHIP), new android.graphics.drawable.ColorDrawable(bar)});
            bg.setLayerWidth(1, dp(7));
            bg.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
            c.setBackground(bg);
            c.setPadding(dp(15), dp(4), dp(8), dp(4));
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

    private static final Set<String> CONSUMED =
        new HashSet<>(java.util.Arrays.asList("batters", "bowler", "lastBalls", "lastSix", "run", "drive", "periods", "quarters", "linescores", "standings", "sets", "game", "leaderboard", "favourites", "stats", "fastestLap", "matches"));

    // ---- RedZone: one compact slab row per game ---------------------------------------------------------------------

    private final class GameRow {
        final LinearLayout root = new LinearLayout(a);
        final Broadcast.Slab homeSlab = new Broadcast.Slab(0xFF2A303B, true);
        final Broadcast.Slab awaySlab = new Broadcast.Slab(0xFF1C212A, false);
        final TextView homeName = Broadcast.cond(a, "", 14, Color.WHITE, 700);
        final TextView homeScore = Broadcast.cond(a, "", 17, Color.WHITE, 700);
        final TextView awayName = Broadcast.cond(a, "", 14, Color.WHITE, 700);
        final TextView awayScore = Broadcast.cond(a, "", 17, Color.WHITE, 700);
        final TextView clock = Broadcast.cond(a, "", 13, Broadcast.SECONDARY, 600);
        final TextView red = Broadcast.cond(a, "Red zone", 12, Color.WHITE, 700);
        final TextView dd = Broadcast.cond(a, "", 12, Broadcast.INK, 700);
        final TextView ball = Broadcast.body(a, "", 12, Broadcast.BODY, 500);
        final LinearLayout extra = new LinearLayout(a);

        GameRow() {
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(3), 0, dp(3));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setBackground(homeSlab);
            l.setPadding(dp(6), 0, dp(10), 0);
            l.addView(homeName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            l.addView(homeScore);
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(awaySlab);
            r.setPadding(dp(10), 0, dp(6), 0);
            r.addView(awayScore);
            awayName.setGravity(Gravity.END);
            r.addView(awayName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(l, new LinearLayout.LayoutParams(0, dp(28), 1f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            rlp.leftMargin = -dp(4);
            row.addView(r, rlp);
            clock.setGravity(Gravity.END);
            row.addView(clock, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(row);
            extra.setOrientation(LinearLayout.HORIZONTAL);
            extra.setGravity(Gravity.CENTER_VERTICAL);
            extra.setPadding(0, dp(3), 0, 0);
            red.setBackgroundColor(0xFFD64545);
            red.setPadding(dp(5), 0, dp(5), 0);
            dd.setBackgroundColor(Broadcast.GOLD);
            dd.setPadding(dp(5), 0, dp(5), 0);
            ball.setPadding(dp(6), 0, 0, 0);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.rightMargin = dp(6);
            extra.addView(red, gap);
            extra.addView(dd);
            extra.addView(ball);
            root.addView(extra);
        }

        void bind(JSONObject g) {
            JSONObject h = g.optJSONObject("home");
            JSONObject aw = g.optJSONObject("away");
            int hc = MatchStyle.color(h == null ? null : str(h, "color"), 0xFF2A303B);
            int ac = MatchStyle.color(aw == null ? null : str(aw, "color"), 0xFF1C212A);
            if (Broadcast.deltaE(hc, ac) < 20) ac = MatchStyle.color(aw == null ? null : str(aw, "altColor"), 0xFF1C212A);
            homeSlab.setColor(hc);
            awaySlab.setColor(ac);
            int onH = Broadcast.on(hc);
            int onA = Broadcast.on(ac);
            homeName.setTextColor(onH);
            homeScore.setTextColor(onH);
            awayName.setTextColor(onA);
            awayScore.setTextColor(onA);
            setText(homeName, teamLabel(g, h, "home"));
            setText(awayName, teamLabel(g, aw, "away"));
            JSONObject sc = g.optJSONObject("score");
            setText(homeScore, sc == null ? "" : sc.optString("home"));
            setText(awayScore, sc == null ? "" : sc.optString("away"));
            String period = str(g, "period") != null ? str(g, "period") : sc == null ? null : str(sc, "period");
            String c = str(g, "clock") != null ? str(g, "clock") : sc == null ? null : str(sc, "clock");
            String status = str(g, "status");
            if ("pre".equals(status)) {
                long ms = PlayerPlugin.isoMs(str(g, "startsAt"));
                setText(clock, ms > 0 ? new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(ms)) : "Soon");
            } else if ("post".equals(status)) {
                setText(clock, "Final");
            } else {
                setText(clock, ((period == null ? "" : period + " ") + (c == null ? "" : c)).trim());
            }
            JSONObject sit = g.optJSONObject("situation");
            boolean rz = g.optBoolean("redZone", false) || (sit != null && (sit.optBoolean("redZone", false) || sit.optBoolean("isRedZone", false)));
            String d = sit == null ? null : downDistance(sit);
            String yl = sit == null ? null : str(sit, "yardLine") != null ? str(sit, "yardLine") : str(sit, "possessionText");
            red.setVisibility(rz ? View.VISIBLE : View.GONE);
            setText(dd, d == null ? "" : d);
            dd.setVisibility(d == null ? View.GONE : View.VISIBLE);
            String last = sit == null || !(status == null || "in".equals(status)) ? null : str(sit, "lastPlay");
            String spot = yl == null ? null : "Ball on " + yl;
            String line = spot != null && last != null ? spot + " · " + last : spot != null ? spot : last;
            setText(ball, line == null ? "" : line);
            ball.setMaxLines(1);
            ball.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ball.setVisibility(line == null ? View.GONE : View.VISIBLE);
            extra.setVisibility(rz || d != null || line != null ? View.VISIBLE : View.GONE);
        }

        private String teamLabel(JSONObject g, JSONObject t, String side) {
            if (t != null) return str(t, "short") != null ? str(t, "short") : str(t, "name") != null ? str(t, "name") : "";
            String s = str(g, side);
            return s == null ? "" : s;
        }
    }

    // ---- F1: the timing tower ---------------------------------------------------------------------------------------

    private void timingTower(JSONArray rows, JSONObject s) {
        for (int i = 0; i < Math.min(rows.length(), 20); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || str(r, "driver") == null) continue;
            String key = "f1:" + str(r, "driver");
            F1Row h = (F1Row) holders.get(key);
            if (h == null) {
                h = new F1Row();
                holders.put(key, h);
            }
            h.bind(r);
            order.add(h.root);
        }
        JSONObject fl = s.optJSONObject("fastestLap");
        String fast = fl != null ? ((str(fl, "driver") == null ? "" : str(fl, "driver")) + " " + (str(fl, "time") == null ? "" : str(fl, "time")) + (str(fl, "lap") == null ? "" : " lap " + str(fl, "lap"))).trim() : str(s, "fastestLap");
        if (fast != null && !fast.isEmpty()) text("f1:fastest", "Fastest lap: " + fast, 12, 0xFFC77DFF, false);
    }

    private final class F1Row {
        final LinearLayout root = new LinearLayout(a);
        final TextView pos = Broadcast.cond(a, "", 15, Broadcast.SECONDARY, 700);
        final View bar = new View(a);
        final TextView driver = Broadcast.cond(a, "", 15, Broadcast.TEXT, 700);
        final View fastest = new View(a);
        final TextView gap = Broadcast.cond(a, "", 13, Broadcast.BODY, 600);
        final TextView tyre = Broadcast.cond(a, "", 11, Color.WHITE, 700);
        final GradientDrawable tyreBg = new GradientDrawable();

        F1Row() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, dp(2), 0, dp(2));
            pos.setGravity(Gravity.END);
            root.addView(pos, new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(4), dp(16));
            blp.leftMargin = dp(6);
            blp.rightMargin = dp(6);
            root.addView(bar, blp);
            root.addView(driver, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFA020F0);
            fastest.setBackground(dot);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(7), dp(7));
            flp.rightMargin = dp(6);
            root.addView(fastest, flp);
            gap.setPadding(0, 0, dp(6), 0);
            root.addView(gap);
            tyre.setGravity(Gravity.CENTER);
            tyreBg.setShape(GradientDrawable.OVAL);
            tyre.setBackground(tyreBg);
            root.addView(tyre, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }

        void bind(JSONObject r) {
            String p = str(r, "pos") == null ? "" : str(r, "pos");
            setText(pos, p);
            pos.setTextColor("1".equals(p) ? Broadcast.GOLD : Broadcast.SECONDARY);
            bar.setBackgroundColor(MatchStyle.color(str(r, "teamColor"), Broadcast.SECONDARY));
            setText(driver, str(r, "driver"));
            fastest.setVisibility(r.optBoolean("fastest", false) ? View.VISIBLE : View.GONE);
            // Race: gap ("LEADER" for P1) or interval; practice / qualifying: P1's best lap, the others' gap
            String g = "1".equals(p) ? (str(r, "best") != null ? str(r, "best") : str(r, "gap") != null ? str(r, "gap") : "Leader") : str(r, "gap") != null ? str(r, "gap") : str(r, "interval");
            setText(gap, g == null ? "" : g);
            String ty = str(r, "tyre") == null ? null : str(r, "tyre").substring(0, 1).toUpperCase(Locale.ROOT);
            if (ty == null) {
                tyre.setVisibility(View.GONE);
            } else {
                tyre.setVisibility(View.VISIBLE);
                setText(tyre, ty);
                int ring = "S".equals(ty) ? 0xFFE10600 : "M".equals(ty) ? 0xFFFFD400 : "H".equals(ty) ? 0xFFFFFFFF : "I".equals(ty) ? 0xFF43B02A : "W".equals(ty) ? 0xFF0067AD : Broadcast.LOSS;
                tyreBg.setColor(ring);
                tyre.setTextColor("M".equals(ty) || "H".equals(ty) ? Broadcast.INK : Color.WHITE);
            }
        }
    }

    // ---- Tennis: the set grid -------------------------------------------------------------------------------------

    private void tennisRow(String key, String side, JSONArray sets, JSONObject s, String name, String country) {
        TennisRow h = (TennisRow) holders.get(key);
        if (h == null) {
            h = new TennisRow();
            holders.put(key, h);
        }
        h.bind(side, sets, s, name, country);
        order.add(h.root);
    }

    private static String playerName(JSONObject mt, String side) {
        JSONObject p = mt.optJSONObject(side);
        if (p == null) return str(mt, side) == null ? "" : str(mt, side);
        return str(p, "short") != null ? str(p, "short") : str(p, "name") == null ? "" : str(p, "name");
    }

    private static String playerCountry(JSONObject mt, String side) {
        JSONObject p = mt.optJSONObject(side);
        return p == null ? null : str(p, "country");
    }

    private final class TennisRow {
        final LinearLayout root = new LinearLayout(a);
        final View serve = new View(a);
        final GradientDrawable serveBg = new GradientDrawable();
        final TextView name = Broadcast.cond(a, "", 15, Broadcast.TEXT, 700);
        final TextView[] setViews = new TextView[5];
        final TextView game = Broadcast.cond(a, "", 15, Broadcast.INK, 700);

        TennisRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, dp(3), 0, dp(3));
            serveBg.setShape(GradientDrawable.OVAL);
            serve.setBackground(serveBg);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(7), dp(7));
            slp.rightMargin = dp(6);
            root.addView(serve, slp);
            root.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            for (int i = 0; i < setViews.length; i++) {
                TextView v = Broadcast.cond(a, "", 16, Broadcast.TEXT, 700);
                v.setGravity(Gravity.CENTER);
                root.addView(v, new LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT));
                setViews[i] = v;
            }
            game.setBackgroundColor(Broadcast.GOLD);
            game.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.leftMargin = dp(4);
            root.addView(game, glp);
        }

        void bind(String side, JSONArray sets, JSONObject s, String n, String country) {
            boolean home = "home".equals(side);
            serveBg.setColor(side.equals(str(s, "serving")) ? Broadcast.GOLD : 0x00000000);
            String flag = Broadcast.flag(country);
            setText(name, flag == null ? n : flag + " " + n);
            for (int i = 0; i < setViews.length; i++) {
                JSONObject st = sets == null ? null : sets.optJSONObject(i);
                TextView v = setViews[i];
                if (st == null) {
                    v.setVisibility(View.GONE);
                    continue;
                }
                v.setVisibility(View.VISIBLE);
                String mine = home ? st.optString("home") : st.optString("away");
                String theirs = home ? st.optString("away") : st.optString("home");
                boolean won = parse(mine) > parse(theirs);
                String tb = str(st, "tb");
                setText(v, mine + (tb != null && !won ? tbSup(tb) : ""));
                v.setTextColor(won ? Color.WHITE : Broadcast.SECONDARY);
            }
            JSONObject g = s.optJSONObject("game");
            String gv = g == null ? null : str(g, side);
            setText(game, gv == null ? "" : gv);
            game.setVisibility(gv == null ? View.GONE : View.VISIBLE);
        }
    }

    /** Golf "thru": "F", a hole number, or a UTC tee time ("13:05Z") shown in local time. */
    private static String teeOrThru(String t) {
        if (t == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})Z$").matcher(t);
        if (!m.find()) return t;
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
        c.set(java.util.Calendar.MINUTE, Integer.parseInt(m.group(2)));
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.getTime());
    }

    private static int parse(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String tbSup(String tb) {
        StringBuilder b = new StringBuilder();
        String sup = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079";
        for (char c : tb.toCharArray()) if (c >= '0' && c <= '9') b.append(sup.charAt(c - '0'));
        return b.toString();
    }

    // ---- Golf: the leaderboard, favourites pinned -----------------------------------------------------------------

    private void leaderboard(JSONArray board, JSONArray favs) {
        GolfRow head = (GolfRow) holders.get("golf:head");
        if (head == null) {
            head = new GolfRow();
            holders.put("golf:head", head);
        }
        head.header();
        order.add(head.root);
        Set<String> pinned = new HashSet<>();
        for (int i = 0; favs != null && i < favs.length(); i++) {
            JSONObject r = favs.optJSONObject(i);
            if (r == null || str(r, "player") == null) continue;
            pinned.add(str(r, "player"));
            golfRow("golf:fav:" + str(r, "player"), r, true);
        }
        int shown = 0;
        for (int i = 0; board != null && i < board.length() && shown < 10; i++) {
            JSONObject r = board.optJSONObject(i);
            if (r == null || str(r, "player") == null || pinned.contains(str(r, "player"))) continue;
            golfRow("golf:" + str(r, "player"), r, false);
            shown++;
        }
    }

    private void golfRow(String key, JSONObject r, boolean pinned) {
        GolfRow h = (GolfRow) holders.get(key);
        if (h == null) {
            h = new GolfRow();
            holders.put(key, h);
        }
        h.bind(r, pinned);
        order.add(h.root);
    }

    private final class GolfRow {
        final LinearLayout root = new LinearLayout(a);
        final TextView pos = Broadcast.cond(a, "", 14, Broadcast.SECONDARY, 600);
        final TextView player = Broadcast.body(a, "", 13, Color.WHITE, 600);
        final TextView toPar = Broadcast.cond(a, "", 16, Color.WHITE, 700);
        final TextView thru = Broadcast.cond(a, "", 13, Broadcast.SECONDARY, 600);

        GolfRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(0, dp(2), 0, dp(2));
            root.addView(pos, new LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
            player.setMaxLines(1);
            player.setEllipsize(android.text.TextUtils.TruncateAt.END);
            root.addView(player, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            toPar.setGravity(Gravity.END);
            root.addView(toPar, new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT));
            thru.setGravity(Gravity.END);
            root.addView(thru, new LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        void header() {
            setText(pos, "Pos");
            Broadcast.styleCond(player, 12, Broadcast.SECONDARY, 600);
            setText(player, "Player");
            Broadcast.styleCond(toPar, 12, Broadcast.SECONDARY, 600);
            setText(toPar, "To par");
            Broadcast.styleCond(thru, 12, Broadcast.SECONDARY, 600);
            setText(thru, "Thru");
        }

        void bind(JSONObject r, boolean pinned) {
            setText(pos, str(r, "pos") == null ? "" : str(r, "pos"));
            String flag = Broadcast.flag(str(r, "country"));
            setText(player, (pinned ? "\u2605 " : "") + (flag == null ? "" : flag + " ") + str(r, "player"));
            String tp = str(r, "toPar") == null ? "" : str(r, "toPar");
            setText(toPar, tp);
            toPar.setTextColor(tp.startsWith("-") ? Broadcast.GOLD : Color.WHITE);
            setText(thru, teeOrThru(str(r, "thru")));
            root.setBackgroundColor(pinned ? 0x1FFFC72C : 0x00000000);
        }
    }

    /** One line for the situation row: "Ball on BUF 32 · RED ZONE", "Need 42 off 30", "RRR 8.4", "12-2 run". */
    static String summary(JSONObject s, boolean ddShown) {
        if (s == null) return null;
        List<String> p = new ArrayList<>();
        String dd = downDistance(s);
        if (dd != null && !ddShown) p.add(dd);
        String yl = str(s, "yardLine") != null ? str(s, "yardLine") : str(s, "possessionText");
        if (yl != null) p.add("Ball on " + yl);
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

    static String downDistance(JSONObject s) {
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
                TextView c = Broadcast.cond(a, "", 12, Color.WHITE, 700);
                c.setGravity(Gravity.CENTER);
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
                int bg = "W".equals(b) ? 0xFFD64545 : "4".equals(b) ? 0xFF2F7DE1 : "6".equals(b) ? Broadcast.GOLD : "0".equals(b) || ".".equals(b) ? Broadcast.LOSS : Broadcast.CHIP;
                c.setTextColor("6".equals(b) ? Broadcast.INK : 0xFFFFFFFF);
                c.setBackgroundColor(bg);
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
        final TextView meta = Broadcast.cond(a, "", 13, Broadcast.SECONDARY, 700);
        final TextView text = Broadcast.body(a, "", 13.5f, 0xFFDADDE3, 500);
        final TextView points = Broadcast.cond(a, "", 14, Broadcast.INK, 700);

        PlayRow() {
            // Mockup A (c): condensed clock, a team-colour tick, the text; a dark rule under each play
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(0, dp(5), 0, dp(5));
            root.setBackground(new Broadcast.RuleDrawable(Broadcast.PLAY_RULE, Math.max(1, dp(1))));
            root.addView(meta, new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(3), dp(15));
            dlp.topMargin = dp(2);
            dlp.rightMargin = dp(6);
            root.addView(dot, dlp);
            text.setMaxLines(2);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            root.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            points.setBackgroundColor(Broadcast.GOLD);
            points.setPadding(dp(5), 0, dp(5), 0);
            root.addView(points);
        }

        void bind(JSONObject p, LiveTeams t) {
            boolean scoring = p.optBoolean("scoring", false);
            String team = str(p, "team");
            dot.setBackgroundColor(team == null ? 0x00000000 : t.side(team));
            String m = (str(p, "period") == null ? "" : str(p, "period")) + (str(p, "clock") == null ? "" : " " + str(p, "clock"));
            setText(meta, m.trim());
            setText(text, str(p, "text"));
            Broadcast.styleBody(text, scoring ? 14 : 13.5f, scoring ? Color.WHITE : 0xFFDADDE3, scoring ? 700 : 500);
            int pts = p.has("points") ? p.optInt("points", 0) : p.optInt("delta", 0);
            if (scoring && pts > 0) {
                setText(points, "+" + pts);
                points.setVisibility(View.VISIBLE);
            } else {
                points.setVisibility(View.GONE);
            }
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

    /** Body text (Barlow); [bold] = condensed caps instead (numbers, names, rates). */
    private TextView text(String key, String s, int sp, int color, boolean bold) {
        TextView v = (TextView) holder(key, () -> bold ? Broadcast.cond(a, "", sp, color, 700) : Broadcast.body(a, "", sp, color, 500));
        setText(v, s);
        v.setTextColor(color);
        order.add(v);
        return v;
    }

    private void note(String key, String s) {
        TextView v = (TextView) holder(key, () -> {
            TextView n = Broadcast.body(a, "", 12.5f, Broadcast.SECONDARY, 500);
            n.setPadding(0, dp(3), 0, dp(3));
            return n;
        });
        setText(v, s);
        order.add(v);
    }

    /** A gold section head with a rule under it. */
    private void label(String key, String s) {
        TextView v = (TextView) holder(key, () -> Broadcast.sectionHead(a, ""));
        setText(v, s);
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
                canvas.drawRect(r, paint);
                x += seg;
            }
        }
    }

    /** The field (mockup A (c)): end zones in the team colours, turf with 10-yard lines, the ball (white), the line to gain (gold). */
    static final class FieldBar extends View {
        private float pos = -1;
        private int home;
        private int away;
        private boolean leftward;
        private int dist = -1;
        private final Paint paint = new Paint();

        FieldBar(Context c) {
            super(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(30 * c.getResources().getDisplayMetrics().density));
            lp.topMargin = Math.round(4 * c.getResources().getDisplayMetrics().density);
            setLayoutParams(lp);
        }

        void set(float p, int homeColor, int awayColor, boolean towardsLeft, int distance) {
            if (p == pos && homeColor == home && awayColor == away && towardsLeft == leftward && distance == dist) return;
            pos = p;
            home = homeColor;
            away = awayColor;
            leftward = towardsLeft;
            dist = distance;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float ez = w / 12f;
            paint.setColor(home);
            canvas.drawRect(0, 0, ez, h, paint);
            paint.setColor(away);
            canvas.drawRect(w - ez, 0, w, h, paint);
            paint.setColor(0xFF24572B);
            canvas.drawRect(ez, 0, w - ez, h, paint);
            float field = w - 2 * ez;
            paint.setColor(0x59FFFFFF);
            for (int i = 1; i <= 9; i++) {
                float x = ez + field * i / 10f;
                canvas.drawRect(x, 0, x + 1, h, paint);
            }
            if (pos < 0) return;
            float bx = ez + field * pos;
            float line = 3 * getResources().getDisplayMetrics().density;
            if (dist > 0) {
                float gx = Math.max(ez, Math.min(w - ez, bx + (leftward ? -1 : 1) * field * dist / 100f));
                paint.setColor(Broadcast.GOLD);
                canvas.drawRect(gx - line / 2, 0, gx + line / 2, h, paint);
            }
            paint.setColor(0xFFFFFFFF);
            canvas.drawRect(bx - line / 2, 0, bx + line / 2, h, paint);
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
