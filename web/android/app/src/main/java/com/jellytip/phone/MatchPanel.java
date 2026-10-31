package com.jellytip.phone;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Portrait panel below the 16:9 video (~/ai/plans/jellytip-live-sport.md, "Phone: portrait match layout plus a mini
 * player"). A match gets tabs Info · Stats · Plays · Odds · Chat · Fantasy: Info is GET /v1/sport/match/{id} (header,
 * where to watch, odds, line-ups, form, Remind me / Record), the live tabs read the player's {@link LiveFeed}, and
 * Fantasy (NFL only, shown once /v1/fantasy/game/{id} returns a league) polls that every 60 s while it is open.
 * Anything else shows its title, description and what's on next.
 */
final class MatchPanel implements LiveFeed.Listener {

    /** What the panel asks of the player. */
    interface Host {
        String currentChannelId();

        /** Play [id] in place ("where to watch"). */
        void switchChannel(String id, String name);

        /** Remind me / Record: `matchAction {action, matchId, channelId?}` to the web app. */
        void matchAction(String action);

        /** The selected tab changed (the feed polls faster on a live tab). */
        void onPanelTabChanged();
    }

    private static final long FANTASY_MS = 60_000;
    private static final long FANTASY_LOOK_MS = 300_000;
    private static final long INFO_STALE_MS = 120_000;
    private static final String NFL_PREFIX = "espn:football~nfl:";

    private final Activity activity;
    private final Host host;
    private final Handler handler;
    private final LiveFeed feed;
    private final String apiBase;
    private final String token;
    private final String matchId;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final FrameLayout panel;
    private final LinearLayout tabRow;
    private final LinearLayout content;
    private final List<String> tabs = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private String tab;
    private boolean shown;
    private boolean released;
    private JSONObject info;
    private String infoNote = "Loading match info…";
    private long infoAt;
    private JSONArray fantasyLeagues;
    private String fantasyNote = "Loading fantasy…";

    private final Runnable fantasyPoll =
        new Runnable() {
            @Override
            public void run() {
                if (released) return;
                fetchFantasy();
                if (shown && "Fantasy".equals(tab)) handler.postDelayed(this, FANTASY_MS);
                else if (!tabs.contains("Fantasy")) handler.postDelayed(this, FANTASY_LOOK_MS); // no league yet: look again
            }
        };

    MatchPanel(
        Activity activity,
        FrameLayout root,
        Handler handler,
        Host host,
        LiveFeed feed,
        String apiBase,
        String token,
        String matchId,
        String title,
        String description,
        List<String[]> upNext
    ) {
        this.activity = activity;
        this.handler = handler;
        this.host = host;
        this.feed = feed;
        this.apiBase = apiBase;
        this.token = token;
        this.matchId = matchId;

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(0xFF101014);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(16));
        HorizontalScrollView tabScroll = new HorizontalScrollView(activity);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(4), 0, dp(10));
        tabScroll.addView(tabRow);
        col.addView(tabScroll);
        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(col);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panel.setVisibility(View.GONE);

        if (matchId != null && apiBase != null) {
            addTab("Info");
            if (feed != null) {
                feed.addListener(this);
                for (String t : LiveRender.TABS) addTab(t);
            }
            selectTab("Info");
            fetchInfo();
            // Fantasy: NFL only, and only once the game endpoint names a league
            if (matchId.startsWith(NFL_PREFIX)) handler.post(fantasyPoll);
        } else {
            tabScroll.setVisibility(View.GONE);
            renderProgramme(title, description, upNext);
        }
    }

    /** Portrait (and not the mini player): the panel shows; [top] = just below the video. */
    void show(boolean show, int top) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            panel.setLayoutParams(lp);
        }
        if (show == shown) return;
        shown = show;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && "Info".equals(tab) && System.currentTimeMillis() - infoAt > INFO_STALE_MS) fetchInfo();
        if (show && "Fantasy".equals(tab)) {
            handler.removeCallbacks(fantasyPoll);
            handler.post(fantasyPoll);
        }
    }

    /** A live tab is on screen (the feed polls every 15 s). */
    boolean showsLive() {
        return shown && tab != null && !"Info".equals(tab) && !"Fantasy".equals(tab);
    }

    /** The short name of the "home" / "away" team from match info, or null. */
    String teamShortFor(String side) {
        if (info == null || side == null || (!"home".equals(side) && !"away".equals(side))) return null;
        JSONObject t = info.optJSONObject(side);
        return t == null ? null : teamShort(t);
    }

    /** The player switched channel: "(playing)" moves in Where to watch. */
    void onChannelChanged() {
        if ("Info".equals(tab)) render();
    }

    void release() {
        released = true;
        handler.removeCallbacks(fantasyPoll);
        io.shutdownNow();
    }

    @Override
    public void onLive() {
        if (shown && showsLive()) render();
    }

    private void addTab(String name) {
        TextView t = LiveRender.text(activity, name, 13, Color.WHITE);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setOnClickListener(v -> selectTab(name));
        tabs.add(name);
        tabViews.add(t);
        tabRow.addView(t);
    }

    private void selectTab(String name) {
        tab = name;
        for (int i = 0; i < tabViews.size(); i++) {
            boolean on = tabs.get(i).equals(name);
            tabViews.get(i).setBackgroundColor(on ? 0xFFF2F2F0 : Color.TRANSPARENT);
            tabViews.get(i).setTextColor(on ? Color.BLACK : Color.WHITE);
        }
        if ("Info".equals(name) && System.currentTimeMillis() - infoAt > INFO_STALE_MS) fetchInfo();
        if ("Fantasy".equals(name)) {
            handler.removeCallbacks(fantasyPoll);
            handler.post(fantasyPoll);
        }
        render();
        host.onPanelTabChanged();
    }

    private void render() {
        if (tab == null) return;
        if ("Info".equals(tab)) renderInfo();
        else if ("Fantasy".equals(tab)) renderFantasy();
        else if (feed != null) LiveRender.render(activity, content, feed, tab);
    }

    // ---- Info -------------------------------------------------------------------------------------------------

    private void fetchInfo() {
        infoAt = System.currentTimeMillis();
        io.execute(() -> {
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, "v1/sport/match/" + CompanionHttp.enc(matchId) + "?score=1");
            JSONObject got = null;
            if (a.body != null) {
                try {
                    JSONObject root = new JSONObject(a.body);
                    JSONObject m = root.optJSONObject("matchInfo");
                    if (m == null) m = root.optJSONObject("match");
                    got = m != null && m.optJSONObject("home") != null ? m : root;
                } catch (org.json.JSONException ignored) {
                    // shown as unavailable
                }
            }
            final JSONObject result = got;
            final int code = a.code;
            handler.post(() -> {
                if (released) return;
                if (result != null) info = result;
                else if (info == null) infoNote = code == 404 ? "No match info for this event." : "Match info isn't available right now.";
                if ("Info".equals(tab)) render();
            });
        });
    }

    private void renderInfo() {
        content.removeAllViews();
        if (info == null) {
            content.addView(LiveRender.muted(activity, infoNote));
            return;
        }
        JSONObject home = info.optJSONObject("home");
        JSONObject away = info.optJSONObject("away");
        String league = str(info, "league");
        if (league != null) content.addView(LiveRender.muted(activity, league));
        TextView head = LiveRender.text(activity, teamName(home) + "  v  " + teamName(away), 18, Color.WHITE);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        content.addView(head);
        JSONObject sc = info.optJSONObject("score");
        if (sc != null && str(sc, "home") != null) {
            List<String> parts = new ArrayList<>();
            parts.add(str(sc, "home") + " – " + str(sc, "away"));
            if (str(sc, "period") != null) parts.add(str(sc, "period"));
            if (str(sc, "clock") != null) parts.add(str(sc, "clock"));
            content.addView(LiveRender.text(activity, android.text.TextUtils.join("  ·  ", parts), 15, Color.WHITE));
        }
        List<String> when = new ArrayList<>();
        long starts = PlayerPlugin.isoMs(str(info, "startsAt"));
        if (starts > 0) when.add(new java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new java.util.Date(starts)));
        String venue = venue(info);
        if (venue != null) when.add(venue);
        if (!when.isEmpty()) content.addView(LiveRender.muted(activity, android.text.TextUtils.join(" · ", when)));

        // Remind me / Record (the web app does both)
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, dp(4));
        TextView remind = button("Remind me");
        remind.setOnClickListener(v -> host.matchAction("remind"));
        buttons.addView(remind);
        TextView record = button("Record");
        record.setOnClickListener(v -> host.matchAction("record"));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        buttons.addView(record, rlp);
        content.addView(buttons);

        // Where to watch: tap a channel to play it here
        JSONArray channels = info.optJSONArray("channels");
        JSONArray broadcasts = info.optJSONArray("broadcasts");
        if ((channels != null && channels.length() > 0) || (broadcasts != null && broadcasts.length() > 0)) {
            section("Where to watch");
            String playing = host.currentChannelId();
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONObject c = channels.optJSONObject(i);
                if (c == null || str(c, "id") == null) continue;
                final String id = str(c, "id");
                final String name = str(c, "name") != null ? str(c, "name") : id;
                boolean now = id.equals(playing);
                TextView row = LiveRender.text(activity, (now ? "●  " : "▶  ") + name + (now ? "  (playing)" : ""), 14, now ? 0xFF7FD48A : Color.WHITE);
                row.setPadding(0, dp(6), 0, dp(6));
                if (!now) row.setOnClickListener(v -> host.switchChannel(id, name));
                content.addView(row);
            }
            List<String> tv = strings(broadcasts);
            if (!tv.isEmpty()) content.addView(LiveRender.muted(activity, "On TV: " + android.text.TextUtils.join(", ", tv)));
        }

        JSONObject odds = info.optJSONObject("odds");
        if (odds != null) {
            List<String> lines = new ArrayList<>();
            String h = oddsSide(odds.optJSONObject("home"));
            String a = oddsSide(odds.optJSONObject("away"));
            if (h != null) lines.add(teamShort(home) + "  " + h);
            if (a != null) lines.add(teamShort(away) + "  " + a);
            if (str(odds, "draw") != null) lines.add("Draw  " + str(odds, "draw"));
            if (str(odds, "overUnder") != null) lines.add("Over/under  " + str(odds, "overUnder"));
            if (!lines.isEmpty()) {
                section("Odds" + (str(odds, "provider") != null ? " · " + str(odds, "provider") : ""));
                for (String l : lines) content.addView(LiveRender.text(activity, l, 13, Color.WHITE));
            }
        }

        JSONObject lineups = info.optJSONObject("lineups");
        if (lineups != null) {
            List<String> hl = players(lineups.optJSONArray("home"));
            List<String> al = players(lineups.optJSONArray("away"));
            if (!hl.isEmpty() || !al.isEmpty()) {
                section(lineups.has("announced") && !lineups.optBoolean("announced") ? "Line-ups (expected)" : "Line-ups");
                if (!hl.isEmpty()) content.addView(LiveRender.text(activity, teamShort(home) + ": " + android.text.TextUtils.join(", ", hl), 13, 0xDDF2F2F0));
                if (!al.isEmpty()) content.addView(LiveRender.text(activity, teamShort(away) + ": " + android.text.TextUtils.join(", ", al), 13, 0xDDF2F2F0));
            }
        }

        JSONObject form = info.optJSONObject("form");
        if (form != null) {
            List<String> hf = strings(form.optJSONArray("home"));
            List<String> af = strings(form.optJSONArray("away"));
            if (!hf.isEmpty() || !af.isEmpty()) {
                section("Form");
                if (!hf.isEmpty()) content.addView(LiveRender.text(activity, teamShort(home) + "  " + android.text.TextUtils.join(" ", hf), 13, Color.WHITE));
                if (!af.isEmpty()) content.addView(LiveRender.text(activity, teamShort(away) + "  " + android.text.TextUtils.join(" ", af), 13, Color.WHITE));
            }
        }

        JSONArray h2h = info.optJSONArray("h2h");
        if (h2h != null && h2h.length() > 0) {
            section("Last meetings");
            for (int i = 0; i < Math.min(h2h.length(), 5); i++) {
                JSONObject m = h2h.optJSONObject(i);
                String line;
                if (m != null) {
                    List<String> p = new ArrayList<>();
                    if (str(m, "date") != null) p.add(str(m, "date").length() > 10 ? str(m, "date").substring(0, 10) : str(m, "date"));
                    if (str(m, "home") != null) p.add(str(m, "home"));
                    if (str(m, "homeScore") != null && str(m, "awayScore") != null) p.add(str(m, "homeScore") + "-" + str(m, "awayScore"));
                    if (str(m, "away") != null) p.add(str(m, "away"));
                    line = android.text.TextUtils.join(" ", p);
                } else {
                    line = h2h.optString(i);
                }
                if (line != null && !line.isEmpty() && !"null".equals(line)) content.addView(LiveRender.text(activity, line, 13, 0xDDF2F2F0));
            }
        }
    }

    // ---- Fantasy ----------------------------------------------------------------------------------------------

    private void fetchFantasy() {
        io.execute(() -> {
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, "v1/fantasy/game/" + CompanionHttp.enc(matchId));
            JSONArray got = null;
            if (a.body != null) {
                try {
                    got = new JSONObject(a.body).optJSONArray("leagues");
                } catch (org.json.JSONException ignored) {
                    // treated as none
                }
            }
            final JSONArray result = got;
            final int code = a.code;
            handler.post(() -> {
                if (released) return;
                if (result != null && result.length() > 0) {
                    fantasyLeagues = result;
                    if (!tabs.contains("Fantasy")) addTab("Fantasy");
                } else if (fantasyLeagues == null) {
                    fantasyNote = code == 200 || code == 404 ? "No fantasy players in this game." : "Fantasy isn't available right now.";
                }
                if ("Fantasy".equals(tab)) render();
            });
        });
    }

    private void renderFantasy() {
        content.removeAllViews();
        if (fantasyLeagues == null) {
            content.addView(LiveRender.muted(activity, fantasyNote));
            return;
        }
        for (int i = 0; i < fantasyLeagues.length(); i++) {
            JSONObject l = fantasyLeagues.optJSONObject(i);
            if (l == null) continue;
            JSONObject me = l.optJSONObject("me");
            JSONObject opp = l.optJSONObject("opp");
            double mine = me == null ? 0 : me.optDouble("points", 0);
            double theirs = opp == null ? 0 : opp.optDouble("points", 0);
            section(l.optString("name", "League"));
            content.addView(LiveRender.text(activity, (me != null && str(me, "team") != null ? str(me, "team") : "You") + "  " + pts(mine) + " – " + pts(theirs) + "  " + (opp != null && str(opp, "team") != null ? str(opp, "team") : "Opp"), 15, Color.WHITE));
            JSONArray starters = l.optJSONArray("starters");
            for (int j = 0; starters != null && j < starters.length(); j++) {
                JSONObject s = starters.optJSONObject(j);
                if (s == null || str(s, "name") == null) continue;
                String sm = str(s, "matchId");
                if (sm != null && !sm.equals(matchId)) continue; // in this game only
                boolean isOpp = "opp".equals(str(s, "side"));
                List<String> p = new ArrayList<>();
                p.add((isOpp ? "Opp  " : "You  ") + str(s, "name"));
                if (str(s, "pos") != null) p.add(str(s, "pos"));
                if (str(s, "nflTeam") != null) p.add(str(s, "nflTeam"));
                String line = android.text.TextUtils.join(" · ", p) + (s.has("points") && !s.isNull("points") ? "   " + pts(s.optDouble("points")) : "");
                TextView t = LiveRender.text(activity, line, 13, isOpp ? 0xFFF2A8A3 : 0xFF9EE0A8);
                t.setPadding(0, dp(3), 0, dp(3));
                content.addView(t);
            }
        }
    }

    // ---- Not a match --------------------------------------------------------------------------------------------

    private void renderProgramme(String title, String description, List<String[]> upNext) {
        content.removeAllViews();
        if (title != null && !title.isEmpty()) {
            TextView t = LiveRender.text(activity, title, 18, Color.WHITE);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            content.addView(t);
        }
        if (description != null && !description.isEmpty()) {
            TextView d = LiveRender.text(activity, description, 14, 0xDDF2F2F0);
            d.setPadding(0, dp(8), 0, 0);
            content.addView(d);
        }
        if (upNext != null && !upNext.isEmpty()) {
            section("Up next");
            for (String[] n : upNext) {
                TextView r = LiveRender.text(activity, (n[0].isEmpty() ? "" : n[0] + "  ") + n[1], 13, Color.WHITE);
                r.setPadding(0, dp(4), 0, dp(4));
                content.addView(r);
            }
        }
        if (content.getChildCount() == 0) content.addView(LiveRender.muted(activity, "Nothing more about this one."));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void section(String name) {
        TextView t = LiveRender.text(activity, name, 13, 0x99F2F2F0);
        t.setPadding(0, dp(16), 0, dp(4));
        t.setAllCaps(true);
        content.addView(t);
    }

    private TextView button(String label) {
        TextView t = LiveRender.text(activity, label, 14, Color.WHITE);
        t.setBackgroundColor(0xFF333338);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String s = o.optString(key);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i);
            if (s != null && !s.isEmpty() && !"null".equals(s)) out.add(s);
        }
        return out;
    }

    private static List<String> players(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < 15; i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null || str(p, "name") == null || !p.optBoolean("starter", true)) continue;
            out.add(str(p, "name") + (str(p, "pos") != null ? " (" + str(p, "pos") + ")" : ""));
        }
        return out;
    }

    private static String teamName(JSONObject t) {
        if (t == null) return "?";
        String n = str(t, "name");
        return n == null ? "?" : n;
    }

    private static String teamShort(JSONObject t) {
        if (t == null) return "?";
        String s = str(t, "short");
        return s != null ? s : teamName(t);
    }

    private static String venue(JSONObject o) {
        JSONObject v = o.optJSONObject("venue");
        if (v != null) {
            List<String> p = new ArrayList<>();
            if (str(v, "name") != null) p.add(str(v, "name"));
            if (str(v, "city") != null) p.add(str(v, "city"));
            return p.isEmpty() ? null : android.text.TextUtils.join(", ", p);
        }
        return str(o, "venue");
    }

    private static String oddsSide(JSONObject s) {
        if (s == null) return null;
        List<String> p = new ArrayList<>();
        if (str(s, "moneyline") != null) p.add(str(s, "moneyline"));
        if (str(s, "spread") != null) p.add("spread " + str(s, "spread"));
        return p.isEmpty() ? null : android.text.TextUtils.join(" · ", p);
    }

    static String pts(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private int dp(int v) {
        return LiveRender.dp(activity, v);
    }
}
