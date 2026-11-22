package com.github.damontecres.wholphin.jellytip.iptv

/**
 * "Next episode" across a split series (Ricki 22-Nov, Celebrity Traitors on IPTV): the library's episodes plus the seasons
 * only IPTV has, by the one split-series rule ([IptvSeasons.onlySeasons]: IPTV fills only seasons the library lacks, the
 * library wins per season, no Specials from IPTV). Pure: both players and the tests use it.
 */
object NextEpisode {
    enum class Source { LIBRARY, IPTV }

    /** An episode of either source. [id] = the Jellyfin item id (LIBRARY) or the IPTV episode id. */
    data class Ep(
        val source: Source,
        val id: String,
        val season: Int,
        val episode: Int,
        val title: String,
    ) {
        /** "S2 E2" */
        val code: String get() = "S$season E$episode"
    }

    /**
     * The whole series in watching order: library episodes (season >= 1) and the IPTV-only seasons, by (season, episode).
     * The same (season, episode) twice: the library copy wins (then the first one listed).
     */
    fun merged(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<Ep> {
        val lib = library.filter { it.season >= 1 }
        val only =
            IptvSeasons.onlySeasons(lib.map { it.season }.toSet(), iptv).flatMap { s ->
                s.episodes.map { e -> Ep(Source.IPTV, e.id, s.season, e.episode, e.title) }
            }
        return (lib + only)
            .withIndex()
            .sortedWith(compareBy({ it.value.season }, { it.value.episode }, { if (it.value.source == Source.LIBRARY) 0 else 1 }, { it.index }))
            .map { it.value }
            .distinctBy { it.season to it.episode }
    }

    /**
     * The first episode after [current] (by season, then episode; it may be from either source and need not be in the
     * merged list itself), crossing seasons and skipping gaps; null after the last one.
     */
    fun after(
        current: Ep,
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): Ep? = merged(library, iptv).firstOrNull { it.season > current.season || (it.season == current.season && it.episode > current.episode) }

    /** The IPTV episode [episodeId] as an [Ep], from the IPTV series' seasons; null when it isn't there. */
    fun iptvEpisode(
        iptv: List<IptvSeason>,
        episodeId: String,
    ): Ep? {
        for (s in iptv) {
            val e = s.episodes.firstOrNull { it.id == episodeId } ?: continue
            return Ep(Source.IPTV, e.id, s.season, e.episode, e.title)
        }
        return null
    }
}

/**
 * The Up next card's timing (same idea as the library player's next-up): shown in the last [WINDOW_MS] of a known runtime,
 * or once the stream ENDED when the runtime is unknown; never after the person cancelled it, never without a next.
 */
object UpNextTiming {
    const val WINDOW_MS = 30_000L

    fun showCard(
        hasNext: Boolean,
        cancelled: Boolean,
        ownsStream: Boolean,
        ended: Boolean,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (!hasNext || cancelled || !ownsStream) return false
        if (ended) return true
        // A sane runtime only (a live-ish stream or a bad header reports 0 / huge): else wait for ENDED
        if (durationMs <= WINDOW_MS || durationMs > 6 * 3_600_000L) return false
        return durationMs - positionMs in 0..WINDOW_MS
    }

    /** The countdown's start (the library player's "auto play next" seconds); null = no auto-advance, OK only. */
    fun countdownStart(
        autoPlayNext: Boolean,
        delaySeconds: Long,
    ): Long? = if (autoPlayNext) delaySeconds.coerceAtLeast(0) else null
}
