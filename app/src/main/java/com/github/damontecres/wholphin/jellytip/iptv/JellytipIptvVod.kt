package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.JellytipApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Resume point from the companion (seconds). */
data class IptvProgress(
    val positionSecs: Double,
    val durationSecs: Double,
    val played: Boolean,
) {
    /** Worth resuming: started, not (nearly) finished. */
    val resumable get() = !played && positionSecs > 30 && (durationSecs <= 0 || positionSecs < durationSecs * 0.9)
}

/** An IPTV film or series (`source:"iptv"`): rows fill-ins, franchise gap-fill and the "via IPTV" details page. */
data class IptvVodItem(
    val id: String,
    /** "movie" | "series" */
    val kind: String,
    val tmdbId: Int?,
    val title: String,
    val year: Int?,
    /** e.g. ["4k", "hdr"]; best first. */
    val quality: List<String>,
    val poster: String?,
    val backdrop: String?,
    val overview: String,
    val rating: Double?,
    val providerName: String?,
    val progress: IptvProgress?,
    /** Premium formats from the title/category ("4K", "DV", "HDR10+", "HDR", "ATMOS", "DTSX"). */
    val tech: List<String> = emptyList(),
    /** In the person's My list (`favourite`, plan "My list"); false = not, or an older server. */
    val favourite: Boolean = false,
) {
    val isSeries get() = kind == "series"

    /** Short quality label for tiles: "4K", "HDR", "1080p"… */
    val qualityLabel: String?
        get() =
            quality.firstOrNull()?.let {
                when (it.lowercase()) {
                    "4k", "uhd" -> "4K"
                    "hdr" -> "HDR"
                    else -> it
                }
            }
}

data class IptvEpisode(
    val id: String,
    val episode: Int,
    val title: String,
    val runtimeMin: Int?,
    val overview: String?,
    val still: String?,
    val progress: IptvProgress?,
)

data class IptvSeason(
    val season: Int,
    val episodes: List<IptvEpisode>,
)

data class IptvVodDetail(
    val item: IptvVodItem,
    val runtimeMin: Int?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val seasons: List<IptvSeason>,
)

object IptvVodParse {
    /** Companion base for relative image paths in payloads parsed without one (rows); updated once it is known. */
    @Volatile
    var imageBase: String = com.github.damontecres.wholphin.jellytip.JellytipPreferences.DEFAULT_API_URL

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** TMDB images are absolute https; provider images come as signed relative companion paths. */
    fun image(
        base: String,
        raw: String?,
    ): String? =
        when {
            raw.isNullOrBlank() -> null
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw) || raw.startsWith("//") -> null
            else -> "$base/${raw.trimStart('/')}"
        }

    fun progress(o: JSONObject?): IptvProgress? {
        o ?: return null
        if (o.isNull("position")) return null
        return IptvProgress(o.optDouble("position", 0.0), o.optDouble("duration", 0.0), o.optBoolean("played", false))
    }

    fun item(
        o: JSONObject?,
        base: String,
    ): IptvVodItem? {
        o ?: return null
        val id = o.str("id") ?: return null
        val q = o.optJSONArray("quality") ?: JSONArray()
        return IptvVodItem(
            id = id,
            kind = o.str("kind") ?: if (id.contains(":s:")) "series" else "movie",
            tmdbId = o.optInt("tmdbId", 0).takeIf { it > 0 },
            title = o.str("title") ?: "Untitled",
            year = o.optInt("year", 0).takeIf { it > 0 },
            quality = (0 until q.length()).mapNotNull { q.optString(it).takeIf { s -> s.isNotBlank() } },
            poster = image(base, o.str("poster")),
            backdrop = image(base, o.str("backdrop")),
            overview = o.str("overview").orEmpty(),
            rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
            providerName = o.str("providerName"),
            progress = progress(o.optJSONObject("progress")),
            tech = o.optJSONArray("tech")?.let { t -> (0 until t.length()).mapNotNull { t.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty(),
            favourite = o.optBoolean("favourite", false),
        )
    }

    fun items(
        a: JSONArray?,
        base: String,
    ): List<IptvVodItem> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { item(a.optJSONObject(it), base) }

    fun detail(
        json: String,
        base: String,
    ): IptvVodDetail? {
        val o = JSONObject(json)
        val item = item(o, base) ?: return null
        val seasons =
            o.optJSONArray("seasons")?.let { sa ->
                (0 until sa.length()).mapNotNull { i ->
                    val s = sa.optJSONObject(i) ?: return@mapNotNull null
                    val ea = s.optJSONArray("episodes") ?: JSONArray()
                    IptvSeason(
                        s.optInt("season", i + 1),
                        (0 until ea.length()).mapNotNull { j ->
                            val e = ea.optJSONObject(j) ?: return@mapNotNull null
                            IptvEpisode(
                                id = e.str("id") ?: return@mapNotNull null,
                                episode = e.optInt("episode", j + 1),
                                title = e.str("title") ?: "Episode ${e.optInt("episode", j + 1)}",
                                runtimeMin = e.optInt("runtime", 0).takeIf { it > 0 },
                                overview = e.str("overview"),
                                still = image(base, e.str("still")),
                                progress = progress(e.optJSONObject("progress")),
                            )
                        },
                    )
                }
            }.orEmpty()
        return IptvVodDetail(
            item = item,
            runtimeMin = o.optInt("runtime", 0).takeIf { it > 0 },
            genre = o.str("genre"),
            director = o.str("director"),
            cast = o.str("cast"),
            seasons = seasons,
        )
    }
}

/** IPTV films / series from the companion; images and ids exactly as the contract gives them. */
@Singleton
class JellytipIptvVodService
    @Inject
    constructor(
        private val api: JellytipApi,
    ) {
        @Volatile
        private var base: String? = null

        suspend fun base(): String =
            base ?: api.companionBase().also {
                base = it
                IptvVodParse.imageBase = it
            }

        /** Base already known (rows parse after the first companion call); null before that. */
        fun baseOrNull(): String? = base

        /**
         * My list on/off for an IPTV title (`POST /v1/iptv/vod/{id}/favourite {on}`). Returns the server's state, or null
         * when it could not be changed (route not deployed yet = 404, offline).
         */
        suspend fun setFavourite(
            id: String,
            on: Boolean,
        ): Boolean? {
            val (code, body) =
                api.userCall("v1/iptv/vod/${URLEncoder.encode(id, "UTF-8")}/favourite", body = """{"on":$on}""") ?: return null
            if (code !in 200..299) {
                com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
                    .log("iptv", "favourite $id on=$on failed: $code")
                return null
            }
            return runCatching { org.json.JSONObject(body).optBoolean("favourite", on) }.getOrDefault(on)
        }

        suspend fun detail(id: String): IptvVodDetail? {
            val b = base()
            val (code, body) = api.userCall("v1/iptv/vod/${URLEncoder.encode(id, "UTF-8")}") ?: return null
            if (code != 200) return null
            return runCatching { IptvVodParse.detail(body, b) }.getOrNull()
        }
    }
