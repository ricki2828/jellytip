package com.github.damontecres.wholphin.jellytip.channels

import org.json.JSONObject

/*
 * Taste profile (GET /v1/me/taste) and the personal channels cut from it: ports of web lib/tasteProfile.ts and
 * lib/personalChannels.ts. Weights are -1..1; affinity() is 0..1 with 0.5 = no opinion.
 */

data class TasteProfile(
    val genres: Map<String, Double>,
    val decades: Map<String, Double>,
    val movie: Double,
    val series: Double,
)

object Taste {
    private val GENRE_ALIASES =
        mapOf(
            "sci-fi" to listOf("science fiction"),
            "scifi" to listOf("science fiction"),
            "sci-fi & fantasy" to listOf("science fiction", "fantasy"),
            "science fiction & fantasy" to listOf("science fiction", "fantasy"),
            "action & adventure" to listOf("action", "adventure"),
            "war & politics" to listOf("war"),
            "kids" to listOf("family"),
            "talk" to listOf("talk show"),
            "musical" to listOf("music"),
        )

    fun canonGenres(name: String): List<String> {
        val k = name.trim().lowercase()
        return GENRE_ALIASES[k] ?: listOf(k)
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0)

    private fun weights(
        v: Any?,
        genres: Boolean,
    ): Map<String, Double> {
        val o = v as? JSONObject ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (k in o.keys()) {
            val n = num(o.opt(k)) ?: continue
            if (k.isEmpty()) continue
            for (c in if (genres) canonGenres(k) else listOf(k.lowercase())) out[c] = maxOf(out[c] ?: -1.0, n)
        }
        return out
    }

    /** The answer of /v1/me/taste ({profile:{…}} or the profile itself); null when it says nothing. */
    fun parse(json: String?): TasteProfile? {
        val root = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return null
        val r = root.optJSONObject("profile") ?: root
        val m = r.optJSONObject("media")
        val p =
            TasteProfile(
                genres = weights(r.opt("genres"), true),
                decades = weights(r.opt("decades"), false),
                movie = num(m?.opt("movie")) ?: 0.0,
                series = num(m?.opt("series")) ?: 0.0,
            )
        return p.takeIf { it.genres.isNotEmpty() || it.decades.isNotEmpty() || it.movie != 0.0 || it.series != 0.0 }
    }

    fun topGenres(
        p: TasteProfile,
        n: Int = 5,
    ): List<String> =
        p.genres.entries
            .filter { it.value > 0 }
            .sortedWith { a, b -> b.value.compareTo(a.value).takeIf { it != 0 } ?: a.key.compareTo(b.key) }
            .take(n)
            .map { it.key }

    fun affinity(
        p: TasteProfile,
        genres: List<String>?,
        year: Int?,
        movie: Boolean,
    ): Double {
        val gs = (genres ?: emptyList()).flatMap { canonGenres(it) }.mapNotNull { p.genres[it] }
        val known = if (gs.isNotEmpty()) 0.6 * gs.max() + 0.4 * (gs.sum() / gs.size) else 0.0
        val dec = if (year != null && year != 0) p.decades[((year / 10) * 10).toString()] ?: 0.0 else 0.0
        val lean = if (movie) p.movie else p.series
        val blend = 0.65 * known + 0.2 * dec + 0.15 * lean
        return (0.5 + 0.5 * blend).coerceIn(0.0, 1.0)
    }
}

object PersonalChannels {
    const val FIT_MIN = 0.55
    private const val FILMS_MAX = 40
    private const val SERIES_MAX = 8
    private const val PER_SERIES = 12
    private const val GENRE_MIN_ITEMS = 6
    const val FOR_YOU_ID = "for-you"
    const val GENRE_ID = "for-you-genre"
    const val SHOWS_ID = "your-shows"

    fun unionOf(base: Map<String, List<ChanItem>>): List<ChanItem> {
        val seen = HashSet<String>()
        val out = ArrayList<ChanItem>()
        for (c in CHANNELS) {
            for (it in Channels.playable(base[c.id] ?: emptyList())) {
                if (!seen.add(it.id)) continue
                out += it
            }
        }
        return out
    }

    private val byAffDesc =
        Comparator<ChanItem> { a, b -> (b.aff ?: 0.5).compareTo(a.aff ?: 0.5).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

    private fun topSeries(
        eps: List<ChanItem>,
        seriesMax: Int,
        per: Int,
    ): List<ChanItem> {
        val by = LinkedHashMap<String, MutableList<ChanItem>>()
        for (e in eps) e.seriesId?.let { by.getOrPut(it) { ArrayList() } += e }
        data class G(
            val sid: String,
            val l: List<ChanItem>,
            val a: Double,
            val lib: Int,
        )
        return by.entries
            .map { (sid, l) -> G(sid, l, Channels.meanAff(l) ?: 0.5, if (Channels.isIptvId(l[0].id)) 0 else 1) }
            .sortedWith { p, q -> q.a.compareTo(p.a).takeIf { it != 0 } ?: (q.lib - p.lib).takeIf { it != 0 } ?: (if (p.sid < q.sid) -1 else 1) }
            .take(seriesMax)
            .flatMap { g ->
                g.l
                    .map { Channels.hashString("you|${it.id}") to it }
                    .sortedWith { p, q -> p.first.compareTo(q.first).takeIf { it != 0 } ?: (if (p.second.id < q.second.id) -1 else 1) }
                    .take(per)
                    .map { it.second }
                    .sortedWith(Channels.byEpisodeOrder)
            }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun hasGenre(
        it: ChanItem,
        g: String,
    ) = (it.genres ?: emptyList()).flatMap { Taste.canonGenres(it) }.contains(g)

    fun personalItems(
        def: ChannelDef,
        base: Map<String, List<ChanItem>>,
        genre: String? = null,
    ): List<ChanItem> {
        val all = unionOf(base)
        return when (def.personal) {
            Personal.FOR_YOU -> {
                val fit = all.filter { (it.aff ?: 0.0) >= FIT_MIN }
                fit.filter { it.seriesId == null }.sortedWith(byAffDesc).take(FILMS_MAX) + topSeries(fit.filter { it.seriesId != null }, SERIES_MAX, PER_SERIES)
            }
            Personal.GENRE -> {
                val g = (genre ?: def.genres?.firstOrNull() ?: "").lowercase()
                val fit = all.filter { hasGenre(it, g) && (it.aff ?: 0.5) >= 0.5 }
                fit.filter { it.seriesId == null }.sortedWith(byAffDesc).take(FILMS_MAX) + topSeries(fit.filter { it.seriesId != null }, SERIES_MAX, PER_SERIES)
            }
            Personal.SHOWS -> topSeries(all.filter { it.seriesId != null && (it.aff ?: 0.0) >= FIT_MIN }, SERIES_MAX, PER_SERIES)
            null -> emptyList()
        }
    }

    private fun viable(items: List<ChanItem>) = Channels.playable(items).size >= MIN_ITEMS

    /** "For You", "<Genre> for you", "Your shows" — each only when it has enough to play. Empty without a profile. */
    fun personalDefs(
        profile: TasteProfile?,
        base: Map<String, List<ChanItem>>,
    ): List<ChannelDef> {
        if (profile == null) return emptyList()
        val defs = ArrayList<ChannelDef>()
        val foryou = ChannelDef(FOR_YOU_ID, "For You", "Films and shows picked from what you watch and like", ChanType.MOVIE, personal = Personal.FOR_YOU)
        if (viable(personalItems(foryou, base))) defs += foryou
        for (g in Taste.topGenres(profile, 5)) {
            val probe = ChannelDef(GENRE_ID, "${cap(g)} for you", "Your favourite genre, picked for you", ChanType.MOVIE, genres = listOf(g), personal = Personal.GENRE)
            val items = personalItems(probe, base, g)
            if (items.size >= GENRE_MIN_ITEMS && viable(items)) {
                defs += probe
                break
            }
        }
        val shows = ChannelDef(SHOWS_ID, "Your shows", "Shows you watch, and ones like them", ChanType.EPISODE, personal = Personal.SHOWS)
        if (viable(personalItems(shows, base))) defs += shows
        return defs
    }
}
