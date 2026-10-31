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

    private final boolean floating;
    private LiveTeams washFor;

    LiveStatsDrawer(Activity activity, FrameLayout root, LiveFeed feed, String apiBase, String token, java.util.function.Supplier<LiveTeams> teams, boolean floating) {
        this.activity = activity;
        this.feed = feed;
        this.teams = teams;
        this.floating = floating;
        feed.addListener(this);

        // Broadcast (plan "In-match visual direction"): the two-tone wash at 95%; docked = full height with a gold
        // left border, floating (sport.panelStyle) = a rounded card inset 12 dp
        panel = new FrameLayout(activity);
        panel.setBackground(Broadcast.wash(LiveTeams.NONE, 242));
        panel.setClickable(true); // taps on the panel don't reach the player underneath
        if (floating) {
            panel.setClipToOutline(true);
            panel.setOutlineProvider(
                new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
                    }
                }
            );
        }
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

        // Body text sits on a near-black card within the wash
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Broadcast.CARD);
        card.setPadding(dp(10), dp(4), dp(10), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(10);
        col.addView(card, cardLp);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(new Broadcast.RuleDrawable(Broadcast.LINE, Math.max(1, dp(1))));
        for (int i = 0; i < LiveRender.TABS.length; i++) {
            final int index = i;
            TextView t = LiveRender.text(activity, LiveRender.TABS[i], 13, Color.WHITE);
            t.setOnClickListener(v -> selectTab(index));
            tabViews[i] = t;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = dp(14);
            tabs.addView(t, tlp);
        }
        card.addView(tabs);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(8);
        card.addView(scroll, slp);
        content = new LiveContent(activity, box, apiBase, token);
        panel.addView(col);
        if (!floating) {
            View gold = new View(activity);
            gold.setBackgroundColor(Broadcast.GOLD);
            panel.addView(gold, new FrameLayout.LayoutParams(dp(3), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        if (floating) lp.setMargins(dp(12), dp(12), dp(12), dp(12));
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
    boolean flash(String label, String detail, int color) {
        if (!open) return false;
        strip.flash(label, detail, color);
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
        if (t != washFor) {
            washFor = t;
            panel.setBackground(Broadcast.wash(t, 242));
        }
        strip.bindTeams(t);
        strip.bind(feed.last);
        content.render(feed, LiveRender.TABS[tab], t);
    }

    private int dp(int v) {
        return LiveRender.dp(activity, v);
    }
}
