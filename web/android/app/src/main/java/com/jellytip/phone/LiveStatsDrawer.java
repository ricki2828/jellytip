package com.jellytip.phone;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The phone's in-event drawer in landscape (~/ai/plans/jellytip-live-sport.md, "In-event panel" and "Live panel: pop,
 * context, performance"): a right-hand panel over the native player with the team-colour scoreboard strip, pill tabs
 * Stats · Plays · Odds · Chat and their content ({@link LiveContent}), fed by the player's {@link LiveFeed} (15 s full
 * while open). It slides in from the right (260 ms) and out (200 ms); the video keeps playing. Views are built once
 * and rebound on each answer. Portrait uses {@link MatchPanel} below the video instead.
 */
final class LiveStatsDrawer implements LiveFeed.Listener {

    private final Activity activity;
    private final LiveFeed feed;
    private final java.util.function.Supplier<LiveTeams> teams;
    private final FrameLayout panel;
    private final LiveStrip strip;
    private final LiveContent content;
    private final TextView[] tabViews = new TextView[LiveRender.TABS.length];
    private int tab = 0;
    private boolean open;
    /** Told when the drawer opens or closes (the feed polls faster while it's open). */
    Runnable onOpenChanged;

    LiveStatsDrawer(Activity activity, FrameLayout root, LiveFeed feed, String apiBase, String token, java.util.function.Supplier<LiveTeams> teams) {
        this.activity = activity;
        this.feed = feed;
        this.teams = teams;
        feed.addListener(this);

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(0xE60B0C12);
        panel.setClickable(true); // taps on the panel don't reach the player underneath
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(14));

        TextView closeView = LiveRender.text(activity, "✕", 18, Color.WHITE);
        closeView.setPadding(dp(12), 0, dp(2), dp(6));
        closeView.setOnClickListener(v -> close());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END;
        col.addView(closeView, clp);

        strip = new LiveStrip(activity, apiBase, token);
        col.addView(strip.view);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(10), 0, dp(10));
        for (int i = 0; i < LiveRender.TABS.length; i++) {
            final int index = i;
            TextView t = LiveRender.text(activity, LiveRender.TABS[i], 13, Color.WHITE);
            t.setOnClickListener(v -> selectTab(index));
            tabViews[i] = t;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = dp(6);
            tabs.addView(t, tlp);
        }
        col.addView(tabs);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LiveContent(activity, box, apiBase, token);
        panel.addView(col);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, lp);
        panel.setVisibility(View.GONE);
        // ~40% of the width (landscape only)
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = (int) ((r - l) * 0.4f);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (p.width != want) {
                p.width = want;
                panel.setLayoutParams(p);
            }
        });
        selectTab(0);
        updateChatTab();
    }

    /** Chat shows only while the feed has a chat block; selected and gone = back to Stats. */
    private void updateChatTab() {
        boolean chat = LiveRender.hasChat(feed);
        for (int i = 0; i < tabViews.length; i++) {
            if (!"Chat".equals(LiveRender.TABS[i])) continue;
            tabViews[i].setVisibility(chat ? View.VISIBLE : View.GONE);
            if (!chat && tab == i) selectTab(0);
        }
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
        strip.setActive(true);
        render();
        if (onOpenChanged != null) onOpenChanged.run();
    }

    void close() {
        if (!open) return;
        open = false;
        strip.setActive(false);
        if (onOpenChanged != null) onOpenChanged.run();
        panel.animate().translationX(panel.getWidth()).setDuration(200).setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    /** Closed at once (rotating to portrait, entering the mini player). */
    void hideNow() {
        if (open && onOpenChanged != null) {
            open = false;
            onOpenChanged.run();
        }
        open = false;
        strip.setActive(false);
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
    }

    /** The strip flashes a moment while the drawer is open; false = not showing. */
    boolean flash(String label, int color) {
        if (!open) return false;
        strip.flash(label, color);
        return true;
    }

    @Override
    public void onLive() {
        if (feed.hidden) {
            hideNow();
            return;
        }
        updateChatTab();
        if (open) render();
    }

    private void selectTab(int index) {
        tab = index;
        for (int i = 0; i < tabViews.length; i++) LiveRender.styleTab(activity, tabViews[i], LiveRender.TABS[i], i == index);
        render();
    }

    private void render() {
        LiveTeams t = teams.get();
        strip.bindTeams(t);
        strip.bind(feed.last);
        content.render(feed, LiveRender.TABS[tab], t);
    }

    private int dp(int v) {
        return LiveRender.dp(activity, v);
    }
}
