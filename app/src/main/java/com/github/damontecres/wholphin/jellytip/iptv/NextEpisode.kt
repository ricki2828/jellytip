package com.github.damontecres.wholphin.jellytip.iptv

/**
 * "Next episode" across a split series (Ricki 22-Nov, Celebrity Traitors on IPTV): the shared split-series list the series
 * page uses ([splitEpisodes], jellytip/details/JellytipSplitNext.kt: every real episode of both sources by (season, episode),
 * the library copy with a file wins a slot, "missing" placeholders give way, no Specials) and its [nextAfter]. This object
 * only converts the players' episodes in and out. Pure: both players and the tests use it.
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
        /** A Jellyfin "missing" placeholder (LocationType VIRTUAL): never played; an IPTV copy takes its slot. */
        val missing: Boolean = false,
        val played: Boolean = false,
    ) {
        /** "S2 E2" */
        val code: String get() = "S$season E$episode"
    }

    /** A library [Ep]'s id as the shared helper's UUID (real Jellyfin ids parse; anything else gets a stable one). */
    private fun uuidOf(id: String): java.util.UUID = runCatching { java.util.UUID.fromString(id) }.getOrNull() ?: java.util.UUID.nameUUIDFromBytes(id.toByteArray())

    /** The whole series in the shared split order, as [Ep]s. */
    fun merged(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<Ep> = slots(library, iptv).mapNotNull { (_, ep) -> ep }

    private fun slots(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<Pair<com.github.damontecres.wholphin.jellytip.details.SplitEp, Ep?>> {
        val byId = HashMap<java.util.UUID, Ep>()
        val lib =
            library.map { e ->
                val u = uuidOf(e.id)
                byId[u] = e
                com.github.damontecres.wholphin.jellytip.details.SplitLibEp(u, e.season, e.episode, e.played, e.resumeMs * 10_000, e.missing)
            }
        return com.github.damontecres.wholphin.jellytip.details
            .splitEpisodes(lib, iptv)
            .map { it to toEp(it, byId) }
    }

    private fun toEp(
        s: com.github.damontecres.wholphin.jellytip.details.SplitEp,
        byId: Map<java.util.UUID, Ep>,
    ): Ep? =
        s.lib?.let { byId[it.id] }
            ?: s.iptv?.let { Ep(Source.IPTV, it.id, s.season, it.episode, it.title, played = it.progress?.played == true) }

    /** The episode after [current] by the shared [nextAfter] (crossing seasons, either source); null after the last one. */
    fun after(
        current: Ep,
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): Ep? {
        val all = slots(library, iptv)
        val next = com.github.damontecres.wholphin.jellytip.details.nextAfter(all.map { it.first }, current.season, current.episode) ?: return null
        return all.firstOrNull { it.first === next }?.second
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
