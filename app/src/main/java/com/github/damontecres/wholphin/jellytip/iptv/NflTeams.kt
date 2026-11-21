package com.github.damontecres.wholphin.jellytip.iptv

/**
 * One NFL team code whatever the feed: fantasy starters carry the server's Sleeper-style code (jellytip-api fantasy.py
 * team_code: WAS, JAX, LAR...), fixtures carry ESPN's abbreviation (WSH...). Same alias table as the server's TEAM_ALIAS,
 * so both sides compare equal.
 */
object NflTeams {
    /** The 32 canonical (Sleeper) codes. */
    val CODES =
        setOf(
            "ARI", "ATL", "BAL", "BUF", "CAR", "CHI", "CIN", "CLE", "DAL", "DEN", "DET", "GB", "HOU", "IND", "JAX", "KC",
            "LAC", "LAR", "LV", "MIA", "MIN", "NE", "NO", "NYG", "NYJ", "PHI", "PIT", "SEA", "SF", "TB", "TEN", "WAS",
        )

    /** Other feeds' codes -> canonical (ESPN WSH; old/relocated LA, SD, OAK, STL; Sleeper/Yahoo variants). */
    val ALIAS =
        mapOf(
            "WSH" to "WAS", "JAC" to "JAX", "LA" to "LAR", "ARZ" to "ARI", "BLT" to "BAL", "CLV" to "CLE", "HST" to "HOU",
            "SD" to "LAC", "OAK" to "LV", "STL" to "LAR",
        )

    /** Canonical code, or the trimmed upper-case input when unknown; null for blank. */
    fun code(t: String?): String? {
        val s = t?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALIAS[s] ?: s
    }

    fun same(
        a: String?,
        b: String?,
    ): Boolean {
        val x = code(a) ?: return false
        return x == code(b)
    }
}
