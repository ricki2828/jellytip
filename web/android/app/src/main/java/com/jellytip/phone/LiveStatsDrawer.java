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
 * The phone's in-event drawer in landscape (~/ai/plans/jellytip-live-sport.md, "In-event panel"): a right-hand panel
 * over the native player with Stats · Plays · Odds · Chat from the player's {@link LiveFeed} (15 s while open). It
 * slides in from the right (260 ms) and out (200 ms); the video keeps playing. A missing route (404) shows a note.
 * Portrait uses {@link MatchPanel} below the video instead.
 */
final class LiveStatsDrawer implements LiveFeed.Listener {

    private final Activity activity;
    private final LiveFeed feed;
    private final FrameLayout panel;
    private final LinearLayout content;
    private final TextView[] tabViews = new TextView[LiveRender.TABS.length];
    private int tab = 0;
    private boolean open;
    /** Told when the drawer opens or closes (the feed polls faster while it's open). */
    Runnable onOpenChanged;

    LiveStatsDrawer(Activity activity, FrameLayout root, LiveFeed feed) {
        this.activity = activity;
        this.feed = feed;
        feed.addListener(this);

        panel = new FrameLayout(activity);
        panel.setBackgroundColor(0xE6101014);
        panel.setClickable(true); // taps on the panel don't reach the player underneath
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(16));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = LiveRender.text(activity, "Live", 16, Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeView = LiveRender.text(activity, "✕", 20, Color.WHITE);
        closeView.setPadding(dp(12), dp(4), dp(4), dp(4));
        closeView.setOnClickListener(v -> close());
        head.addView(closeView);
        col.addView(head);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(10), 0, dp(10));
        for (int i = 0; i < LiveRender.TABS.length; i++) {
            final int index = i;
            TextView t = LiveRender.text(activity, LiveRender.TABS[i], 13, Color.WHITE);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setOnClickListener(v -> selectTab(index));
            tabViews[i] = t;
            tabs.addView(t);
        }
        col.addView(tabs);

        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
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
        render();
        if (onOpenChanged != null) onOpenChanged.run();
    }

    void close() {
        if (!open) return;
        open = false;
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
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
    }

    @Override
    public void onLive() {
        updateChatTab();
        if (open) render();
    }

    private void selectTab(int index) {
        tab = index;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setBackgroundColor(i == index ? 0xFFF2F2F0 : Color.TRANSPARENT);
            tabViews[i].setTextColor(i == index ? Color.BLACK : Color.WHITE);
        }
        render();
    }

    private void render() {
        LiveRender.render(activity, content, feed, LiveRender.TABS[tab]);
    }

    private int dp(int v) {
        return LiveRender.dp(activity, v);
    }
}
