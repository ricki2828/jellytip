package com.github.damontecres.wholphin.jellytip.kids

import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONObject

enum class KidsKind { MOVIE, SERIES, EPISODE, COLLECTION }

/** One tile or bubble, from plain values: the server's Home items, library grids and My list all become these. */
data class KidsTile(
    /** Jellyfin item id (library), IPTV catalogue id (iptv) or collection id. */
    val id: String,
    /** "library" | "iptv" */
    val source: String,
    val kind: KidsKind,
    val name: String,
    val seriesName: String? = null,
    val seriesId: String? = null,
    /** 0 (none) .. 1 */
    val progress: Float = 0f,
    val playCount: Int = 0,
    val imageUrl: String? = null,
)

class KidsHero(
    /** true = Keep watching (resume); false = today's pick from their favourites. */
    val resume: Boolean,
    val tile: KidsTile,
    /** Wide art for the hero card (may be null: the card has its own fallback). */
    val imageUrl: String?,
)

class KidsRow(
    val id: String,
    val title: String,
    val bubbles: Boolean,
    val items: List<KidsTile>,
)

class KidsHomeData(
    val hero: KidsHero?,
    val rows: List<KidsRow>,
)

/** What the Home page has to show. Unavailable (403, error, old server) hides the rows: NEVER the adult Home. */
sealed interface KidsHomeResult {
    data class Ok(
        val data: KidsHomeData,
    ) : KidsHomeResult

    data object Unavailable : KidsHomeResult
}

/** Turns the server's `{itemId, kind}` image refs into URLs. [wide] = the hero card (big backdrop) rather than a tile. */
fun interface KidsImageResolver {
    fun url(
        itemId: String,
        kind: String,
        wide: Boolean,
    ): String?
}

/**
 * `GET /v1/kids/home` (spec section 2). Pure, so it is unit tested. Anything unknown is dropped, never guessed: an item with an
 * unknown type, no id or no name is skipped; a row left with no items is omitted. The server already omits thin rows (< 6 items,
 * bubbles < 4) and re-checks every title against the policy; the client trusts that and draws what it is given (cap: 3 rows).
 */
object KidsHomeParse {
    const val MAX_ROWS = 3

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun kindOf(type: String?): KidsKind? =
        when (type) {
            "Movie" -> KidsKind.MOVIE
            "Series" -> KidsKind.SERIES
            "Episode" -> KidsKind.EPISODE
            else -> null
        }

    private fun image(
        o: JSONObject?,
        base: String,
        wide: Boolean,
        resolver: KidsImageResolver,
    ): String? {
        o ?: return null
        o.str("url")?.let { return IptvVodParse.image(base, it) }
        val itemId = o.str("itemId") ?: return null
        return resolver.url(itemId, o.str("kind") ?: "Primary", wide)
    }

    fun item(
        o: JSONObject?,
        base: String,
        resolver: KidsImageResolver,
        wide: Boolean = false,
    ): KidsTile? {
        o ?: return null
        val id = o.str("id") ?: return null
        val kind = kindOf(o.str("type")) ?: return null
        val name = o.str("name") ?: return null
        val source = if (o.str("source") == "iptv") "iptv" else "library"
        // IPTV is only a film (the server sends it only as the hero): an IPTV series or episode has no kids page, so no tile
        if (source == "iptv" && kind != KidsKind.MOVIE) return null
        val progress = o.optDouble("progress", 0.0).takeIf { it > 0.0 && it < 1.0 }?.toFloat() ?: 0f
        return KidsTile(
            id = id,
            source = source,
            kind = kind,
            name = name,
            seriesName = o.str("seriesName"),
            seriesId = o.str("seriesId"),
            progress = progress,
            playCount = o.optInt("playCount", 0).coerceAtLeast(0),
            imageUrl = image(o.optJSONObject("image"), base, wide, resolver),
        )
    }

    fun bubble(
        o: JSONObject?,
        base: String,
        resolver: KidsImageResolver,
    ): KidsTile? {
        o ?: return null
        val kind =
            when (o.str("kind")) {
                "series" -> KidsKind.SERIES
                "collection" -> KidsKind.COLLECTION
                else -> return null
            }
        val target = o.optJSONObject("target")
        // The target says what opens; it must agree with the bubble's kind
        val targetType = target?.str("type")
        val targetId = target?.str("id") ?: return null
        if ((kind == KidsKind.SERIES && targetType != "series") || (kind == KidsKind.COLLECTION && targetType != "collection")) return null
        val name = o.str("name") ?: return null
        return KidsTile(
            id = targetId,
            source = "library",
            kind = kind,
            name = name,
            imageUrl = image(o.optJSONObject("image"), base, false, resolver),
        )
    }

    /** Null = not a kids answer (malformed, or `kidsMode` not true): the caller hides everything. */
    fun parse(
        json: String,
        base: String,
        resolver: KidsImageResolver,
    ): KidsHomeData? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("kidsMode", false)) return null
        val hero =
            root.optJSONObject("hero")?.let { h ->
                val tile = item(h.optJSONObject("item"), base, resolver, wide = false) ?: return@let null
                val wideTile = item(h.optJSONObject("item"), base, resolver, wide = true)
                KidsHero(resume = h.str("kind") == "resume", tile = tile, imageUrl = wideTile?.imageUrl ?: tile.imageUrl)
            }
        val rowsJson = root.optJSONArray("rows")
        val rows =
            (0 until (rowsJson?.length() ?: 0)).mapNotNull { i ->
                val r = rowsJson?.optJSONObject(i) ?: return@mapNotNull null
                val id = r.str("id") ?: return@mapNotNull null
                val title = r.str("title") ?: return@mapNotNull null
                val bubbles = r.str("style") == "bubbles"
                val arr = r.optJSONArray("items")
                val items =
                    (0 until (arr?.length() ?: 0)).mapNotNull { j ->
                        val o = arr?.optJSONObject(j)
                        if (bubbles) bubble(o, base, resolver) else item(o, base, resolver)
                    }
                if (items.isEmpty()) null else KidsRow(id, title, bubbles, items)
            }.take(MAX_ROWS)
        return KidsHomeData(hero, rows)
    }
}

/**
 * What a tap on a tile opens. Pure, so it is unit tested; null = nothing kids-safe to open (the tile does nothing). There is no
 * kids page for an IPTV series, and the adult one is never shown to a kid: an IPTV film plays, an IPTV series is a no-op (and the
 * parser drops IPTV series so no dead tile is drawn).
 */
object KidsOpen {
    fun destination(tile: KidsTile): Destination? =
        when (tile.source) {
            "iptv" -> if (tile.kind == KidsKind.MOVIE) Destination.JellytipIptvPlay(title = tile.name, vodId = tile.id) else null

            else ->
                when (tile.kind) {
                    KidsKind.MOVIE -> uuid(tile.id)?.let { Destination.MediaItem(it, BaseItemKind.MOVIE) }
                    KidsKind.SERIES -> uuid(tile.id)?.let { Destination.MediaItem(it, BaseItemKind.SERIES) }
                    KidsKind.EPISODE ->
                        // An episode tile opens its series (the kids details page has the episodes); no series id = the episode itself
                        (tile.seriesId?.let { uuid(it) })?.let { Destination.MediaItem(it, BaseItemKind.SERIES) }
                            ?: uuid(tile.id)?.let { Destination.MediaItem(it, BaseItemKind.EPISODE) }

                    // A friend's collection opens the kids grid of its films and shows (the shell routes a BOX_SET there)
                    KidsKind.COLLECTION -> uuid(tile.id)?.let { Destination.MediaItem(it, BaseItemKind.BOX_SET) }
                }
        }

    private fun uuid(s: String) = runCatching { s.toUUID() }.getOrNull()
}
