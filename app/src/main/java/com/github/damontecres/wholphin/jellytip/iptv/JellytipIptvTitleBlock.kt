package com.github.damontecres.wholphin.jellytip.iptv

/**
 * The IPTV player's title block, the library player's equivalent (Fable's parity inventory §5, 22-Nov): the show's name as the
 * title, "S2 E5 · Episode title" under it, and the title logo top-left. Films: the title and the year. Pure (tested).
 */
object IptvTitleBlock {
    data class Block(
        val title: String,
        val subtitle: String?,
        val logo: String?,
    )

    /** "Episode 5", "Ep. 5", "S02E05", "Show - S02E05", "2x05": only the number again. */
    private val GENERIC =
        Regex("^(?:.*[-–·:|]\\s*)?(?:(?:episode|ep\\.?|e)\\s*\\d+|s\\d+\\s*e\\d+|\\d+x\\d+)$", RegexOption.IGNORE_CASE)

    /** [label] is the target's own label ("Show · Episode title" / "Film"): the fallback until (or when) the detail is missing. */
    fun of(
        label: String,
        detail: IptvVodDetail?,
        episodeId: String?,
    ): Block {
        if (detail == null) return fromLabel(label, episodeId != null)
        val name = detail.item.title.ifBlank { label }
        if (episodeId == null) return Block(name, detail.item.year?.toString(), detail.logo)
        for (s in detail.seasons) {
            val e = s.episodes.firstOrNull { it.id == episodeId } ?: continue
            return Block(name, episodeLine(s.season, e.episode, e.title, name), detail.logo)
        }
        // An episode the detail doesn't list (a newer episode, an older answer): the label's episode part
        return fromLabel(label, true).copy(title = name, logo = detail.logo)
    }

    /** "S2 E5 · Episode title"; the title is left out when it only repeats the number ("Episode 5"). */
    fun episodeLine(
        season: Int,
        episode: Int,
        title: String?,
        show: String? = null,
    ): String {
        val code = "S$season E$episode"
        val t = title?.trim().orEmpty()
        val same = show != null && t.equals(show.trim(), ignoreCase = true)
        return if (t.isEmpty() || same || GENERIC.matches(t)) code else "$code · $t"
    }

    private fun fromLabel(
        label: String,
        episode: Boolean,
    ): Block {
        if (!episode) return Block(label, null, null)
        val i = label.indexOf(" · ")
        return if (i > 0) Block(label.substring(0, i), label.substring(i + 3).ifBlank { null }, null) else Block(label, null, null)
    }
}
