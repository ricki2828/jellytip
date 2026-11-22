package com.github.damontecres.wholphin.jellytip.details

import java.time.LocalDateTime
import java.util.UUID

/*
 * Pure (unit tested) wording and choices for the library series details page: what the primary button plays and says,
 * the hero status line, the season chip names and the My list button. No Android, no BaseItem.
 */

/** "S2 E4", "E4" or null when the episode number is unknown (a season number alone names nothing playable). */
fun episodeCode(
    season: Int?,
    episode: Int?,
): String? =
    when {
        episode == null -> null
        season != null -> "S$season E$episode"
        else -> "E$episode"
    }

/**
 * The series primary button: "Resume S2 E4" for a part-watched episode, "Play S2 E4" otherwise, and just the verb when
 * the numbers are unknown. [resumeWord] / [playWord] come from string resources.
 */
fun seriesPlayLabel(
    season: Int?,
    episode: Int?,
    resumable: Boolean,
    resumeWord: String = "Resume",
    playWord: String = "Play",
): String {
    val verb = if (resumable) resumeWord else playWord
    val code = episodeCode(season, episode) ?: return verb
    return "$verb $code"
}

/**
 * Hero status line under the title: "3 seasons  ·  Next: S2 E4". Seasons count real seasons (Specials, season 0, is not
 * counted); "Next:" appears only when the resolver found an episode with a number. Null when there is nothing to say.
 */
fun seriesStatusLine(
    seasonNumbers: List<Int?>,
    nextSeason: Int?,
    nextEpisode: Int?,
): String? {
    val count = seasonNumbers.count { it != 0 }
    val seasons =
        when {
            count <= 0 -> null
            count == 1 -> "1 season"
            else -> "$count seasons"
        }
    val next = episodeCode(nextSeason, nextEpisode)?.let { "Next: $it" }
    return listOfNotNull(seasons, next).joinToString("  ·  ").takeIf { it.isNotEmpty() }
}

/** One season name everywhere: "Season 1", "Season 2", "Specials" (season 0). [position] (0-based) names a season with no number. */
fun seasonChipLabel(
    indexNumber: Int?,
    position: Int,
): String =
    when (indexNumber) {
        null -> "Season ${position + 1}"
        0 -> "Specials"
        else -> "Season $indexNumber"
    }

/** The favourite toggle on every details page is called My list (the same words the IPTV page uses). */
fun myListLabel(on: Boolean): String = if (on) "♥  In My list" else "♡  My list"

/** An episode as the play resolver sees it: plain values only. */
data class PlayCandidate(
    val id: UUID,
    val season: Int?,
    val episode: Int?,
    /** Resume position in ticks; 0 when not started. */
    val positionTicks: Long = 0L,
    val lastPlayed: LocalDateTime? = null,
)

/**
 * What the series primary button plays: the most recently played part-watched episode of this series, else the next-up
 * episode, else the first episode; null when none is known.
 *
 *  - [resume]: Jellyfin resume items for this series (anything with no resume position is ignored; ties on the date keep
 *    the server's order, which is most recent first)
 *  - [nextUp]: Jellyfin's next-up episode, which never offers a part-watched one
 *  - [first]: the first episode of the series
 *
 * Whether the button says "Resume" is the chosen candidate's own [PlayCandidate.positionTicks] > 0, so label and
 * action come from the one value.
 */
fun resolveSeriesPlay(
    resume: List<PlayCandidate>,
    nextUp: PlayCandidate?,
    first: PlayCandidate?,
): PlayCandidate? {
    val part = resume.filter { it.positionTicks > 0L }
    if (part.isNotEmpty()) {
        var best = part[0]
        for (c in part) {
            val a = c.lastPlayed
            val b = best.lastPlayed
            if (a != null && (b == null || a.isAfter(b))) best = c
        }
        return best
    }
    return nextUp ?: first
}
