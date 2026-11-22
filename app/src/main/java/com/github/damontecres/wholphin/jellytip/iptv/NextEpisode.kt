package com.github.damontecres.wholphin.jellytip.iptv

/**
 * "Next episode" across a split series (Ricki 22-Nov, Celebrity Traitors on IPTV): EXACTLY the series page's order
 * ([mergeSeasons] then [seasonTiles], jellytip/details/JellytipSeasonMerge.kt): library seasons in library order (Specials
 * where the library puts them), IPTV-only seasons slotted in by number, and inside a library season the IPTV episodes the
 * library lacks interleaved (library wins; a "missing" library placeholder gives way to the IPTV copy). IPTV season 0 is
 * ignored. Pure: both players and the tests use it.
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
        /** A library episode's own resume point (ms); 0 = from the start. */
        val resumeMs: Long = 0,
        /** A Jellyfin "missing" placeholder (LocationType VIRTUAL): a tile on the series page, never played. */
        val missing: Boolean = false,
    ) {
        /** "S2 E2" */
        val code: String get() = "S$season E$episode"
    }

    /**
     * The whole series in the series page's tile order. [library] = the library's episodes in library order (seasons in
     * the order they first appear, episodes in their order), "missing" placeholders included and marked.
     */
    fun merged(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<Ep> {
        val seasons = library.map { it.season }.distinct()
        val bySeason = library.groupBy { it.season }
        val refs = seasons.mapIndexed { i, n -> com.github.damontecres.wholphin.jellytip.details.LibrarySeasonRef(i, n) }
        return com.github.damontecres.wholphin.jellytip.details.mergeSeasons(refs, iptv).flatMap { m ->
            val libEps = m.libraryIndex?.let { bySeason[seasons[it]] }.orEmpty()
            com.github.damontecres.wholphin.jellytip.details
                .seasonTiles(m, libEps.size, true, { libEps[it].episode }, { libEps[it].missing })
                .map { t ->
                    when (t) {
                        is IptvSeasons.SeasonTile.Lib -> libEps[t.index]
                        is IptvSeasons.SeasonTile.Iptv -> Ep(Source.IPTV, t.episode.id, m.number ?: 0, t.episode.episode, t.episode.title)
                    }
                }
        }
    }

    /**
     * The first playable episode after [current] in [merged] order, crossing seasons; null after the last one. [current] is
     * found by source + id; when it isn't a tile (an IPTV copy the library wins over, or an unknown id) by its season and
     * episode number. "Missing" placeholders are skipped.
     */
    fun after(
        current: Ep,
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): Ep? {
        val list = merged(library, iptv)
        val at = list.indexOfFirst { it.source == current.source && it.id == current.id }
        val from = if (at >= 0) at + 1 else startAfter(list, current.season, current.episode)
        return list.drop(from).firstOrNull { !it.missing }
    }

    /** Where "after S[season] E[episode]" begins in [list] when that episode isn't a tile. */
    private fun startAfter(
        list: List<Ep>,
        season: Int,
        episode: Int,
    ): Int {
        val inSeason = list.indices.filter { list[it].season == season }
        if (inSeason.isNotEmpty()) {
            return inSeason.firstOrNull { list[it].episode > episode } ?: (inSeason.last() + 1)
        }
        // A season the list doesn't have: where the page would slot it (before the first higher-numbered season)
        return list.indexOfFirst { it.season > season }.takeIf { it >= 0 } ?: list.size
    }

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
 * The Up next card's timing (lead 22-Nov: IPTV has no credit markers, never cut playback early): shown in the last
 * [WINDOW_MS] of a known runtime (OK = play now, counting down the time left), auto-advance only at ENDED; with an unknown
 * runtime it shows at ENDED with the library player's countdown. Never after the person cancelled it, never without a next.
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
        if (!knownRuntime(durationMs)) return ended
        // Known runtime: only near its end, even at ENDED (a stream that stops at 10:00 of 60:00 is not the end)
        return durationMs - positionMs <= WINDOW_MS
    }

    /** A sane runtime only (a live-ish stream or a bad header reports 0 / huge); else the card waits for ENDED. */
    fun knownRuntime(durationMs: Long): Boolean = durationMs > WINDOW_MS && durationMs <= 6 * 3_600_000L

    /** Before ENDED: the card's number = whole seconds of the episode left (rounded up); null = no auto-advance. */
    fun secondsLeft(
        autoPlayNext: Boolean,
        positionMs: Long,
        durationMs: Long,
    ): Long? = if (autoPlayNext && knownRuntime(durationMs)) ((durationMs - positionMs).coerceAtLeast(0) + 999) / 1000 else null

    /**
     * At ENDED: seconds to count before auto-advancing. 0 when the card was already up before the end (it counted the
     * time left); else the library player's "auto play next" seconds. Null = no auto-advance (auto play next off): OK only.
     */
    fun endCountdown(
        autoPlayNext: Boolean,
        delaySeconds: Long,
        cardSeenBeforeEnd: Boolean,
    ): Long? =
        when {
            !autoPlayNext -> null
            cardSeenBeforeEnd -> 0L
            else -> delaySeconds.coerceAtLeast(0)
        }

    /**
     * The library series to look for, from an IPTV title: provider decorations off ("UK: Show", "Show (2024)",
     * "Show [4K]", "Show_ger"). The match itself is on the TMDB id.
     */
    fun searchTitle(title: String): String {
        var t = title.trim()
        t = t.replace(Regex("""^[A-Z]{2,3}\s*[:|]\s*"""), "")
        t = t.replace(Regex("""\s*\[[^\]]*]\s*"""), " ")
        // A trailing language token only when it is a known one (IptvSeasons' dub rule)
        if (IptvSeasons.foreignCopy(t)) t = t.replace(Regex("""[_\-\s]\(?[a-z]{2,3}\)?$""", RegexOption.IGNORE_CASE), "").trimEnd(' ', '-', '_')
        t = t.replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "")
        return t.trim().ifEmpty { title.trim() }
    }

    /** The library player's card (shown at its ENDED): its "auto play next" seconds; null = OK only. */
    fun countdownStart(
        autoPlayNext: Boolean,
        delaySeconds: Long,
    ): Long? = if (autoPlayNext) delaySeconds.coerceAtLeast(0) else null
}
