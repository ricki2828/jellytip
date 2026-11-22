package com.github.damontecres.wholphin.jellytip.details

import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeasons

/*
 * Pure (unit tested) rules for a series that is partly in the library and partly on IPTV: ONE ordered season list for
 * the chips, which source each season comes from, which tiles a season shows, and the small source marks. No Android.
 */

enum class SeasonSource { LIBRARY, IPTV, BOTH }

/** A library season as the merge sees it: its position in the library list (stable) and its number (null = unknown / not loaded). */
data class LibrarySeasonRef(
    val index: Int,
    val number: Int?,
)

/**
 * One chip of the merged list.
 *
 * @param number the season number (null only for a library season without one)
 * @param libraryIndex index into the library season list; null for an IPTV-only season
 * @param iptv the IPTV episodes of this season: all of an IPTV-only season, or the ones to interleave into a [SeasonSource.BOTH] season
 */
data class MergedSeason(
    val number: Int?,
    val source: SeasonSource,
    val libraryIndex: Int?,
    val iptv: IptvSeason?,
)

/**
 * Library seasons in library order (Specials wherever the library puts them, seasons without a number keep their place),
 * plus the IPTV-only seasons ([IptvSeasons.onlySeasons]: number >= 1, not in the library) slotted in by number: before the
 * first library season with a higher number, else after the last real season (so a library that lists Specials last keeps
 * them last), else at the end. A library season the IPTV copy also has episodes for is [SeasonSource.BOTH]; its episodes
 * are merged by [seasonTiles]. IPTV season 0 is ignored.
 */
fun mergeSeasons(
    library: List<LibrarySeasonRef>,
    iptv: List<IptvSeason>,
): List<MergedSeason> {
    val out = ArrayList<MergedSeason>(library.size + 2)
    for (ref in library) {
        val n = ref.number
        if (n != null && n >= 1) {
            val eps = IptvSeasons.episodesFor(iptv, n)
            if (eps.isNotEmpty()) {
                out += MergedSeason(n, SeasonSource.BOTH, ref.index, IptvSeason(n, eps))
                continue
            }
        }
        out += MergedSeason(n, SeasonSource.LIBRARY, ref.index, null)
    }
    for (s in IptvSeasons.onlySeasons(library.mapNotNull { it.number }.toSet(), iptv)) {
        val higher = out.indexOfFirst { (it.number ?: 0) > s.season }
        val lastReal = out.indexOfLast { (it.number ?: 0) >= 1 }
        val at =
            when {
                higher >= 0 -> higher
                lastReal >= 0 -> lastReal + 1
                else -> out.size
            }
        out.add(at, MergedSeason(s.season, SeasonSource.IPTV, null, s))
    }
    return out
}

/**
 * The chip that is selected, by IDENTITY not position: the IPTV-only season [iptvSeason] when it is in [merged], else the
 * library season at [libraryIndex]. A later IPTV answer inserts chips but cannot change which season this names.
 */
fun selectedMergedIndex(
    merged: List<MergedSeason>,
    libraryIndex: Int,
    iptvSeason: Int?,
): Int {
    if (iptvSeason != null) {
        val i = merged.indexOfFirst { it.source == SeasonSource.IPTV && it.number == iptvSeason }
        if (i >= 0) return i
    }
    return merged.indexOfFirst { it.libraryIndex == libraryIndex }.takeIf { it >= 0 } ?: 0
}

/** Above this many library episodes in a season the interleave is skipped (it reads every entry). */
const val MAX_INTERLEAVE = 200

/**
 * The tiles of the selected season, in order: an IPTV-only season shows its IPTV episodes; a library season shows its
 * episodes with the IPTV-only ones interleaved ([IptvSeasons.interleave]: library wins, a "missing" placeholder gives way
 * to the IPTV copy); a library-only season shows its episodes. [libraryReady] false (the list is still the previous
 * season's) or an over-long season skips the interleave.
 */
fun seasonTiles(
    entry: MergedSeason,
    libraryCount: Int,
    libraryReady: Boolean,
    libNumber: (Int) -> Int?,
    libVirtual: (Int) -> Boolean,
): List<IptvSeasons.SeasonTile> {
    if (entry.source == SeasonSource.IPTV) return entry.iptv?.episodes.orEmpty().map { IptvSeasons.SeasonTile.Iptv(it) }
    val extra = entry.iptv?.episodes
    if (extra.isNullOrEmpty() || !libraryReady || libraryCount > MAX_INTERLEAVE) {
        return List(libraryCount) { IptvSeasons.SeasonTile.Lib(it) }
    }
    return IptvSeasons.interleave(List(libraryCount) { libNumber(it) }, List(libraryCount) { libVirtual(it) }, extra)
}

/** The series is split only when an IPTV season exists or an IPTV episode is actually among the shown tiles. */
fun seriesIsSplit(
    merged: List<MergedSeason>,
    tiles: List<IptvSeasons.SeasonTile>,
): Boolean = merged.any { it.source == SeasonSource.IPTV } || tiles.any { it is IptvSeasons.SeasonTile.Iptv }

/** The hero badge of a split series; null (no badge) otherwise. */
fun splitSourceBadge(split: Boolean): String? = if (split) "Library + IPTV" else null

/**
 * The small line under an episode tile's title: nothing at all unless the series is [split]; then "Library" or "IPTV",
 * and an IPTV episode's failure note after the mark ("IPTV · Failed 2 h ago").
 */
fun episodeSourceMark(
    split: Boolean,
    iptv: Boolean,
    failNote: String? = null,
): String? =
    when {
        !split -> null
        !iptv -> "Library"
        failNote != null -> "IPTV · $failNote"
        else -> "IPTV"
    }

/** Where the page last was on the IPTV side: the picked IPTV-only season and the IPTV episode last played (Back lands on it). */
data class IptvSeasonPosition(
    val season: Int? = null,
    val episodeId: String? = null,
)
