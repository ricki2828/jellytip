package com.github.damontecres.wholphin.jellytip.details

import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason

/** The episode an IPTV series' primary button plays: where it sits in the page's season list, and whether it resumes. */
data class IptvPlayTarget(
    /** Index into the series' season list (the season chip to open on). */
    val seasonIndex: Int,
    /** Index into that season's episode list (the tile to scroll to). */
    val episodeIndex: Int,
    val season: Int,
    val episode: IptvEpisode,
    /** A part-watched episode: the button says "Resume" and the play starts at its position. */
    val resumable: Boolean,
)

/**
 * Pure (unit tested): what the IPTV series primary button plays, from per-episode progress across all seasons.
 *
 *  1. a part-watched episode (resumable progress): the first in season / episode order (the IPTV model carries no
 *     last-played time, so "most recent" cannot be told)
 *  2. else the first unwatched episode after the last watched one
 *  3. else the first episode of the first real season (S1 E1)
 *
 * Specials (a season numbered 0) can be resumed in step 1 but are not part of the order in steps 2 and 3, unless the
 * series has nothing else. Seasons and episodes are ordered by number, whatever order the server sent them in. Null when
 * there is no episode at all.
 */
fun resolveIptvSeriesPlay(seasons: List<IptvSeason>): IptvPlayTarget? {
    data class Pos(
        val seasonIndex: Int,
        val episodeIndex: Int,
        val season: Int,
        val episode: IptvEpisode,
    )
    val all =
        seasons
            .withIndex()
            .sortedBy { it.value.season }
            .flatMap { (si, s) ->
                s.episodes.withIndex().sortedBy { it.value.episode }.map { (ei, e) -> Pos(si, ei, s.season, e) }
            }
    if (all.isEmpty()) return null

    fun target(
        p: Pos,
        resumable: Boolean,
    ) = IptvPlayTarget(p.seasonIndex, p.episodeIndex, p.season, p.episode, resumable)

    all.firstOrNull { it.episode.progress?.resumable == true }?.let { return target(it, true) }
    val ordered = all.filter { it.season != 0 }.ifEmpty { all }
    val lastWatched = ordered.indexOfLast { it.episode.progress?.played == true }
    if (lastWatched >= 0) {
        ordered.drop(lastWatched + 1).firstOrNull { it.episode.progress?.played != true }?.let { return target(it, false) }
    }
    return target(ordered.first(), false)
}
