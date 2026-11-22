package com.github.damontecres.wholphin.jellytip.details

import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import java.util.UUID

/*
 * Next up over a SPLIT series (part library, part IPTV): one ordered episode list, so "next" can be an IPTV-only episode.
 * Jellyfin's NextUp only knows library episodes, so after library S2E2 it says E6 although IPTV has E3-E5 (Celebrity
 * Traitors, 22-Nov). Pure and unit tested; shared by the series page (button + "Next" marker) and the player's Up next (t5n).
 */

/** A library episode as the merge sees it. */
data class SplitLibEp(
    val id: UUID,
    val season: Int?,
    val episode: Int?,
    val played: Boolean,
    val positionTicks: Long = 0L,
    /** A "missing" placeholder (no file): never a slot of its own, its IPTV copy (if any) takes the slot. */
    val virtual: Boolean = false,
)

/**
 * One slot of the merged list: season and episode number, the library copy (wins when it has a file) and / or the IPTV copy;
 * [played] when EITHER source says so (watched on IPTV or in the library is watched), [inProgress] when the copy that plays
 * has a resume point.
 */
data class SplitEp(
    val season: Int,
    val episode: Int,
    val lib: SplitLibEp?,
    val iptv: IptvEpisode?,
    val played: Boolean,
    val inProgress: Boolean,
)

/**
 * Every real episode (season >= 1, known numbers) of both sources in (season, episode) order. Specials (season 0) and
 * unnumbered library episodes are left out: next up never lands on them.
 */
fun splitEpisodes(
    library: List<SplitLibEp>,
    iptv: List<IptvSeason>,
): List<SplitEp> {
    val lib = library.filter { !it.virtual && (it.season ?: 0) >= 1 && it.episode != null }.associateBy { it.season!! to it.episode!! }
    val tv = iptv.filter { it.season >= 1 }.flatMap { s -> s.episodes.map { (s.season to it.episode) to it } }.toMap()
    return (lib.keys + tv.keys)
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { k ->
            val l = lib[k]
            val v = tv[k]
            val vPlayed = v?.progress?.played == true
            SplitEp(
                season = k.first,
                episode = k.second,
                lib = l,
                iptv = v,
                played = (l?.played == true) || vPlayed,
                inProgress = if (l != null) l.positionTicks > 0L else v?.progress?.resumable == true,
            )
        }
}

/**
 * The episode the series' primary button plays: the first one not yet watched after the LAST watched one (in order, either
 * source); nothing watched = the first episode; everything after the last watched one watched = null (the caller keeps
 * its own answer). A part-watched episode after the last watched one is that first not-watched one (Resume).
 */
fun splitNextUp(eps: List<SplitEp>): SplitEp? {
    if (eps.isEmpty()) return null
    val last = eps.indexOfLast { it.played }
    return eps.drop(last + 1).firstOrNull { !it.played }
}

/** The episode after [season]x[episode] in the merged order (library or IPTV), for the player's Up next; null = the last one. */
fun nextAfter(
    eps: List<SplitEp>,
    season: Int?,
    episode: Int?,
): SplitEp? {
    if (season == null || episode == null) return null
    return eps.firstOrNull { it.season > season || (it.season == season && it.episode > episode) }
}
