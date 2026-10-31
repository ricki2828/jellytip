package com.jellytip.phone;

import org.json.JSONObject;

/** The two teams as the live views show them (from match info): names, crests, colours; neutral until info loads. */
final class LiveTeams {
    final String homeName;
    final String awayName;
    final String homeShort;
    final String awayShort;
    final String homeLogo;
    final String awayLogo;
    final int homeColor;
    final int awayColor;
    /** The server sent team colours (else neutral slabs and no wash). */
    final boolean known;
    /** An event (F1, golf; plan "Live coverage round 2"): no home/away, [title] is the hero, colours are the series'. */
    final boolean event;
    final String title;
    final String league;
    /** Tennis: flags instead of crests (ISO 2/3 letters); null = none. */
    final String homeCountry;
    final String awayCountry;

    static final LiveTeams NONE = new LiveTeams(null);

    LiveTeams(JSONObject info) {
        JSONObject h = info == null ? null : info.optJSONObject("home");
        JSONObject a = info == null ? null : info.optJSONObject("away");
        homeName = s(h, "name", "Home");
        awayName = s(a, "name", "Away");
        homeShort = s(h, "short", homeName);
        awayShort = s(a, "short", awayName);
        homeLogo = s(h, "logo", null);
        awayLogo = s(a, "logo", null);
        event = info != null && ("event".equals(s(info, "kind", null)) || (h == null && a == null && s(info, "title", null) != null));
        title = s(info, "title", null);
        league = s(info, "league", null);
        homeCountry = s(h, "country", null);
        awayCountry = s(a, "country", null);
        String leagueKey = s(info, "leagueKey", null);
        Integer series = leagueKey == null ? null : "f1".equals(leagueKey) ? Integer.valueOf(0xFFE10600) : leagueKey.startsWith("golf") ? Integer.valueOf(0xFF0B6E4F) : null;
        String hc = event ? null : s(h, "color", null);
        String ac = event ? null : s(a, "color", null);
        known = event ? series != null : hc != null || ac != null;
        int home = MatchStyle.color(hc, 0xFF2A303B);
        int away = MatchStyle.color(ac, 0xFF1C212A);
        // Clash rule: primaries closer than ΔE 20 -> the away side's alt colour (or neutral)
        if (hc != null && ac != null && Broadcast.deltaE(home, away) < 20) {
            int alt = MatchStyle.color(s(a, "altColor", null), 0xFF1C212A);
            away = Broadcast.deltaE(home, alt) >= 20 ? alt : 0xFF1C212A;
        }
        homeColor = event && series != null ? series : home;
        awayColor = event && series != null ? series : away;
    }

    /** "home" / "away" -> the team colour (bars, ticks); without team colours gold v grey so splits still read. */
    int side(String team) {
        if ("home".equals(team)) return known ? homeColor : Broadcast.GOLD;
        if ("away".equals(team)) return known ? awayColor : Broadcast.SECONDARY;
        return Broadcast.SECONDARY;
    }

    private static String s(JSONObject o, String k, String fallback) {
        if (o == null || !o.has(k) || o.isNull(k)) return fallback;
        String v = o.optString(k);
        return v.isEmpty() || "null".equals(v) ? fallback : v;
    }
}
