package com.github.damontecres.wholphin.jellytip.specials

import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * One "special" from the companion's `GET /v1/home/specials` (contract: ~/ai/plans/jellytip-specials.md).
 * [position] = insert before the Nth normal carousel row (0 = right under Continue Watching / Next Up).
 */
sealed interface JellytipSpecial {
    val id: String
    val position: Int
    val title: String

    /** Every Jellyfin item this special draws, so one batch request can load them all. */
    val itemIds: List<UUID>

    data class TrailerBreak(
        override val id: String,
        override val position: Int,
        override val title: String,
        val itemId: UUID,
        val trailerItemId: UUID?,
        val reason: String,
        /** Companion hero backdrop index, used as the poster frame. */
        val heroIndex: Int?,
    ) : JellytipSpecial {
        override val itemIds get() = listOf(itemId)
    }

    data class PersonSpotlight(
        override val id: String,
        override val position: Int,
        override val title: String,
        val personId: UUID,
        val name: String,
        val role: String,
        val imageTag: String?,
        val filmCount: Int,
        val unwatchedCount: Int,
        val films: List<UUID>,
        /** Their films that are not in the library but are on IPTV (shown after the library ones). */
        val iptvItems: List<com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem> = emptyList(),
    ) : JellytipSpecial {
        override val itemIds get() = films
    }

    data class TonightsPick(
        override val id: String,
        override val position: Int,
        override val title: String,
        val itemId: UUID,
        val reason: String,
        val kicker: String,
        val heroIndex: Int?,
    ) : JellytipSpecial {
        override val itemIds get() = listOf(itemId)
    }

    data class Top10(
        override val id: String,
        override val position: Int,
        override val title: String,
        val variant: String,
        val ranked: List<UUID>,
    ) : JellytipSpecial {
        override val itemIds get() = ranked
    }

    data class DoubleFeature(
        override val id: String,
        override val position: Int,
        override val title: String,
        val pairs: List<Pair>,
    ) : JellytipSpecial {
        data class Pair(
            val a: UUID,
            val b: UUID,
            val reason: String,
            val totalRuntimeMin: Int?,
        )

        override val itemIds get() = pairs.flatMap { listOf(it.a, it.b) }
    }

    data class BecauseLoved(
        override val id: String,
        override val position: Int,
        override val title: String,
        val anchorItemId: UUID,
        /** Letterboxd stars, 0.5..5 in halves. */
        val rating: Double?,
        val quote: String?,
        val similar: List<UUID>,
    ) : JellytipSpecial {
        override val itemIds get() = listOf(anchorItemId) + similar
    }

    data class AroundHouse(
        override val id: String,
        override val position: Int,
        override val title: String,
        val entries: List<Entry>,
    ) : JellytipSpecial {
        data class Entry(
            val itemId: UUID,
            val who: String,
            /** finished | watching | requested */
            val what: String,
            /** As sent (ISO timestamp or a ready label). */
            val whenText: String,
        )

        override val itemIds get() = entries.map { it.itemId }
    }

    data class ClosetPicks(
        override val id: String,
        override val position: Int,
        override val title: String,
        val guest: String,
        val videoUrl: String,
        val posterUrl: String?,
        val durationSec: Int,
        val picks: List<Pick>,
        /** Admins: faded (not in library) picks can be requested ("Get it"). */
        val canRequest: Boolean,
    ) : JellytipSpecial {
        data class Pick(
            val title: String,
            val year: Int?,
            /** Null = not in the library (drawn faded; admins can request it). */
            val itemId: UUID?,
            val tmdbId: Int?,
            val posterUrl: String?,
            val atSec: Int?,
            /** TMDB media type for a request: "movie" (default when the server sends none) or "tv". */
            val mediaType: String = "movie",
        )

        override val itemIds get() = picks.mapNotNull { it.itemId }
    }
}

/** One parsed answer: the specials plus what they say about their items (textless Thumbs, hero backdrop indexes). */
data class JellytipSpecialsAnswer(
    val date: String?,
    val specials: List<JellytipSpecial>,
    val untitled: Set<UUID>,
    val heroIndexes: Map<UUID, Int>,
)

object JellytipSpecialsParser {
    /** The specials in the answer, in server order. Unknown layouts and malformed entries are dropped, never fatal. */
    fun parse(json: String): List<JellytipSpecial> = parseAnswer(json).specials

    fun parseAnswer(json: String): JellytipSpecialsAnswer {
        val root = JSONObject(json)
        com.github.damontecres.wholphin.jellytip.JellytipLogoTint.setStyles(root.optJSONObject("logoStyles"))
        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.setAll(root.optJSONObject("techs"))
        val arr = root.optJSONArray("specials") ?: return JellytipSpecialsAnswer(date(json), emptyList(), emptySet(), emptyMap())
        val untitled = mutableSetOf<UUID>()
        val heroIndexes = mutableMapOf<UUID, Int>()
        val specials =
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                try {
                    special(o)?.also { sp ->
                        untitled += o.uuids("untitled")
                        com.github.damontecres.wholphin.jellytip.JellytipLogoTint.setStyles(o.optJSONObject("logoStyles"))
                        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.setAll(o.optJSONObject("techs"))
                        val hero = if (o.isNull("heroIndex")) null else o.optInt("heroIndex", -1).takeIf { it >= 0 }
                        when (sp) {
                            is JellytipSpecial.TrailerBreak -> hero?.let { heroIndexes[sp.itemId] = it }
                            is JellytipSpecial.TonightsPick -> hero?.let { heroIndexes[sp.itemId] = it }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Special %d skipped", i)
                    null
                }
            }
        return JellytipSpecialsAnswer(root.optString("date").takeIf { it.isNotBlank() && it != "null" }, specials, untitled, heroIndexes)
    }

    /** The answer's `date` (YYYY-MM-DD), or null. */
    fun date(json: String): String? =
        try {
            JSONObject(json).optString("date").takeIf { it.isNotBlank() && it != "null" }
        } catch (e: Exception) {
            null
        }

    private fun special(o: JSONObject): JellytipSpecial? {
        val id = o.optString("id").ifBlank { return null }
        val position = o.optInt("position", 0).coerceAtLeast(0)
        val title = o.str("title").orEmpty()
        return when (o.optString("layout")) {
            "trailer_break" -> {
                JellytipSpecial.TrailerBreak(
                    id,
                    position,
                    title,
                    o.uuid("itemId") ?: return null,
                    o.uuid("trailerItemId"),
                    o.str("reason").orEmpty(),
                    o.index("heroIndex"),
                )
            }

            "person_spotlight" -> {
                val films = o.uuids("itemIds")
                val iptv =
                    com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
                        .items(o.optJSONArray("iptvItems"), com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse.imageBase)
                if (films.isEmpty() && iptv.isEmpty()) return null
                JellytipSpecial.PersonSpotlight(
                    id = id,
                    position = position,
                    title = title,
                    personId = o.uuid("personId") ?: return null,
                    name = o.str("name") ?: return null,
                    role = o.optString("role", "actor"),
                    imageTag = o.str("imageTag"),
                    filmCount = o.optInt("filmCount", films.size),
                    unwatchedCount = o.optInt("unwatchedCount", 0),
                    films = films,
                    iptvItems = iptv,
                )
            }

            "tonights_pick" -> {
                JellytipSpecial.TonightsPick(
                    id,
                    position,
                    title,
                    o.uuid("itemId") ?: return null,
                    o.str("reason").orEmpty(),
                    o.str("kicker").orEmpty(),
                    o.index("heroIndex"),
                )
            }

            "top10" -> {
                val ranked = o.uuids("itemIds")
                if (ranked.isEmpty()) return null
                JellytipSpecial.Top10(id, position, title.ifBlank { "Top 10 on Jellytip this month" }, o.optString("variant", "household"), ranked.take(10))
            }

            "double_feature" -> {
                val pairs =
                    o.objects("pairs").mapNotNull { p ->
                        JellytipSpecial.DoubleFeature.Pair(
                            a = p.uuid("a") ?: return@mapNotNull null,
                            b = p.uuid("b") ?: return@mapNotNull null,
                            reason = p.str("reason").orEmpty(),
                            totalRuntimeMin = if (p.isNull("totalRuntimeMin")) null else p.optInt("totalRuntimeMin").takeIf { it > 0 },
                        )
                    }
                if (pairs.isEmpty()) return null
                JellytipSpecial.DoubleFeature(id, position, title, pairs)
            }

            "because_loved" -> {
                JellytipSpecial.BecauseLoved(
                    id = id,
                    position = position,
                    title = title,
                    anchorItemId = o.uuid("anchorItemId") ?: return null,
                    rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                    quote = o.str("quote"),
                    similar = o.uuids("itemIds"),
                )
            }

            "around_house" -> {
                val entries =
                    o.objects("entries").mapNotNull { e ->
                        JellytipSpecial.AroundHouse.Entry(
                            itemId = e.uuid("itemId") ?: return@mapNotNull null,
                            who = e.str("who").orEmpty(),
                            what = e.optString("what"),
                            whenText = e.str("when").orEmpty(),
                        )
                    }
                if (entries.isEmpty()) return null
                JellytipSpecial.AroundHouse(id, position, title.ifBlank { "What friends are watching" }, entries)
            }

            "closet_picks" -> {
                val picks =
                    o.objects("picks").mapNotNull { p ->
                        JellytipSpecial.ClosetPicks.Pick(
                            title = p.str("title") ?: return@mapNotNull null,
                            year = if (p.isNull("year")) null else p.optInt("year").takeIf { it > 0 },
                            itemId = p.uuid("itemId"),
                            tmdbId = if (p.isNull("tmdbId")) null else p.optInt("tmdbId").takeIf { it > 0 },
                            posterUrl = p.str("posterUrl"),
                            mediaType = if (p.str("mediaType")?.lowercase() in setOf("tv", "series", "show")) "tv" else "movie",
                            atSec = if (!p.has("atSec") || p.isNull("atSec")) null else p.optDouble("atSec").takeIf { !it.isNaN() && it >= 0 }?.toInt(),
                        )
                    }
                if (picks.isEmpty()) return null
                JellytipSpecial.ClosetPicks(
                    id = id,
                    position = position,
                    title = title,
                    guest = o.str("guest").orEmpty(),
                    videoUrl = o.str("videoUrl") ?: return null,
                    posterUrl = o.str("posterUrl"),
                    durationSec = o.optInt("durationSec", 0),
                    picks = picks,
                    canRequest = o.optBoolean("canRequest", false),
                )
            }

            else -> {
                null // forward compatible: layouts this build does not know render as nothing
            }
        }
    }

    private fun JSONObject.index(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key, -1).takeIf { it >= 0 }

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() && it != "null" }

    private fun JSONObject.uuid(key: String): UUID? = str(key)?.let { runCatching { it.toUUID() }.getOrNull() }

    private fun JSONObject.uuids(key: String): List<UUID> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() }?.let { runCatching { it.toUUID() }.getOrNull() } }
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}
