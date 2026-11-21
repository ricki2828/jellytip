package com.github.damontecres.wholphin.jellytip.iptv

/*
 * Round 3 "wow" sport features (~/ai/state/fleet/jellytip-tv-wow-brief.md; proposals in
 * ~/ai/state/jellytip-tv-design-review-20261121.md): kick-off takeover (1B), full-time card with what's next (3), break mode
 * (4A, web lib/companion.ts autoOpen/autoClose) and catch me up (8). Pure policy only; tested in SportWowTest.
 */

/** 1. Kick-off takeover: which favourite fixture to announce now, and in which phase. */
object KickoffPolicy {
    /** Announce this long before kick-off ("KICK-OFF IN 2 MIN"). */
    const val SOON_SEC = 150L

    /** And once more at kick-off, while the game is this young. */
    const val LIVE_SEC = 300L

    const val SHOW_MS = 15_000L

    data class Due(
        val fixture: FxItem,
        /** soon | live */
        val phase: String,
    ) {
        val key get() = "${fixture.fid}:$phase"
    }

    /**
     * The first favourite fixture due an announcement: [nudged] holds "fid:phase" already shown; a fixture with a
     * reminder is the reminder banner's (no double nudge); one whose channel is playing is already being watched.
     * A "live" announcement also marks "soon" (never both for one game).
     */
    fun due(
        items: List<FxItem>,
        now: Long,
        nudged: Set<String>,
        playingChannel: String?,
    ): Due? {
        for (f in items.sortedBy { it.kickoff }) {
            if (!f.favourite || f.reminderId != null || f.channels.isEmpty()) continue
            if (playingChannel != null && f.channels.any { it.id == playingChannel }) continue
            val until = f.kickoff - now
            val phase =
                when {
                    f.status == "pre" && until in 1..SOON_SEC -> "soon"
                    (f.status == "in" || (f.status == "pre" && until <= 0)) && now - f.kickoff in 0..LIVE_SEC -> "live"
                    else -> null
                } ?: continue
            if ("${f.fid}:$phase" in nudged) continue
            if (phase == "soon" && "${f.fid}:live" in nudged) continue
            return Due(f, phase)
        }
        return null
    }

    /** "KICK-OFF IN 2 MIN" / "KICK-OFF NOW". */
    fun headline(
        d: Due,
        now: Long,
    ): String =
        if (d.phase == "live") {
            "Kick-off now"
        } else {
            "Kick-off in ${((d.fixture.kickoff - now + 59) / 60).coerceAtLeast(1)} min"
        }
}

/** 3. Full-time card: the next thing to watch. */
object FullTimePolicy {
    /** Upcoming games further away than this aren't "next". */
    const val NEXT_WITHIN_SEC = 60 * 60L

    /**
     * Next game: a live or soon favourite first, then a live game (server rank order), then the soonest upcoming
     * within the hour. Excludes the game just finished and games without a channel. Null = nothing (show the film pick).
     */
    fun nextGame(
        items: List<FxItem>,
        exclude: Set<String>,
        now: Long,
    ): FxItem? {
        val ok =
            items.filter { f ->
                f.fid !in exclude && (f.matchId == null || f.matchId !in exclude) && f.channels.isNotEmpty() &&
                    (f.status == "in" || (f.status == "pre" && f.kickoff - now in 0..NEXT_WITHIN_SEC))
            }
        return ok.firstOrNull { it.favourite && it.status == "in" }
            ?: ok.filter { it.favourite }.minByOrNull { it.kickoff }
            ?: ok.firstOrNull { it.status == "in" }
            ?: ok.filter { it.status == "pre" }.minByOrNull { it.kickoff }
    }

    fun nextLine(
        f: FxItem,
        now: Long,
    ): String =
        if (f.status == "in") {
            "Live now · OK to switch"
        } else {
            "Starts in ${((f.kickoff - now + 59) / 60).coerceAtLeast(1)} min · OK to switch"
        }
}

/** 4. Break mode: the panel opens itself at breaks and after big moments (web companion.ts). */
object BreakModePolicy {
    const val MOMENT_OPEN_MS = 6_000L
    const val USER_CLOSE_QUIET_MS = 120_000L

    /** An auto-opened panel ignores D-pad keys this long, so a press already under way keeps its meaning. */
    const val KEY_GUARD_MS = 800L

    enum class Reason { USER, BREAK, MOMENT }

    /**
     * Open by itself now? Never in replay or when open; a break the viewer closed stays closed for the rest of that
     * break ([closedBreak] = the break's key); a moment never re-opens within 2 min of a manual close, nor in a
     * blowout / final / pre.
     */
    fun autoOpen(
        enabled: Boolean,
        replay: Boolean,
        panelOpen: Boolean,
        kind: GameKind?,
        breakKey: String?,
        closedBreak: String?,
        userClosedAtMs: Long?,
        nowMs: Long,
        freshMoment: Boolean,
    ): Reason? {
        if (!enabled || replay || panelOpen || kind == null) return null
        if (kind == GameKind.BREAK) return if (breakKey != null && breakKey == closedBreak) null else Reason.BREAK
        if (userClosedAtMs != null && nowMs - userClosedAtMs < USER_CLOSE_QUIET_MS) return null
        if (freshMoment && kind != GameKind.BLOWOUT && kind != GameKind.FINAL && kind != GameKind.PRE) return Reason.MOMENT
        return null
    }

    /** Tuck an auto-opened panel away: a moment after 6 s, a break when play resumes; user-opened or touched never. */
    fun autoClose(
        openedBy: Reason,
        openedAtMs: Long,
        nowMs: Long,
        kind: GameKind?,
        touched: Boolean,
        replay: Boolean,
    ): Boolean {
        if (openedBy == Reason.USER || touched) return false
        if (replay) return true
        if (openedBy == Reason.MOMENT) return nowMs - openedAtMs >= MOMENT_OPEN_MS
        return kind != GameKind.BREAK
    }

    /** One break's identity: the period text of the released answer ("Half Time", "Innings break"…). */
    fun breakKey(
        period: String?,
        clock: String?,
    ): String = "${period ?: ""}|${clock?.takeIf { GameStateLogic.isBreak(it, null, "default") } ?: ""}"
}

/** 8. Catch me up when joining late. */
object CatchUpPolicy {
    /** Joining at least this far into a game offers the choice. */
    const val LATE_SEC = 10 * 60L
    const val CHOICE_TIMEOUT_MS = 20_000L
    const val CARD_MS = 5_000L

    /** Twelve cards at 5 s: the "60 s of key plays". */
    const val MAX_CARDS = 12

    fun offer(
        live: Boolean,
        replay: Boolean,
        spoilerNotice: Boolean,
        status: String?,
        startsAt: Long?,
        now: Long,
    ): Boolean = live && !replay && !spoilerNotice && status != "post" && status != "pre" && startsAt != null && now - startsAt >= LATE_SEC

    data class Card(
        val whenText: String,
        val text: String,
        val team: String?,
        val points: Int?,
    )

    /**
     * Key plays before now, oldest first, only what the reveal clock has released: the rugby league timeline (each
     * entry gated on its time), else the scoring plays of a released answer, else the released key moments.
     */
    fun cards(
        released: MatchLive?,
        latest: MatchLive?,
        reveal: (Double?) -> Boolean,
    ): List<Card> {
        val tl = (latest?.timeline ?: emptyList()).filter { it.at != null && reveal(it.at) }
        if (tl.isNotEmpty()) {
            val pts = mapOf("try" to 4, "conversion" to 2, "penalty_goal" to 2, "field_goal" to 1)
            return tl.takeLast(MAX_CARDS).map { Card(it.clock ?: "", it.text, it.team, pts[it.kind]) }
        }
        val plays = released?.plays?.filter { it.scoring }.orEmpty()
        if (plays.isNotEmpty()) return plays.take(MAX_CARDS).reversed().map { Card(listOfNotNull(it.period, it.clock).joinToString(" "), it.text, it.team, it.points) }
        val moments = (latest?.moments ?: emptyList()).filter { m -> stampSec(m.at)?.let(reveal) == true }
        return moments.takeLast(MAX_CARDS).map { Card("", it.detail?.let { d -> "${it.label} · $d" } ?: it.label, it.team, null) }
    }

    /** "YOU'RE JOINING 25 MINUTES IN". */
    fun joiningLine(
        startsAt: Long,
        now: Long,
    ): String {
        val m = ((now - startsAt) / 60).coerceAtLeast(1)
        return if (m >= 120) "You're joining ${m / 60} h ${m % 60} min in" else "You're joining $m minutes in"
    }
}
