package com.github.damontecres.wholphin.jellytip.iptv

/*
 * Ambient scoreboard (round 3 item 9, look 9): which game the idle screen shows and where the card sits. Pure; tested in
 * SportAmbientTest. Spoiler-safe by construction: the card never carries a score (the fixtures answer's score is live,
 * not held to the viewer's delay, so there is nothing safe to show). Burn-in: the whole card moves to a new spot every
 * few minutes and the team-colour wash is static and dim.
 */
object SportAmbient {
    /** A favourite's game this close to kick-off counts ("KICK-OFF IN 8 MIN"). */
    const val SOON_S = 15 * 60L

    /** One fixtures call a minute while the screensaver shows; never faster. */
    const val POLL_MS = 60_000L

    /** The card moves this often (burn-in). */
    const val DRIFT_MS = 3 * 60_000L

    data class Pick(
        val f: FxItem,
        val live: Boolean,
        /** "ON NOW" or "KICK-OFF IN 8 MIN" / "KICK-OFF NOW". */
        val badge: String,
        /** "NRL · OK TO WATCH" (no OK hint when nothing can be watched). */
        val line: String,
    )

    /**
     * The favourite (starred team) game to show: live first, then the soonest starting within [SOON_S]; ties go to the
     * server rank. Null = no favourite on, the normal screensaver stays.
     */
    fun pick(
        items: List<FxItem>,
        nowS: Long,
    ): Pick? {
        val cands =
            items.filter { f ->
                f.favourite &&
                    (f.status == "in" || (f.status == "pre" && f.kickoff - nowS <= SOON_S && f.kickoff - nowS > -SOON_S))
            }
        val best =
            cands.minWithOrNull(
                compareByDescending<FxItem> { it.status == "in" }
                    .thenBy { if (it.status == "in") 0L else it.kickoff }
                    .thenBy { it.rank ?: Double.MAX_VALUE }
                    .thenByDescending { it.significance }
                    .thenBy { it.fid },
            ) ?: return null
        val live = best.status == "in"
        val watchable = SportFixtures.pickChannel(best, nowS).channel != null
        val mins = (best.kickoff - nowS + 59) / 60
        val badge = if (live) "ON NOW" else if (mins <= 0) "KICK-OFF NOW" else "KICK-OFF IN $mins MIN"
        val league = best.league?.uppercase()
        val line = listOfNotNull(league, if (watchable) "OK TO WATCH" else "NO STREAM YET").joinToString(" · ")
        return Pick(best, live, badge, line)
    }

    /** Three-letter crest text when the server has no short name. */
    fun short(
        name: String,
        given: String?,
    ): String =
        given?.takeIf { it.isNotBlank() }?.uppercase()
            ?: name
                .filter { it.isLetter() }
                .take(3)
                .uppercase()
                .ifEmpty { "?" }

    /**
     * Card offset as fractions of the free room (-1..1 each way) for drift step [step]: a fixed walk over nine spots,
     * never the same spot twice in a row, so no pixel holds the same bright text for long.
     */
    fun drift(step: Long): Pair<Float, Float> {
        val spots =
            listOf(
                0f to 0f, -0.8f to -0.6f, 0.7f to 0.5f, -0.5f to 0.7f, 0.8f to -0.7f,
                0f to 0.8f, -0.8f to 0.2f, 0.6f to -0.2f, 0.1f to -0.8f,
            )
        return spots[(step.mod(spots.size.toLong())).toInt()]
    }
}
