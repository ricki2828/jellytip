package com.github.damontecres.wholphin.jellytip.ask

/*
 * Ask over IPTV films and series (GET v1/iptv/vod, live 22-Nov with t5q's certifications / minRuntime / maxRuntime): port
 * of web/src/lib/askVod.ts (jellytip-web-askvod 7d8ca9e7), held to resources/jellytip/askvod-vectors.json. The same chips as
 * the library query become one IPTV query per kind plus a client-side check for what the route can't filter (genres:
 * items carry TMDB genre ids; exact years; played). Pure; the screen wiring comes with Fable's source-neutral tiles.
 */

/** The slice of an IPTV vod item the checks need. [kind] = "movie" or "series". */
data class VodView(
    val kind: String,
    val year: Int?,
    val genreIds: List<Int>,
    val certification: String?,
    val runtime: Int?,
    val played: Boolean?,
)

data class VodQuery(
    val kind: String,
    val params: Map<String, String>,
)

object AskVod {
    /** TMDB genre ids by the genre names Jellyfin uses (movie and TV lists). */
    val TMDB_GENRES =
        mapOf(
            "action" to 28, "adventure" to 12, "animation" to 16, "comedy" to 35, "crime" to 80, "documentary" to 99, "drama" to 18,
            "family" to 10751, "fantasy" to 14, "history" to 36, "horror" to 27, "music" to 10402, "mystery" to 9648, "romance" to 10749,
            "science fiction" to 878, "tv movie" to 10770, "thriller" to 53, "war" to 10752, "western" to 37, "action & adventure" to 10759,
            "kids" to 10762, "news" to 10763, "reality" to 10764, "sci-fi & fantasy" to 10765, "soap" to 10766, "talk" to 10767,
            "war & politics" to 10768,
        )

    /** How many IPTV titles one ask fetches per kind (the route's own maximum). */
    const val VOD_LIMIT = 200

    private inline fun <reified T : ChipData> List<AskChip>.of(): List<T> = mapNotNull { it.data as? T }

    private fun ids(genres: List<String>) = genres.mapNotNull { TMDB_GENRES[it.lowercase()] }.distinct()

    /** The IPTV queries for the chips: none with a person chip (no cast in the catalogue), else one per kind. */
    fun vodQueries(chips: List<AskChip>): List<VodQuery> {
        if (chips.of<ChipData.Person>().isNotEmpty()) return emptyList()
        val kinds = chips.of<ChipData.Kind>().map { it.kind }
        val wantSeasons = chips.of<ChipData.MaxSeasons>().isNotEmpty()
        val list =
            when {
                wantSeasons -> listOf("series")
                kinds.size == 1 -> listOf(if (kinds[0] == "Movie") "movie" else "series")
                else -> listOf("movie", "series")
            }
        val p = LinkedHashMap<String, String>()
        p["limit"] = VOD_LIMIT.toString()
        p["sort"] = "rating"
        // US ratings only on the route (NR / unrated never match): the kids rule's G / PG / TV-* list as it is
        chips.of<ChipData.Kids>().firstOrNull()?.let { k -> p["certifications"] = k.ratings.filter { it != "U" }.joinToString("|") }
        val maxRt = chips.of<ChipData.MaxRuntime>().minOfOrNull { it.min }
        val minRt = chips.of<ChipData.MinRuntime>().maxOfOrNull { it.min } ?: 0
        if (maxRt != null) p["maxRuntime"] = maxRt.toString()
        if (minRt > 0) p["minRuntime"] = minRt.toString()
        val minRating = chips.of<ChipData.MinRating>().maxOfOrNull { it.value } ?: 0.0
        if (minRating > 0) p["minRating"] = AskParse.num(minRating)
        val years = chips.of<ChipData.Years>()
        if (years.isNotEmpty()) p["year"] = "${years.minOf { it.from }}-${years.maxOf { it.to }}"
        return list.map { kind -> VodQuery(kind, linkedMapOf("kind" to kind) + p) }
    }

    /** The client-side check over the IPTV page (web vodKeep). */
    fun vodKeep(
        it: VodView,
        chips: List<AskChip>,
    ): Boolean {
        val have = it.genreIds.toSet()
        for (c in chips.of<ChipData.NotGenre>()) if (ids(c.genres).any { g -> g in have }) return false
        val moods = chips.of<ChipData.Genre>()
        if (!moods.all { c -> ids(c.genres).any { g -> g in have } }) return false
        val kg = AskParse.kidsGenres(chips)
        if (chips.of<ChipData.Kids>().isNotEmpty() && moods.isEmpty() && kg.isNotEmpty() && ids(kg).none { g -> g in have }) return false
        val years = chips.of<ChipData.Years>()
        if (years.isNotEmpty() && (it.year == null || years.none { c -> it.year in c.from..c.to })) return false
        if (chips.of<ChipData.Unplayed>().isNotEmpty() && it.played == true) return false
        if (chips.of<ChipData.Played>().isNotEmpty() && it.played != true) return false
        return true
    }

    /** One line about what the IPTV half could and couldn't apply. */
    fun vodNote(chips: List<AskChip>): String? {
        if (chips.of<ChipData.Person>().isNotEmpty()) return "IPTV isn't searched by director or actor."
        val parts = mutableListOf<String>()
        if (chips.of<ChipData.Kids>().isNotEmpty()) parts.add("IPTV titles without a known age rating are left out")
        if (chips.of<ChipData.MaxRuntime>().isNotEmpty() || chips.of<ChipData.MinRuntime>().isNotEmpty()) parts.add("IPTV titles without a known length are left out")
        if (chips.of<ChipData.MaxSeasons>().isNotEmpty()) parts.add("IPTV season counts aren't known")
        return if (parts.isEmpty()) null else "${parts.joinToString("; ")}."
    }
}
