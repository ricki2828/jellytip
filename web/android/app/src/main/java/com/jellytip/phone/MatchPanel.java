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
    /** The LIVE pulse (cancelled on every re-render). */
    private android.animation.AnimatorSet pulse;
    private final java.util.Map<String, android.graphics.Bitmap> images = new java.util.HashMap<>();
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
                updateChatTab();
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
        if (pulse != null) pulse.cancel();
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
            final int verdict = CompanionHttp.verdict(a);
            JSONObject got = null;
            if (verdict == CompanionHttp.OK) {
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
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        content.removeAllViews();
        if (info == null) {
            content.addView(LiveRender.muted(activity, infoNote));
            return;
        }
        JSONObject home = info.optJSONObject("home");
        JSONObject away = info.optJSONObject("away");
        int[] colors = MatchStyle.colors(home, away);
        content.addView(hero(home, away, colors), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));

        // Remind me / Record (the web app does both)
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(4));
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

        // Odds: a split bar in the team colours, then the lines
        JSONObject odds = info.optJSONObject("odds");
        if (odds != null) {
            List<String> lines = new ArrayList<>();
            String h = oddsSide(odds.optJSONObject("home"));
            String a = oddsSide(odds.optJSONObject("away"));
            if (h != null) lines.add(teamShort(home) + "  " + h);
            if (a != null) lines.add(teamShort(away) + "  " + a);
            if (str(odds, "draw") != null) lines.add("Draw  " + str(odds, "draw"));
            if (str(odds, "overUnder") != null) lines.add("Over/under  " + str(odds, "overUnder"));
            View bar = oddsBar(odds, home, away, colors);
            if (bar != null || !lines.isEmpty()) {
                section("Odds" + (str(odds, "provider") != null ? " · " + str(odds, "provider") : ""));
                if (bar != null) content.addView(bar);
                for (String l : lines) content.addView(LiveRender.text(activity, l, 13, 0xCCF2F2F0));
            }
        }

        // Line-ups: two columns tinted with the team colours
        JSONObject lineups = info.optJSONObject("lineups");
        if (lineups != null) {
            List<String> hl = players(lineups.optJSONArray("home"));
            List<String> al = players(lineups.optJSONArray("away"));
            if (!hl.isEmpty() || !al.isEmpty()) {
                section(lineups.has("announced") && !lineups.optBoolean("announced") ? "Line-ups (expected)" : "Line-ups");
                LinearLayout cols = new LinearLayout(activity);
                cols.setOrientation(LinearLayout.HORIZONTAL);
                cols.addView(lineupColumn(teamShort(home), hl, colors[0]), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                alp.leftMargin = dp(8);
                cols.addView(lineupColumn(teamShort(away), al, colors[1]), alp);
                content.addView(cols);
            }
        }

        // Form: W / L / D chips
        JSONObject form = info.optJSONObject("form");
        if (form != null) {
            List<String> hf = MatchStyle.formLetters(strings(form.optJSONArray("home")));
            List<String> af = MatchStyle.formLetters(strings(form.optJSONArray("away")));
            if (!hf.isEmpty() || !af.isEmpty()) {
                section("Form");
                if (!hf.isEmpty()) content.addView(formRow(teamShort(home), hf));
                if (!af.isEmpty()) content.addView(formRow(teamShort(away), af));
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

    // ---- The hero (plan "match info: more pop") ---------------------------------------------------------------

    /** Split diagonal hero in the team colours, venue photo faint behind, crests with a glow, the centre state. */
    private View hero(JSONObject home, JSONObject away, int[] colors) {
        FrameLayout hero = new FrameLayout(activity);
        hero.setBackground(new MatchStyle.SplitDrawable(MatchStyle.darken(colors[0]), MatchStyle.darken(colors[1]), dp(12)));
        hero.setClipToOutline(true);
        hero.setOutlineProvider(
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            }
        );
        JSONObject venueObj = info.optJSONObject("venue");
        String venueImage = venueObj == null ? null : str(venueObj, "image");
        if (venueImage != null) {
            ImageView bg = new ImageView(activity);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setAlpha(0.16f);
            hero.addView(bg, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            loadImage(venueImage, bg, dp(480));
        }
        View shade = new View(activity);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x73000000}));
        hero.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        View homeCol = heroTeam(home, colors[0]);
        View awayCol = heroTeam(away, colors[1]);
        row.addView(homeCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(heroCentre(), new LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(awayCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Entry, once per panel: scale-and-fade (220 ms), the crests slide in from either side
        if (!heroAnimated) {
            heroAnimated = true;
            hero.setAlpha(0f);
            hero.setScaleX(0.94f);
            hero.setScaleY(0.94f);
            hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
            homeCol.setTranslationX(-dp(80));
            awayCol.setTranslationX(dp(80));
            homeCol.setAlpha(0f);
            awayCol.setAlpha(0f);
            android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator();
            homeCol.animate().translationX(0).alpha(1f).setStartDelay(60).setDuration(380).setInterpolator(ease).start();
            awayCol.animate().translationX(0).alpha(1f).setStartDelay(60).setDuration(380).setInterpolator(ease).start();
        }
        return hero;
    }

    private View heroTeam(JSONObject t, int color) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout crest = new FrameLayout(activity);
        // Soft glow: a radial fade behind the crest
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[] {(MatchStyle.lighten(color) & 0x00FFFFFF) | 0x8C000000, 0x00000000});
        glow.setGradientRadius(dp(46));
        crest.setBackground(glow);
        String logo = t == null ? null : str(t, "logo");
        if (logo != null) {
            ImageView img = new ImageView(activity);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            crest.addView(img, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
            loadImage(logo, img, dp(144));
        } else {
            TextView ini = LiveRender.text(activity, MatchStyle.initials(teamName(t)), 22, Color.WHITE);
            ini.setGravity(Gravity.CENTER);
            ini.setTypeface(ini.getTypeface(), android.graphics.Typeface.BOLD);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            ini.setBackground(circle);
            crest.addView(ini, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        }
        col.addView(crest, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView name = LiveRender.text(activity, teamName(t), 15, Color.WHITE);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        String rec = t == null ? null : str(t, "record");
        if (rec != null) {
            TextView r = LiveRender.text(activity, rec, 11, 0xB3FFFFFF);
            r.setGravity(Gravity.CENTER);
            col.addView(r);
        }
        return col;
    }

    private View heroCentre() {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String league = str(info, "league");
        if (league != null) {
            TextView l = LiveRender.text(activity, league.toUpperCase(Locale.ROOT), 10, 0xB3FFFFFF);
            l.setGravity(Gravity.CENTER);
            l.setLetterSpacing(0.1f);
            l.setMaxLines(1);
            c.addView(l);
        }
        String status = str(info, "status");
        JSONObject sc = info.optJSONObject("score");
        String clock = null;
        if (sc != null) {
            List<String> p = new ArrayList<>();
            if (str(sc, "period") != null) p.add(str(sc, "period"));
            if (str(sc, "clock") != null) p.add(str(sc, "clock"));
            clock = p.isEmpty() ? null : android.text.TextUtils.join(" · ", p);
        }
        if (sc != null && str(sc, "home") != null && str(sc, "away") != null && ("in".equals(status) || "post".equals(status))) {
            TextView s = LiveRender.text(activity, str(sc, "home") + " – " + str(sc, "away"), 28, Color.WHITE);
            s.setTypeface(s.getTypeface(), android.graphics.Typeface.BOLD);
            c.addView(s);
            String under = "post".equals(status) ? "FT" : clock;
            if (under != null) c.addView(LiveRender.text(activity, under, 12, 0xCCFFFFFF));
        } else if ("in".equals(status)) {
            TextView live = LiveRender.text(activity, "●  LIVE", 16, Color.WHITE);
            live.setTypeface(live.getTypeface(), android.graphics.Typeface.BOLD);
            live.setBackgroundColor(0xFFB3261E);
            live.setPadding(dp(12), dp(4), dp(12), dp(4));
            c.addView(live);
            // A breathing LIVE (scale only)
            pulse = new android.animation.AnimatorSet();
            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(live, View.SCALE_X, 1f, 1.12f);
            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(live, View.SCALE_Y, 1f, 1.12f);
            for (android.animation.ObjectAnimator a : new android.animation.ObjectAnimator[] {sx, sy}) {
                a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                a.setDuration(700);
            }
            pulse.playTogether(sx, sy);
            pulse.start();
            if (clock != null) c.addView(LiveRender.text(activity, clock, 12, 0xD9FFFFFF));
        } else if ("post".equals(status)) {
            TextView ft = LiveRender.text(activity, "FT", 28, Color.WHITE);
            ft.setTypeface(ft.getTypeface(), android.graphics.Typeface.BOLD);
            c.addView(ft);
        } else {
            long starts = PlayerPlugin.isoMs(str(info, "startsAt"));
            if (starts > 0) {
                TextView cd = LiveRender.text(activity, MatchStyle.countdown(starts, System.currentTimeMillis()), 16, Color.WHITE);
                cd.setTypeface(cd.getTypeface(), android.graphics.Typeface.BOLD);
                cd.setGravity(Gravity.CENTER);
                c.addView(cd);
                c.addView(LiveRender.text(activity, new java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new java.util.Date(starts)), 11, 0xBFFFFFFF));
            }
        }
        String venue = venue(info);
        if (venue != null) {
            TextView v = LiveRender.text(activity, "📍 " + venue, 11, 0xBFFFFFFF);
            v.setGravity(Gravity.CENTER);
            v.setMaxLines(2);
            v.setPadding(0, dp(4), 0, 0);
            c.addView(v);
        }
        return c;
    }

    /** Implied probabilities from the moneylines as one bar in the team colours (draw grey); null = unreadable. */
    private View oddsBar(JSONObject odds, JSONObject home, JSONObject away, int[] colors) {
        Double h = MatchStyle.impliedProb(str(odds.optJSONObject("home"), "moneyline"));
        Double a = MatchStyle.impliedProb(str(odds.optJSONObject("away"), "moneyline"));
        if (h == null || a == null) return null;
        Double dr = MatchStyle.impliedProb(str(odds, "draw"));
        double d = dr == null ? 0 : dr;
        double sum = h + a + d;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(6));
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(block(colors[0]), new LinearLayout.LayoutParams(0, dp(12), (float) Math.max(0.01, h / sum)));
        if (d > 0) bar.addView(block(0xFF6B6F78), new LinearLayout.LayoutParams(0, dp(12), (float) Math.max(0.01, d / sum)));
        bar.addView(block(colors[1]), new LinearLayout.LayoutParams(0, dp(12), (float) Math.max(0.01, a / sum)));
        box.addView(bar);
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setPadding(0, dp(4), 0, 0);
        labels.addView(label(teamShort(home) + "  " + LiveRender.pct(h / sum), Gravity.START), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (d > 0) labels.addView(label("Draw  " + LiveRender.pct(d / sum), Gravity.CENTER), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(label(LiveRender.pct(a / sum) + "  " + teamShort(away), Gravity.END), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(labels);
        return box;
    }

    private View block(int color) {
        View v = new View(activity);
        v.setBackgroundColor(color);
        return v;
    }

    private TextView label(String s, int gravity) {
        TextView t = LiveRender.text(activity, s, 13, Color.WHITE);
        t.setGravity(gravity);
        t.setMaxLines(1);
        return t;
    }

    private View formRow(String team, List<String> letters) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView name = LiveRender.text(activity, team, 13, 0xCCF2F2F0);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < Math.min(letters.size(), 10); i++) {
            String r = letters.get(i);
            TextView chip = LiveRender.text(activity, r, 12, Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setBackgroundColor(MatchStyle.formColor(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.rightMargin = dp(4);
            row.addView(chip, lp);
        }
        return row;
    }

    private View lineupColumn(String team, List<String> names, int color) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor((color & 0x00FFFFFF) | 0x2E000000);
        col.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView head = LiveRender.text(activity, team, 13, Color.WHITE);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(head);
        for (String n : names) col.addView(LiveRender.text(activity, n, 12, 0xDDF2F2F0));
        if (names.isEmpty()) col.addView(LiveRender.muted(activity, "Not announced"));
        return col;
    }

    /** Crest / venue images: companion paths resolved against the API base; small in-memory cache. */
    private void loadImage(String url, ImageView into, int maxPx) {
        final String abs = url.startsWith("http://") || url.startsWith("https://") ? url : apiBase.replaceAll("/+$", "") + "/" + url.replaceAll("^/+", "");
        android.graphics.Bitmap cached = images.get(abs);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            android.graphics.Bitmap bm = MatchStyle.download(abs, token, apiBase.replaceAll("/+$", "") + "/", maxPx);
            handler.post(() -> {
                if (released || bm == null) return;
                images.put(abs, bm);
                into.setImageBitmap(bm);
            });
        });
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
