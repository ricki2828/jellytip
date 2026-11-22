package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.iptv.IptvSource

/*
 * One hold-OK menu for a title, whichever source the tile came from. Pure Kotlin (no Compose, no resources), unit tested.
 *
 * The COMMON block is built here once, so a library tile and an IPTV tile show the same entries in the same order with
 * the same words; each dialog maps an entry's [Entry.id] to the source-appropriate action:
 *
 *   Play (Resume when in progress) · Play from start (in progress only) · Details · My list · Mark as watched / unwatched
 *   · I like this · Not for me · Not interested · Play from IPTV · 4K / Play from library (only with a second source)
 *
 * Source-only extras (library: playlist, subtitles, delete, Remove from Continue Watching, Go to series, media information;
 * IPTV: none today) are appended by the dialog AFTER this block, unchanged.
 *
 * The words are the library menu's existing ones ("Play", "Resume", "Mark as watched", the taste lines); the English text
 * is repeated here because the model has no resources (the taste lines were hard-coded in the library menu already).
 */
object JellytipTitleMenu {
    object Id {
        const val PLAY = "play"
        const val PLAY_FROM_START = "play-from-start"
        const val DETAILS = "details"
        const val MY_LIST = "my-list"
        const val WATCHED = "watched"
        const val LIKE = "like"
        const val NOT_FOR_ME = "not-for-me"
        const val NOT_INTERESTED = "not-interested"
        const val PLAY_OTHER = "play-other"
    }

    data class Entry(
        val id: String,
        val label: String,
        val enabled: Boolean = true,
    )

    enum class Direction { TO_IPTV, TO_LIBRARY }

    /** The title's second source: where "Play from ..." goes, and (to IPTV) the best copy's quality word when known. */
    data class Other(
        val direction: Direction,
        val quality: String? = null,
    )

    data class State(
        /** Play / Resume / Play from start are offered (a film, an episode, a series the source can start). */
        val canPlay: Boolean,
        val inProgress: Boolean,
        val inMyList: Boolean,
        val watched: Boolean,
        /** The watched line (an IPTV title can only be marked while the person's taste service is available). */
        val showWatched: Boolean = true,
        /** The "Details" line (a menu on the details page itself leaves it out). */
        val showDetails: Boolean = true,
        /** The "My list" line (the details page has a My list button of its own). */
        val showMyList: Boolean = true,
        /** Like / Not for me / Not interested (the person's taste signals are available). */
        val tasteAvailable: Boolean = false,
        /** 1 = liked, -1 = not for me, 0 = none. */
        val thumb: Int = 0,
        val notInterested: Boolean = false,
        val other: Other? = null,
    )

    const val PLAY = "Play"
    const val RESUME = "Resume"
    const val PLAY_FROM_START = "Play from start"
    const val DETAILS = "Details"
    const val ADD_MY_LIST = "Add to My list"
    const val REMOVE_MY_LIST = "Remove from My list"
    const val MARK_WATCHED = "Mark as watched"
    const val MARK_UNWATCHED = "Mark as unwatched"
    const val LIKE = "I like this"
    const val LIKED_UNDO = "Liked · undo"
    const val NOT_FOR_ME = "Not for me"
    const val NOT_FOR_ME_UNDO = "Not for me · undo"
    const val NOT_INTERESTED = "Not interested (hide from rows)"
    const val NOT_INTERESTED_UNDO = "Show it in my rows again"
    const val PLAY_FROM_IPTV = "Play from IPTV"
    const val PLAY_FROM_LIBRARY = "Play from library"

    /**
     * The device-language word for a label whose wording did not change from the library menu's own strings (Play, Resume,
     * Mark as watched / unwatched); the newly worded lines stay English constants.
     */
    fun localized(
        label: String,
        resources: android.content.res.Resources,
    ): String =
        when (label) {
            PLAY -> resources.getString(com.github.damontecres.wholphin.R.string.play)
            RESUME -> resources.getString(com.github.damontecres.wholphin.R.string.resume)
            MARK_WATCHED -> resources.getString(com.github.damontecres.wholphin.R.string.mark_watched)
            MARK_UNWATCHED -> resources.getString(com.github.damontecres.wholphin.R.string.mark_unwatched)
            else -> label
        }

    fun otherLabel(other: Other): String =
        when (other.direction) {
            Direction.TO_LIBRARY -> PLAY_FROM_LIBRARY
            Direction.TO_IPTV -> other.quality?.takeIf { it.isNotBlank() }?.let { "$PLAY_FROM_IPTV · $it" } ?: PLAY_FROM_IPTV
        }

    /** The block both sources share, in order. */
    fun common(s: State): List<Entry> =
        buildList {
            if (s.canPlay) {
                add(Entry(Id.PLAY, if (s.inProgress) RESUME else PLAY))
                if (s.inProgress) add(Entry(Id.PLAY_FROM_START, PLAY_FROM_START))
            }
            if (s.showDetails) add(Entry(Id.DETAILS, DETAILS))
            if (s.showMyList) add(Entry(Id.MY_LIST, if (s.inMyList) REMOVE_MY_LIST else ADD_MY_LIST))
            if (s.showWatched) add(Entry(Id.WATCHED, if (s.watched) MARK_UNWATCHED else MARK_WATCHED))
            if (s.tasteAvailable) {
                add(Entry(Id.LIKE, if (s.thumb == 1) LIKED_UNDO else LIKE))
                add(Entry(Id.NOT_FOR_ME, if (s.thumb == -1) NOT_FOR_ME_UNDO else NOT_FOR_ME))
                add(Entry(Id.NOT_INTERESTED, if (s.notInterested) NOT_INTERESTED_UNDO else NOT_INTERESTED))
            }
            s.other?.let { add(Entry(Id.PLAY_OTHER, otherLabel(it))) }
        }

    /** A library tile's menu: the common block, then [extras] (playlist, delete, Go to series ...) as the library menu has them. */
    fun forLibrary(
        s: State,
        extras: List<Entry> = emptyList(),
    ): List<Entry> = common(s) + extras

    /** An IPTV tile's menu: the common block, then [extras] (none today). */
    fun forIptv(
        s: State,
        extras: List<Entry> = emptyList(),
    ): List<Entry> = common(s) + extras

    /** "4K" / "HDR" / the token as sent; null when unknown. Same words as `IptvVodItem.qualityLabel`. */
    fun qualityWord(quality: List<String>): String? =
        quality.firstOrNull { it.isNotBlank() }?.let {
            when (it.lowercase()) {
                "4k", "uhd" -> "4K"
                "hdr" -> "HDR"
                else -> it
            }
        }

    /**
     * The IPTV copy "Play from IPTV" plays: the server ranks `copies` best first, so this is the first IPTV source that has not
     * failed lately ([failedLately] = the client's own 24 h memory); when all of them failed, the first one. Null = no IPTV copy.
     * No client-side ranking.
     */
    fun bestIptv(
        sources: List<IptvSource>,
        failedLately: (String) -> Boolean = { false },
    ): IptvSource? {
        val iptv = sources.filter { it.type == "iptv" && it.id.isNotBlank() }
        return iptv.firstOrNull { !failedLately(it.id) } ?: iptv.firstOrNull()
    }

    /** The [Other] for a library tile with IPTV copies [sources]; null when there is none. */
    fun otherForLibraryTile(
        sources: List<IptvSource>,
        failedLately: (String) -> Boolean = { false },
    ): Other? = bestIptv(sources, failedLately)?.let { Other(Direction.TO_IPTV, qualityWord(it.quality)) }
}
