package com.jellytip.phone;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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

        /** Match info said 403 (no IPTV) or `{unknown: true}`: hide ⓘ for this match. */
        void onMatchHidden();
    }

    private static final long FANTASY_MS = 60_000;
    private static final long FANTASY_LOOK_MS = 300_000;
    private static final long INFO_STALE_MS = 120_000;
    private static final long INFO_MISSING_RETRY_MS = 10 * 60_000;
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
    /** The hero's entry animation plays once per panel. */
    private boolean heroAnimated;
    private LiveTeams washFor;
    private final LiveStrip liveStrip;
    private final LiveContent liveContent;
    private LiveTeams teams = LiveTeams.NONE;
    private final String programmeTitle;
    private JSONObject multiInfo;
    private JSONObject teamsFrom;
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
        this.programmeTitle = title;

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(Broadcast.BASE);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(16));
        HorizontalScrollView tabScroll = new HorizontalScrollView(activity);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackground(new Broadcast.RuleDrawable(Broadcast.LINE, Math.max(1, dp(1))));
        tabScroll.addView(tabRow);
        col.addView(tabScroll);
        // Live tabs: the team-colour scoreboard strip above them (built once, rebound per answer)
        liveStrip = new LiveStrip(activity, apiBase, token);
        liveStrip.view.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(8);
        col.addView(liveStrip.view, slp);
        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(col);
        liveContent = new LiveContent(activity, content, apiBase, token);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panel.setVisibility(View.GONE);

        if (matchId != null && apiBase != null) {
            addTab("Info");
            if (feed != null) {
                feed.addListener(this);
                for (String t : LiveRender.TABS) addTab(t);
                updateChatTab();
            }
            selectTab("Info");
            fetchInfo();
            // Fantasy: NFL only, and only once the game endpoint names a league
            if (matchId.startsWith(NFL_PREFIX) || multi()) handler.post(fantasyPoll);
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
        liveStrip.setActive(showsLive());
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
        liveStrip.setActive(false);
        handler.removeCallbacks(fantasyPoll);
        io.shutdownNow();
    }

    @Override
    public void onLive() {
        if (feed.hidden) {
            hideLiveTabs();
            return;
        }
        updateChatTab();
        if (shown && showsLive()) render();
    }

    /** No live data for this match (403 / unknown): Stats · Plays · Odds · Chat go; Info and Fantasy stay. */
    private void hideLiveTabs() {
        for (String t : LiveRender.TABS) {
            int i = tabs.indexOf(t);
            if (i >= 0) tabViews.get(i).setVisibility(View.GONE);
        }
        if (tab != null && java.util.Arrays.asList(LiveRender.TABS).contains(tab)) selectTab("Info");
    }

    /** Chat shows only while the feed has a chat block; selected and gone = back to Stats. */
    private void updateChatTab() {
        if (feed == null) return;
        int i = tabs.indexOf("Chat");
        if (i < 0) return;
        boolean chat = LiveRender.hasChat(feed);
        tabViews.get(i).setVisibility(chat ? View.VISIBLE : View.GONE);
        if (!chat && "Chat".equals(tab)) selectTab("Stats");
    }

    private void addTab(String name) {
        TextView t = LiveRender.text(activity, name, 13, Color.WHITE);
        LiveRender.styleTab(activity, t, name, name.equals(tab));
        t.setOnClickListener(v -> selectTab(name));
        tabs.add(name);
        tabViews.add(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(14);
        tabRow.addView(t, lp);
    }

    /** The teams for the live views (names, crests, colours from match info; neutral until it loads). */
    LiveTeams teams() {
        JSONObject src = info != null ? info : multiFallback();
        if (src != teamsFrom) {
            teamsFrom = src;
            teams = new LiveTeams(src);
        }
        return teams;
    }

    /** RedZone without match info (a multi id the match route doesn't know): the programme as the title, the league's colour. */
    private JSONObject multiFallback() {
        if (matchId == null || !matchId.startsWith("multi:")) return null;
        if (multiInfo == null) {
            String[] parts = matchId.split(":");
            try {
                multiInfo = new JSONObject().put("kind", "multi").put("title", programmeTitle != null ? programmeTitle : "Whip-around").put("leagueKey", parts.length > 1 ? parts[1] : null);
            } catch (org.json.JSONException ignored) {
                return null;
            }
        }
        return multiInfo;
    }

    /** A moment flashes the strip while a live tab shows; false = not on screen. */
    boolean flash(String label, String detail, int color) {
        if (!showsLive()) return false;
        liveStrip.flash(label, detail, color);
        return true;
    }

    private void selectTab(String name) {
        tab = name;
        for (int i = 0; i < tabViews.size(); i++) LiveRender.styleTab(activity, tabViews.get(i), tabs.get(i), tabs.get(i).equals(name));
        liveStrip.view.setVisibility(feed != null && LiveRender.isLiveTab(name) ? View.VISIBLE : View.GONE);
        liveStrip.setActive(showsLive());
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
        applyWash(teams());
        if ("Info".equals(tab)) renderInfo();
        else if ("Fantasy".equals(tab)) renderFantasy();
        else if (feed != null) {
            LiveTeams t = teams();
            liveStrip.bindTeams(t);
            liveStrip.bind(feed.last);
            liveContent.render(feed, tab, t);
        }
    }

    // ---- Info -------------------------------------------------------------------------------------------------

    private void fetchInfo() {
        infoAt = System.currentTimeMillis();
        io.execute(() -> {
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, "v1/sport/match/" + CompanionHttp.enc(matchId) + "?score=1");
            final int verdict = CompanionHttp.verdict(a);
            JSONObject got = null;
            if (verdict == CompanionHttp.OK) {
                try {
                    JSONObject root = new JSONObject(a.body);
                    JSONObject m = root.optJSONObject("matchInfo");
                    if (m == null) m = root.optJSONObject("match");
                    got = m != null && (m.optJSONObject("home") != null || "event".equals(m.optString("kind")) || m.has("title")) ? m : root;
                } catch (org.json.JSONException ignored) {
                    // shown as unavailable
                }
            }
            final JSONObject result = got;
            handler.post(() -> {
                if (released) return;
                if (verdict == CompanionHttp.NO_IPTV || verdict == CompanionHttp.UNKNOWN) {
                    info = null;
                    infoNote = "No match info for this match.";
                    infoAt = Long.MAX_VALUE / 2; // never asked again
                    host.onMatchHidden();
                } else if (result != null) {
                    info = result;
                } else if (verdict == CompanionHttp.MISSING) {
                    if (info == null) infoNote = "Match info isn't available on this server yet.";
                    infoAt = System.currentTimeMillis() + INFO_MISSING_RETRY_MS - INFO_STALE_MS; // again in 10 min
                } else if (info == null) {
                    infoNote = "Match info isn't available right now.";
                }
                if ("Info".equals(tab)) render();
            });
        });
    }

    private void renderInfo() {
        content.removeAllViews();
        if (info == null) {
            content.addView(note(infoNote));
            return;
        }
        JSONObject home = info.optJSONObject("home");
        JSONObject away = info.optJSONObject("away");
        LiveTeams t = teams();
        applyWash(t);
        content.addView(t.event ? eventHeader(t) : header(home, away, t), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));
        content.addView(subBar());

        // Remind me / Record (the web app does both)
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(4));
        TextView remind = button("Remind me", true);
        remind.setOnClickListener(v -> host.matchAction("remind"));
        buttons.addView(remind);
        TextView record = button("Record", false);
        record.setOnClickListener(v -> host.matchAction("record"));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(9);
        buttons.addView(record, rlp);
        content.addView(buttons);

        // Body text on a near-black card within the wash
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Broadcast.CARD);
        card.setPadding(dp(10), 0, dp(10), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        content.addView(card, clp);

        // Where to watch: tap a channel to play it here
        JSONArray channels = info.optJSONArray("channels");
        JSONArray broadcasts = info.optJSONArray("broadcasts");
        if ((channels != null && channels.length() > 0) || (broadcasts != null && broadcasts.length() > 0)) {
            card.addView(Broadcast.sectionHead(activity, "Where to watch"));
            String playing = host.currentChannelId();
            for (int i = 0; channels != null && i < channels.length(); i++) {
                JSONObject c = channels.optJSONObject(i);
                if (c == null || str(c, "id") == null) continue;
                final String id = str(c, "id");
                final String name = str(c, "name") != null ? str(c, "name") : id;
                boolean now = id.equals(playing);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(7), 0, dp(7));
                row.addView(Broadcast.body(activity, name, 14, now ? Broadcast.GOLD : Broadcast.TEXT, 600), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView pill = Broadcast.cond(activity, now ? "Playing" : "Watch", 12, now ? Broadcast.INK : Broadcast.BODY, 700);
                pill.setBackgroundColor(now ? Broadcast.GOLD : Broadcast.CHIP);
                pill.setPadding(dp(7), dp(1), dp(7), dp(1));
                row.addView(pill);
                if (!now) row.setOnClickListener(v -> host.switchChannel(id, name));
                card.addView(row);
            }
            List<String> tv = strings(broadcasts);
            if (!tv.isEmpty()) card.addView(note("On TV: " + android.text.TextUtils.join(", ", tv)));
        }

        // Odds: the favourite in gold over a split track, then the lines
        JSONObject odds = info.optJSONObject("odds");
        if (odds != null) {
            View bar = oddsBar(odds, home, away, t);
            List<String> lines = new ArrayList<>();
            JSONObject hs = odds.optJSONObject("home");
            if (str(hs, "spread") != null) lines.add(teamShort(home) + " " + str(hs, "spread"));
            if (str(odds, "draw") != null) lines.add("Draw " + str(odds, "draw"));
            if (str(odds, "overUnder") != null) lines.add("O/U " + str(odds, "overUnder"));
            if (bar != null || !lines.isEmpty()) {
                card.addView(Broadcast.sectionHead(activity, "Odds" + (str(odds, "provider") != null ? " · " + str(odds, "provider") : "")));
                if (bar != null) card.addView(bar);
                if (!lines.isEmpty()) card.addView(note(android.text.TextUtils.join("  ·  ", lines)));
            }
        }

        // Form: W / L chips
        JSONObject form = info.optJSONObject("form");
        if (form != null) {
            List<String> hf = MatchStyle.formLetters(strings(form.optJSONArray("home")));
            List<String> af = MatchStyle.formLetters(strings(form.optJSONArray("away")));
            if (!hf.isEmpty() || !af.isEmpty()) {
                card.addView(Broadcast.sectionHead(activity, "Form · last 5"));
                if (!hf.isEmpty()) card.addView(formRow(teamShort(home), hf));
                if (!af.isEmpty()) card.addView(formRow(teamShort(away), af));
            }
        }

        // Line-ups: two columns with a team-colour tick
        JSONObject lineups = info.optJSONObject("lineups");
        if (lineups != null) {
            List<String> hl = players(lineups.optJSONArray("home"));
            List<String> al = players(lineups.optJSONArray("away"));
            if (!hl.isEmpty() || !al.isEmpty()) {
                LinearLayout cols = new LinearLayout(activity);
                cols.setOrientation(LinearLayout.HORIZONTAL);
                cols.addView(lineupColumn(teamShort(home), hl, t.side("home"), lineups.has("announced") && !lineups.optBoolean("announced")), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                alp.leftMargin = dp(12);
                cols.addView(lineupColumn(teamShort(away), al, t.side("away"), lineups.has("announced") && !lineups.optBoolean("announced")), alp);
                card.addView(cols);
            }
        }

        JSONArray h2h = info.optJSONArray("h2h");
        if (h2h != null && h2h.length() > 0) {
            card.addView(Broadcast.sectionHead(activity, "Last meetings"));
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
                if (line != null && !line.isEmpty() && !"null".equals(line)) card.addView(Broadcast.body(activity, line, 13, 0xFFDADDE3, 500));
            }
        }
    }

    // ---- The Broadcast header (mockup A (b), phone sizes) -----------------------------------------------------------

    /** Two angled full-colour slabs (crest, name, record, score when live or final) and the gold status tag on top. */
    private View header(JSONObject home, JSONObject away, LiveTeams t) {
        FrameLayout box = new FrameLayout(activity);
        LinearLayout slabs = new LinearLayout(activity);
        slabs.setOrientation(LinearLayout.HORIZONTAL);
        String status = str(info, "status");
        JSONObject sc = info.optJSONObject("score");
        boolean scored = sc != null && str(sc, "home") != null && str(sc, "away") != null && ("in".equals(status) || "post".equals(status));
        View homeCrest = slabCrest(home, t.homeColor);
        View awayCrest = slabCrest(away, t.awayColor);
        LinearLayout l = slab(true, t.homeColor);
        l.setPadding(dp(10), dp(14), dp(14), 0);
        l.addView(homeCrest, new LinearLayout.LayoutParams(dp(36), dp(36)));
        l.addView(slabName(home, Broadcast.on(t.homeColor)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (scored) l.addView(Broadcast.cond(activity, str(sc, "home"), 26, Broadcast.on(t.homeColor), 700));
        LinearLayout r = slab(false, t.awayColor);
        r.setPadding(dp(18), dp(14), dp(10), 0);
        if (scored) r.addView(Broadcast.cond(activity, str(sc, "away"), 26, Broadcast.on(t.awayColor), 700));
        TextView an = slabName(away, Broadcast.on(t.awayColor));
        an.setGravity(Gravity.END);
        r.addView(an, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(awayCrest, new LinearLayout.LayoutParams(dp(36), dp(36)));
        slabs.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = -dp(8);
        slabs.addView(r, rlp);
        box.addView(slabs, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        String tag;
        if ("in".equals(status)) {
            JSONObject s2 = info.optJSONObject("score");
            tag = "Live" + (s2 != null && str(s2, "period") != null ? " · " + str(s2, "period") : "");
        } else if ("post".equals(status)) {
            tag = "Full time";
        } else {
            long starts = PlayerPlugin.isoMs(str(info, "startsAt"));
            tag = starts > 0 ? MatchStyle.countdown(starts, System.currentTimeMillis()) : "Upcoming";
        }
        TextView tg = Broadcast.cond(activity, tag, 12, Broadcast.INK, 700);
        tg.setLetterSpacing(0.06f);
        tg.setBackgroundColor(Broadcast.GOLD);
        tg.setPadding(dp(7), dp(1), dp(7), dp(1));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = dp(4);
        box.addView(tg, tlp);
        // Entry, once per panel: the crests slide in from either side
        if (!heroAnimated) {
            heroAnimated = true;
            android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator();
            homeCrest.setTranslationX(-dp(40));
            awayCrest.setTranslationX(dp(40));
            homeCrest.setAlpha(0f);
            awayCrest.setAlpha(0f);
            homeCrest.animate().translationX(0).alpha(1f).setStartDelay(60).setDuration(380).setInterpolator(ease).start();
            awayCrest.animate().translationX(0).alpha(1f).setStartDelay(60).setDuration(380).setInterpolator(ease).start();
        }
        return box;
    }

    /** An event (F1, golf): the title as the hero on the series colour, the gold status tag on the right. */
    private View eventHeader(LiveTeams t) {
        FrameLayout box = new FrameLayout(activity);
        LinearLayout hero = slab(true, t.homeColor);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(14), 0, dp(30), 0);
        int on = Broadcast.on(t.homeColor);
        TextView title = Broadcast.cond(activity, t.title != null ? t.title : t.league != null ? t.league : "Event", 24, on, 700);
        title.setMaxLines(2);
        hero.addView(title);
        if (t.title != null && t.league != null) hero.addView(Broadcast.body(activity, t.league, 13, on, 600));
        box.addView(hero, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        String status = str(info, "status");
        String tag;
        if ("in".equals(status)) {
            tag = "Live";
        } else if ("post".equals(status)) {
            tag = "Finished";
        } else {
            long starts = PlayerPlugin.isoMs(str(info, "startsAt"));
            tag = starts > 0 ? MatchStyle.countdown(starts, System.currentTimeMillis()) : "Upcoming";
        }
        TextView tg = Broadcast.cond(activity, tag, 12, Broadcast.INK, 700);
        tg.setBackgroundColor(Broadcast.GOLD);
        tg.setPadding(dp(7), dp(1), dp(7), dp(1));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tlp.topMargin = dp(4);
        tlp.rightMargin = dp(10);
        box.addView(tg, tlp);
        return box;
    }

    private LinearLayout slab(boolean left, int color) {
        LinearLayout s = new LinearLayout(activity);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setBackground(new Broadcast.Slab(color, left));
        return s;
    }

    private TextView slabName(JSONObject t, int color) {
        String rec = t == null ? null : str(t, "record");
        TextView n = Broadcast.cond(activity, teamName(t), 18, color, 700);
        n.setPadding(dp(6), 0, dp(6), 0);
        if (rec != null) {
            n.setMaxLines(2);
            n.setAllCaps(false);
            n.setText(new android.text.SpannableStringBuilder(Broadcast.caps(teamName(t))).append("\n").append(rec, new android.text.style.RelativeSizeSpan(0.7f), 0));
        }
        return n;
    }

    private View slabCrest(JSONObject t, int color) {
        String logo = t == null ? null : str(t, "logo");
        String flag = logo == null && t != null ? Broadcast.flag(str(t, "country")) : null;
        if (flag != null) {
            // Tennis: the player's flag instead of a crest
            TextView f = new TextView(activity);
            f.setText(flag);
            f.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
            f.setGravity(Gravity.CENTER);
            return f;
        }
        if (logo != null) {
            ImageView img = new ImageView(activity);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            loadImage(logo, img, dp(72));
            return img;
        }
        TextView ini = Broadcast.cond(activity, MatchStyle.initials(teamName(t)), 14, Broadcast.on(color), 700);
        ini.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x40000000);
        ini.setBackground(circle);
        return ini;
    }

    /** League in gold, when, venue with a pin. */
    private View subBar() {
        LinearLayout sub = new LinearLayout(activity);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        sub.setBackgroundColor(Broadcast.SUB);
        sub.setPadding(dp(10), dp(6), dp(10), dp(6));
        String league = str(info, "league");
        if (league != null) sub.addView(Broadcast.cond(activity, league, 15, Broadcast.GOLD, 700));
        List<String> p = new ArrayList<>();
        long starts = PlayerPlugin.isoMs(str(info, "startsAt"));
        if (starts > 0) p.add(new java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new java.util.Date(starts)));
        String venue = venue(info);
        if (venue != null) p.add("📍 " + venue);
        TextView rest = Broadcast.body(activity, android.text.TextUtils.join("  ·  ", p), 13, Broadcast.BODY, 600);
        rest.setMaxLines(1);
        rest.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rest.setPadding(dp(10), 0, 0, 0);
        sub.addView(rest, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return sub;
    }

    /** The favourite's share in gold over a square split track in the team colours; null = unreadable lines. */
    private View oddsBar(JSONObject odds, JSONObject home, JSONObject away, LiveTeams t) {
        Double h = MatchStyle.impliedProb(str(odds.optJSONObject("home"), "moneyline"));
        Double a = MatchStyle.impliedProb(str(odds.optJSONObject("away"), "moneyline"));
        if (h == null || a == null) return null;
        Double dr = MatchStyle.impliedProb(str(odds, "draw"));
        double d = dr == null ? 0 : dr;
        double sum = h + a + d;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, dp(4));
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.BOTTOM);
        labels.addView(oddsLabel(teamShort(home), LiveRender.pct(h / sum), h >= a), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (d > 0) labels.addView(oddsLabel("Draw", LiveRender.pct(d / sum), false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View right = oddsLabel(teamShort(away), LiveRender.pct(a / sum), a > h);
        ((LinearLayout) right).setGravity(Gravity.END | Gravity.BOTTOM);
        labels.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(labels);
        LiveContent.SplitBar bar = new LiveContent.SplitBar(activity);
        bar.set(d > 0 ? new float[] {(float) (h / sum), (float) (d / sum), (float) (a / sum)} : new float[] {(float) (h / sum), (float) (a / sum)}, d > 0 ? new int[] {t.side("home"), Broadcast.LOSS, t.side("away")} : new int[] {t.side("home"), t.side("away")});
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(4);
        box.addView(bar, blp);
        return box;
    }

    private View oddsLabel(String team, String pct, boolean fav) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.BOTTOM);
        l.addView(Broadcast.body(activity, team + " ", 13, Broadcast.TEXT, 600));
        l.addView(Broadcast.cond(activity, pct, 17, fav ? Broadcast.GOLD : Broadcast.TEXT, 700));
        return l;
    }

    private View formRow(String team, List<String> letters) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(Broadcast.cond(activity, team, 13, Broadcast.BODY, 600), new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < Math.min(letters.size(), 10); i++) {
            String r = letters.get(i);
            boolean win = "W".equals(r);
            TextView chip = Broadcast.cond(activity, r, 12, win ? Broadcast.INK : Broadcast.TEXT, 700);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundColor(win ? Broadcast.WIN : Broadcast.LOSS);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.rightMargin = dp(3);
            row.addView(chip, lp);
        }
        return row;
    }

    private View lineupColumn(String team, List<String> names, int color, boolean expected) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        View tick = new View(activity);
        tick.setBackgroundColor(color);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(4), dp(13));
        tlp.rightMargin = dp(5);
        tlp.bottomMargin = dp(6);
        head.addView(tick, tlp);
        head.addView(Broadcast.sectionHead(activity, team + (expected ? " (exp.)" : "") + " XI"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);
        for (int i = 0; i < names.size(); i++) {
            LinearLayout r = new LinearLayout(activity);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.addView(Broadcast.cond(activity, String.valueOf(i + 1), 12, 0xFF8C94A1, 600), new LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView n = Broadcast.body(activity, names.get(i), 12.5f, 0xFFDADDE3, 500);
            n.setMaxLines(1);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(n);
            col.addView(r);
        }
        if (names.isEmpty()) col.addView(note("Not announced"));
        return col;
    }

    private TextView note(String s) {
        TextView t = Broadcast.body(activity, s, 12.5f, Broadcast.SECONDARY, 500);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** Crest images through the shared cache (display size, companion paths resolved). */
    private void loadImage(String url, ImageView into, int maxPx) {
        ImageCache.load(into, url, apiBase, token, maxPx);
    }

    /** The panel's background: the two-tone wash in the team colours (plain base without them). */
    private void applyWash(LiveTeams t) {
        if (t == washFor) return;
        washFor = t;
        panel.setBackground(Broadcast.wash(t, 255));
    }


    // ---- Fantasy ----------------------------------------------------------------------------------------------

    private void fetchFantasy() {
        io.execute(() -> {
            // RedZone (a multi id): every league's live answer, all games; else this game's
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, multi() ? "v1/fantasy/live" : "v1/fantasy/game/" + CompanionHttp.enc(matchId));
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
                    if (!tabs.contains("Fantasy")) {
                        addTab("Fantasy");
                        // RedZone: the Fantasy tab is the default
                        if (multi() && "Info".equals(tab)) selectTab("Fantasy");
                    }
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
            content.addView(note(fantasyNote));
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
            // Mockup A (c): a dark chip with a green edge, the score in condensed numerals
            TextView sc = Broadcast.cond(activity, (me != null && str(me, "team") != null ? str(me, "team") : "You") + "  " + pts(mine) + " – " + pts(theirs) + "  " + (opp != null && str(opp, "team") != null ? str(opp, "team") : "Opp"), 16, Broadcast.TEXT, 700);
            android.graphics.drawable.LayerDrawable chip =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {new android.graphics.drawable.ColorDrawable(Broadcast.SUB), new android.graphics.drawable.ColorDrawable(Broadcast.WIN)});
            chip.setLayerWidth(1, dp(4));
            chip.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
            sc.setBackground(chip);
            sc.setPadding(dp(12), dp(6), dp(8), dp(6));
            content.addView(sc);
            JSONArray starters = l.optJSONArray("starters");
            for (int j = 0; starters != null && j < starters.length(); j++) {
                JSONObject s = starters.optJSONObject(j);
                if (s == null || str(s, "name") == null) continue;
                String sm = str(s, "matchId");
                if (multi() ? !"in".equals(str(s, "gameState")) : sm != null && !sm.equals(matchId)) continue; // in this game only (RedZone: live now)
                boolean isOpp = "opp".equals(str(s, "side"));
                List<String> p = new ArrayList<>();
                p.add((isOpp ? "Opp  " : "You  ") + str(s, "name"));
                if (str(s, "pos") != null) p.add(str(s, "pos"));
                if (str(s, "nflTeam") != null) p.add(str(s, "nflTeam"));
                String line = android.text.TextUtils.join(" · ", p) + (s.has("points") && !s.isNull("points") ? "   " + pts(s.optDouble("points")) : "");
                TextView t = Broadcast.body(activity, line, 13, isOpp ? 0xFFF2A8A3 : 0xFF9EE0A8, 600);
                t.setPadding(0, dp(3), 0, dp(3));
                content.addView(t);
            }
        }
    }

    // ---- Not a match --------------------------------------------------------------------------------------------

    /** A whip-around programme (RedZone; plan "RedZone / whip-around mode"): NFL fantasy across every game. */
    private boolean multi() {
        return matchId != null && matchId.startsWith("multi:nfl");
    }

    private void renderProgramme(String title, String description, List<String[]> upNext) {
        content.removeAllViews();
        if (title != null && !title.isEmpty()) {
            TextView t = Broadcast.cond(activity, title, 20, Broadcast.TEXT, 700);
            t.setMaxLines(2);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            content.addView(t);
        }
        if (description != null && !description.isEmpty()) {
            TextView d = Broadcast.body(activity, description, 14, Broadcast.BODY, 500);
            d.setPadding(0, dp(8), 0, 0);
            content.addView(d);
        }
        if (upNext != null && !upNext.isEmpty()) {
            section("Up next");
            for (String[] n : upNext) {
                TextView r = Broadcast.body(activity, (n[0].isEmpty() ? "" : n[0] + "  ") + n[1], 13, Broadcast.TEXT, 600);
                r.setPadding(0, dp(4), 0, dp(4));
                content.addView(r);
            }
        }
        if (content.getChildCount() == 0) content.addView(note("Nothing more about this one."));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void section(String name) {
        content.addView(Broadcast.sectionHead(activity, name));
    }

    /** Condensed caps; the primary one gold. */
    private TextView button(String label, boolean primary) {
        TextView t = Broadcast.cond(activity, label, 15, primary ? Broadcast.INK : Broadcast.TEXT, 700);
        t.setLetterSpacing(0.05f);
        t.setBackgroundColor(primary ? Broadcast.GOLD : Broadcast.CHIP);
        t.setPadding(dp(16), dp(7), dp(16), dp(7));
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
