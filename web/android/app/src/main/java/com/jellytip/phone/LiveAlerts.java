package com.jellytip.phone;

import android.os.Handler;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fantasy scoring and market-flip alerts for the match playing (~/ai/plans/jellytip-live-sport.md, "Fantasy alerts"
 * and "Favourite-flip toast"). Flips come from the player's {@link LiveFeed} (a new market.flip.at); fantasy from GET
 * /v1/fantasy/live every 60 s for an NFL match, both sides, de-duplicated on at + league + name, the first answer
 * only priming. The player decides how to show each (a toast on screen, a heads-up notification in the mini player).
 */
final class LiveAlerts implements LiveFeed.Listener {

    interface Sink {
        /** [side]: "me", "opp" or null (a market flip). */
        void alert(String text, String side);

        /** The short name of the "home" / "away" team, or null. */
        String teamName(String side);

        /** A big moment ("TOUCHDOWN", "WICKET", "LEAD CHANGE"; [side] home | away | null) with its detail or the score line. */
        void moment(String label, String side, String detail);
    }

    private static final long FANTASY_MS = 60_000;
    private static final String NFL_PREFIX = "espn:football~nfl:";

    private final Handler handler;
    private final LiveFeed feed;
    private final String apiBase;
    private final String token;
    private final boolean flips;
    private final Sink sink;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> seen = new HashSet<>();
    private boolean primed;
    private boolean released;
    private String lastFlipAt;
    private final boolean nfl;
    private boolean momentsPrimed;
    private final Set<String> seenMoments = new HashSet<>();
    private int lastHome = -1;
    private int lastAway = -1;

    private final Runnable fantasyPoll =
        new Runnable() {
            @Override
            public void run() {
                if (released) return;
                fetchFantasy();
                handler.postDelayed(this, FANTASY_MS);
            }
        };

    LiveAlerts(Handler handler, LiveFeed feed, String apiBase, String token, String matchId, boolean fantasy, boolean flips, Sink sink) {
        this.handler = handler;
        this.feed = feed;
        this.apiBase = apiBase;
        this.token = token;
        this.flips = flips;
        this.sink = sink;
        // RedZone (multi:nfl:…): fantasy toasts for every starter, every game (events aren't filtered by game)
        this.nfl = matchId != null && (matchId.startsWith(NFL_PREFIX) || matchId.startsWith("multi:nfl"));
        if (feed != null) feed.addListener(this);
        if (fantasy && apiBase != null && nfl) handler.post(fantasyPoll);
    }

    void release() {
        released = true;
        handler.removeCallbacks(fantasyPoll);
        io.shutdownNow();
    }

    @Override
    public void onLive() {
        if (released || feed.last == null) return;
        moments(feed.last);
        if (!flips) return;
        JSONObject m = feed.last.optJSONObject("market");
        JSONObject flip = m == null ? null : m.optJSONObject("flip");
        String at = flip == null || flip.isNull("at") ? null : flip.optString("at");
        if (at == null || at.isEmpty() || at.equals(lastFlipAt)) return;
        // A thin market never fires a flip alert (plan "thin markets", decided 31-Oct)
        if (m.optBoolean("thin", false)) {
            lastFlipAt = at;
            return;
        }
        lastFlipAt = at;
        String to = flip.isNull("to") ? null : flip.optString("to");
        String name = sink.teamName(to);
        if (name == null) name = to != null && !to.isEmpty() ? to : "New";
        String source = m.isNull("source") ? "" : m.optString("source");
        String src = source.isEmpty() ? "Market" : source.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + source.substring(1);
        String prob = flip.has("prob") && !flip.isNull("prob") ? " · " + LiveRender.pct(flip.optDouble("prob")) : "";
        // Winner markets (plan "Polymarket coverage round 2"): flip.to names the outcome
        boolean winner = "winner".equals(m.isNull("kind") ? null : m.optString("kind"));
        sink.alert("📈  " + src + " flip: " + (winner ? (to != null && !to.isEmpty() ? to : "New") + " new favourite" : name + " now favourites") + prob, null);
    }

    /**
     * Moments (plan "Live panel: pop, context, performance"): the server's `moments` (de-duplicated on at + label), else
     * a lead change or an NFL touchdown / field goal from score changes. The first answer only primes.
     */
    private void moments(JSONObject d) {
        boolean first = !momentsPrimed;
        momentsPrimed = true;
        JSONObject sc = d.optJSONObject("score");
        int h = sc == null ? -1 : parseInt(sc.optString("home"));
        int a = sc == null ? -1 : parseInt(sc.optString("away"));
        String score = h >= 0 && a >= 0 ? name("home") + " " + h + " – " + a + " " + name("away") : null;
        JSONArray ms = d.optJSONArray("moments");
        if (d.has("moments")) {
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (m == null) continue;
                String kind = m.isNull("kind") ? null : m.optString("kind");
                String label = !m.isNull("label") && !m.optString("label").isEmpty() ? m.optString("label") : kind == null ? null : kind.replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
                if (label == null) continue;
                String at = m.isNull("at") ? label : m.optString("at");
                String detail = !m.isNull("detail") && !m.optString("detail").isEmpty() ? m.optString("detail") : !m.isNull("text") && !m.optString("text").isEmpty() ? m.optString("text") : score;
                if (seenMoments.add(at + "|" + label) && !first) sink.moment(label, m.isNull("team") ? null : m.optString("team"), detail);
            }
        } else if (h >= 0 && a >= 0 && lastHome >= 0 && lastAway >= 0 && !first && (h != lastHome || a != lastAway)) {
            int before = Integer.signum(lastHome - lastAway);
            int now = Integer.signum(h - a);
            String team = h > lastHome ? "home" : a > lastAway ? "away" : null;
            int delta = Math.max(h - lastHome, a - lastAway);
            if (before != 0 && now != 0 && before != now) sink.moment("LEAD CHANGE", team, score);
            else if (nfl && delta >= 6 && delta <= 8) sink.moment("TOUCHDOWN", team, score);
            else if (nfl && delta == 3) sink.moment("FIELD GOAL", team, score);
        }
        if (h >= 0 && a >= 0) {
            lastHome = h;
            lastAway = a;
        }
    }

    private String name(String side) {
        String n = sink.teamName(side);
        return n == null ? ("home".equals(side) ? "Home" : "Away") : n;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void fetchFantasy() {
        io.execute(() -> {
            CompanionHttp.Answer a = CompanionHttp.get(apiBase, token, "v1/fantasy/live");
            JSONObject got = null;
            if (a.body != null) {
                try {
                    got = new JSONObject(a.body);
                } catch (org.json.JSONException ignored) {
                    // skipped
                }
            }
            final JSONObject result = got;
            handler.post(() -> {
                if (!released && result != null) onFantasy(result);
            });
        });
    }

    private void onFantasy(JSONObject o) {
        JSONArray leagues = o.optJSONArray("leagues");
        if (leagues == null || leagues.length() == 0) return; // nothing linked
        JSONArray events = o.optJSONArray("events");
        boolean first = !primed;
        primed = true;
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || e.isNull("at") || e.isNull("name") || !e.has("delta")) continue;
            String league = e.isNull("league") ? null : e.optString("league");
            if (!seen.add(e.optString("at") + "|" + league + "|" + e.optString("name")) || first) continue;
            JSONObject l = null;
            for (int k = 0; k < leagues.length(); k++) {
                JSONObject c = leagues.optJSONObject(k);
                if (c != null && league != null && (league.equals(c.optString("name")) || league.equals(c.optString("id")))) l = c;
            }
            double delta = e.optDouble("delta");
            boolean opp = "opp".equals(e.optString("side"));
            StringBuilder t = new StringBuilder("🏈  ");
            if (opp) t.append("Opp ");
            t.append(delta >= 0 ? "+" : "").append(MatchPanel.pts(delta)).append(' ').append(e.optString("name"));
            String reason = e.isNull("reason") ? "" : e.optString("reason");
            if (!reason.isEmpty()) t.append(" · ").append(reason);
            String leagueName = l != null ? shortName(l.optString("name", league)) : league;
            if (leagueName != null && !leagueName.isEmpty()) t.append(" (").append(leagueName).append(')');
            if (l != null) {
                JSONObject me = l.optJSONObject("me");
                JSONObject op = l.optJSONObject("opp");
                double mine = me == null ? 0 : me.optDouble("points", 0);
                double theirs = op == null ? 0 : op.optDouble("points", 0);
                t.append(mine >= theirs ? " · you lead " : " · you trail ").append(MatchPanel.pts(mine)).append(" – ").append(MatchPanel.pts(theirs));
            }
            sink.alert(t.toString(), opp ? "opp" : "me");
        }
    }

    /** The league's first word ("CCP"), as the TV shows it. */
    private static String shortName(String name) {
        if (name == null) return null;
        String[] w = name.trim().split("\\s+");
        return w.length > 0 && !w[0].isEmpty() ? w[0] : name;
    }
}
