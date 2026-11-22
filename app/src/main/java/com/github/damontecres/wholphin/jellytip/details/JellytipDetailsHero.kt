package com.github.damontecres.wholphin.jellytip.details

import androidx.compose.ui.text.AnnotatedString
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.cards.JellytipTech
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail
import com.github.damontecres.wholphin.jellytip.scoreLine
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import java.util.Locale
import java.util.UUID

/**
 * Everything the bottom-left text block of a details page draws, with no source (BaseItem, IPTV item) in it.
 * Build one with [heroOf] (library item) or [buildDetailsHero] (plain inputs), draw it with `JellytipDetailsBlockContent`.
 */
data class JellytipDetailsHero(
    /** Typeset when there is no [logoUrl] (or the logo fails / is tall and narrow). */
    val title: String?,
    val logoUrl: String? = null,
    /** Item ids the companion's per-item logo style is looked up by (own id, then series / logo owner). */
    val logoStyleIds: List<UUID> = emptyList(),
    /** Bold quiet line under the title ("Double feature with ..."). */
    val subtitle: String? = null,
    /** Quiet line under the subtitle ("3 seasons  ·  Next: S2 E4"). Library film leaves it null. */
    val statusLine: String? = null,
    /** "2019  ·  15  ·  2h 4m  ·  4K  ·  Drama, Thriller"; empty when nothing is known. */
    val infoText: String = "",
    /** Quiet file-detail badges drawn after the info text. */
    val badges: List<String> = emptyList(),
    /** "Directed by X  ·  Country" */
    val dimLine: String? = null,
    /** Admin-only "Requested by ..." line. */
    val audienceLine: String? = null,
    val overview: String? = null,
    /** "IMDb 8.1  ·  RT 95% ..." with its marks, ready to draw. */
    val scores: AnnotatedString? = null,
    /** Small badge after the file badges ("Library", "IPTV"). Library film leaves it null. */
    val sourceBadge: String? = null,
)

private const val SEP = "  ·  "

/**
 * Pure (unit tested): what `JellytipDetailsBlock` computed inline before the hero model existed - same strings, same
 * order, same conditions.
 *
 * @param premium full premium format names ("4K", "Dolby Vision", ...) from the companion; a "4K" file badge is dropped when it is in here
 * @param directedBy "Directed by %s" in the current language
 */
fun buildDetailsHero(
    title: String?,
    logoUrl: String? = null,
    logoStyleIds: List<UUID> = emptyList(),
    subtitle: String? = null,
    statusLine: String? = null,
    productionYear: Int? = null,
    officialRating: String? = null,
    runTimeTicks: Long? = null,
    premium: List<String> = emptyList(),
    genres: List<String>? = null,
    fileBadges: List<String> = emptyList(),
    directors: List<String> = emptyList(),
    country: String? = null,
    directedBy: (String) -> String = { "Directed by $it" },
    audienceLine: String? = null,
    overview: String? = null,
    scores: AnnotatedString? = null,
    sourceBadge: String? = null,
): JellytipDetailsHero {
    val info =
        listOfNotNull(
            productionYear?.toString(),
            officialRating,
            runtimeText(runTimeTicks),
            premium.joinToString(SEP).takeIf { it.isNotEmpty() },
            genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
        ).joinToString(SEP)
    val director = directors.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    val dim = listOfNotNull(director?.let(directedBy), country).joinToString(SEP)
    return JellytipDetailsHero(
        title = title,
        logoUrl = logoUrl,
        logoStyleIds = logoStyleIds,
        subtitle = subtitle,
        statusLine = statusLine,
        infoText = info,
        badges = fileBadges.filterNot { it == "4K" && "4K" in premium },
        dimLine = dim.takeIf { it.isNotEmpty() },
        audienceLine = audienceLine,
        overview = overview?.takeIf { it.isNotBlank() },
        scores = scores,
        sourceBadge = sourceBadge,
    )
}

/** The library item adapter over [buildDetailsHero]. */
fun heroOf(
    item: BaseItem,
    extras: JellytipExtras?,
    premium: List<String>,
    directedBy: (String) -> String,
    logoUrl: String?,
    subtitle: String? = null,
    statusLine: String? = null,
    audienceLine: String? = null,
    sourceBadge: String? = null,
): JellytipDetailsHero {
    val dto = item.data
    return buildDetailsHero(
        title = item.title,
        logoUrl = logoUrl,
        logoStyleIds = listOfNotNull(item.id, dto.seriesId, dto.parentLogoItemId),
        subtitle = subtitle,
        statusLine = statusLine,
        productionYear = dto.productionYear,
        officialRating = dto.officialRating,
        runTimeTicks = dto.runTimeTicks,
        premium = premium,
        genres = dto.genres,
        fileBadges = fileBadges(item),
        directors =
            dto.people
                ?.filter { it.type == PersonKind.DIRECTOR && !it.name.isNullOrBlank() }
                ?.map { it.name!! }
                .orEmpty(),
        country = dto.productionLocations?.firstOrNull(),
        directedBy = directedBy,
        audienceLine = audienceLine,
        overview = dto.overview,
        scores = scoreLine(extras),
        sourceBadge = sourceBadge,
    )
}

/** "TMDB 7.4": the one score an IPTV title has. */
fun iptvScoreText(rating: Double?): String? = rating?.takeIf { it > 0 }?.let { String.format(Locale.US, "TMDB %.1f", it) }

/**
 * The IPTV title adapter over [buildDetailsHero]: the same hero as a library page, drawn from what IPTV knows.
 *
 *  - info line: year, runtime (films only; a series says its seasons on the status line), premium names (else the quality
 *    words), up to 3 genres - the library's order and separators
 *  - "Language unknown" is one more badge, "IPTV" the source badge at the end of the line (the provider is not in the hero)
 *  - dim line: "Directed by X" (no cast text); score line: the single TMDB rating as plain text
 *  - [statusLine]: a series' "3 seasons  ·  Next: S2 E4" (built by the caller from [resolveIptvSeriesPlay])
 */
fun iptvHeroOf(
    d: IptvVodDetail,
    fallbackTitle: String,
    statusLine: String? = null,
    directedBy: (String) -> String = { "Directed by $it" },
): JellytipDetailsHero {
    val item = d.item
    val premium =
        JellytipTech
            .fullNames(item.tech.flatMap(JellytipTech::normalize).toSet())
            .ifEmpty { listOf(item.quality.joinToString(" ") { it.uppercase(Locale.ROOT) }).filter { it.isNotEmpty() } }
    return buildDetailsHero(
        title = item.title.ifBlank { fallbackTitle },
        logoUrl = d.logo,
        logoStyleIds = emptyList(),
        statusLine = statusLine,
        productionYear = item.year,
        runTimeTicks = if (item.isSeries) null else d.runtimeMin?.let { it * 600_000_000L },
        premium = premium,
        genres = d.genres.ifEmpty { listOfNotNull(d.genre) },
        fileBadges = if (item.langUnknown) listOf("Language unknown") else emptyList(),
        directors = listOfNotNull(d.director),
        directedBy = directedBy,
        overview = item.overview,
        scores = iptvScoreText(item.rating)?.let { AnnotatedString(it) },
        sourceBadge = "IPTV",
    )
}

internal fun runtimeText(ticks: Long?): String? {
    if (ticks == null || ticks <= 0) return null
    val minutes = (ticks / 600_000_000L).toInt()
    if (minutes <= 0) return null
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** e.g. ["1080p", "HEVC", "EAC3 5.1"] from the first media source; empty when the server sent none. */
internal fun fileBadges(item: BaseItem): List<String> {
    val streams =
        item.data.mediaSources
            ?.firstOrNull()
            ?.mediaStreams
            .orEmpty()
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audio =
        streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
    return buildList {
        video?.height?.let { h ->
            add(
                when {
                    h >= 2000 -> "4K"
                    h >= 1000 -> "1080p"
                    h >= 700 -> "720p"
                    else -> "SD"
                },
            )
        }
        video?.codec?.let { c ->
            add(
                when (c.lowercase()) {
                    "hevc", "h265" -> "HEVC"
                    "h264", "avc" -> "H.264"
                    else -> c.uppercase()
                },
            )
        }
        audio?.let { a ->
            val ch =
                when (a.channels) {
                    1 -> "1.0"
                    2 -> "2.0"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> null
                }
            val codec = a.codec?.uppercase()
            val text = listOfNotNull(codec, ch).joinToString(" ")
            if (text.isNotEmpty()) add(text)
        }
    }
}
