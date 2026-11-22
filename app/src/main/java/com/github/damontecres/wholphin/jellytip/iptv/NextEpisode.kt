package com.github.damontecres.wholphin.jellytip.iptv

/**
 * "Next episode" across a split series (Ricki 22-Nov, Celebrity Traitors on IPTV): the same merge the library series page
 * shows. Whole seasons only IPTV has ([IptvSeasons.onlySeasons]), plus, inside a library season, the IPTV episodes the
 * library lacks ([IptvSeasons.interleave] rule: the library copy wins). No Specials from IPTV. Pure: both players and the
 * tests use it.
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
    ) {
        /** "S2 E2" */
        val code: String get() = "S$season E$episode"
    }

    /**
     * The whole series in watching order, by (season, episode): library episodes (season >= 1; real ones, the caller drops
     * "missing" placeholders), the IPTV-only seasons, and the IPTV episodes missing inside a library season. The same
     * (season, episode) twice: the library copy wins (then the first one listed).
     */
    fun merged(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<Ep> {
        val lib = library.filter { it.season >= 1 }
        val libSeasons = lib.map { it.season }.toSet()
        val only =
            IptvSeasons.onlySeasons(libSeasons, iptv).flatMap { s ->
                s.episodes.map { e -> Ep(Source.IPTV, e.id, s.season, e.episode, e.title) }
            }
        val fill =
            libSeasons.flatMap { sn ->
                val have = lib.filter { it.season == sn }.map { it.episode }.toSet()
                IptvSeasons.episodesFor(iptv, sn).filter { it.episode !in have }.map { e -> Ep(Source.IPTV, e.id, sn, e.episode, e.title) }
            }
        return (lib + only + fill)
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
