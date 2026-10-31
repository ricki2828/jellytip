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
        int[] c = MatchStyle.colors(h, a);
        homeColor = c[0];
        awayColor = c[1];
    }

    /** "home" / "away" -> the team colour (brightened for the dark glass); anything else = neutral. */
    int side(String team) {
        if ("home".equals(team)) return MatchStyle.brighten(homeColor);
        if ("away".equals(team)) return MatchStyle.brighten(awayColor);
        return 0xFF8A90A0;
    }

    private static String s(JSONObject o, String k, String fallback) {
        if (o == null || !o.has(k) || o.isNull(k)) return fallback;
        String v = o.optString(k);
        return v.isEmpty() || "null".equals(v) ? fallback : v;
    }
}
