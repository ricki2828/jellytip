package com.github.damontecres.wholphin.jellytip.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipAdmin
import com.github.damontecres.wholphin.jellytip.JellytipApi
import org.jellyfin.sdk.model.api.ItemSortBy
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/**
 * Jellyfin has no file-size sort, so the library views sort client-side using the companion's
 * `/v1/library/sizes` map. The sort enum cannot be extended, so the otherwise unused
 * [ItemSortBy.START_DATE] (live TV only, never offered in movie/show libraries) is the marker.
 */
val FILE_SIZE_SORT: ItemSortBy = ItemSortBy.START_DATE

object JellytipSizes {
    private const val TTL_MS = 3_600_000L
    private const val TIMEOUT_MS = 15_000L

    @Volatile
    private var sizes: Map<String, Long> = emptyMap()

    @Volatile
    private var fetchedAt = 0L

    private var badgeOwner by mutableStateOf<Any?>(null)

    /** True while a library grid is sorted by file size; cards show their size badge. */
    val badgesActive: Boolean get() = badgeOwner != null

    /** [owner] (a view model) turns the badges on or off; a stale owner can never switch off a newer one's badges. */
    fun setBadges(
        owner: Any,
        on: Boolean,
    ) {
        if (on) badgeOwner = owner else if (badgeOwner === owner) badgeOwner = null
    }

    private fun key(id: UUID) = id.toString().replace("-", "").lowercase()

    /** Cached (1 h) id -> bytes map; empty if the companion is unreachable. */
    suspend fun get(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (sizes.isNotEmpty() && now - fetchedAt < TTL_MS) return sizes
        val body = JellytipApi.shared?.getJson("/v1/library/sizes", TIMEOUT_MS)
        if (body == null) {
            Timber.w("Jellytip sizes unavailable")
            return sizes
        }
        val root = JSONObject(body)
        val map = HashMap<String, Long>(root.length())
        root.keys().forEach { map[it] = root.optLong(it) }
        sizes = map
        fetchedAt = now
        return map
    }

    fun sizeOf(id: UUID): Long? = sizes[key(id)]

    /** Largest first when [descending]; titles with no known size always sort last, ties keep the incoming (name) order. */
    fun order(
        ids: List<UUID>,
        sizes: Map<String, Long>,
        descending: Boolean,
    ): List<UUID> {
        val (known, unknown) = ids.partition { (sizes[key(it)] ?: 0L) > 0L }
        val sorted =
            if (descending) known.sortedByDescending { sizes[key(it)] } else known.sortedBy { sizes[key(it)] }
        return sorted + unknown
    }

    fun format(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 10) {
            String.format(Locale.US, "%.0f GB", gb)
        } else if (gb >= 1) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        }
    }
}

/** Small "41 GB" pill shown on library grid cards while sorted by file size. */
@Composable
fun JellytipSizeBadge(
    item: BaseItem?,
    modifier: Modifier = Modifier,
) {
    if (!JellytipSizes.badgesActive || !JellytipAdmin.isAdmin || item == null) return
    val bytes = JellytipSizes.sizeOf(item.id) ?: return
    Text(
        text = JellytipSizes.format(bytes),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
