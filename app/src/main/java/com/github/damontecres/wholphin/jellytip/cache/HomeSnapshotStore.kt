package com.github.damontecres.wholphin.jellytip.cache

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One card image as the home page requests it: URL plus the pixel size of the card. */
@Serializable
data class ThumbSpec(
    val url: String,
    val width: Int,
    val height: Int,
    val fill: Boolean,
)

@Serializable
data class RowSnapshot(
    /** Identifies the row config; a row is only restored when this still matches the live settings. */
    val key: String,
    val items: List<BaseItem>,
    val showViewMore: Boolean,
    val thumbs: List<ThumbSpec> = emptyList(),
)

@Serializable
data class HomeSnapshot(
    val userId: String,
    val rows: List<RowSnapshot>,
    /** Hero backdrop URL of the first card of the first row. */
    val heroUrl: String? = null,
)

/**
 * Last successful home page, on disk, for stale-while-revalidate cold starts.
 * A single file holding one user's data: a different user id (or [clear]) discards it.
 */
@Singleton
class HomeSnapshotStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val file get() = File(context.filesDir, FILE_NAME)
        private val json = Json { ignoreUnknownKeys = true }

        @Synchronized
        fun read(): HomeSnapshot? {
            memo?.let { return it }
            val f = file
            if (!f.exists()) return null
            return try {
                json.decodeFromString(HomeSnapshot.serializer(), f.readText()).also { memo = it }
            } catch (ex: Exception) {
                Timber.w(ex, "Home snapshot unreadable, discarding")
                f.delete()
                null
            }
        }

        /** Rows for [settings], or null when there is no usable snapshot for this user. */
        fun restore(
            userId: UUID,
            settings: HomePageResolvedSettings,
        ): List<HomeRowLoadingState>? {
            val snap = read()?.takeIf { it.userId == userId.toString() } ?: return null
            val byKey = snap.rows.associateBy { it.key }
            var restored = 0
            val rows =
                settings.rows.map { row ->
                    val saved = byKey[rowKey(row.config)]
                    if (saved != null && saved.items.isNotEmpty()) {
                        restored++
                        HomeRowLoadingState.Success(
                            title = row.title,
                            items = saved.items,
                            viewOptions = row.config.viewOptions,
                            rowType = row.config,
                            showViewMore = saved.showViewMore,
                        )
                    } else {
                        HomeRowLoadingState.Pending(row.title)
                    }
                }
            return rows.takeIf { restored > 0 }
        }

        @Synchronized
        fun save(
            userId: UUID,
            snapshot: HomeSnapshot,
        ) {
            if (snapshot.userId != userId.toString()) return
            try {
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(HomeSnapshot.serializer(), snapshot))
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                } else {
                    memo = snapshot
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Could not write home snapshot")
            }
        }

        companion object {
            private const val FILE_NAME = "jellytip_home_snapshot.json"

            @Volatile
            private var memo: HomeSnapshot? = null

            /** Forget the stored home page, e.g. on logout or user switch. */
            @Synchronized
            fun clear(context: Context) {
                memo = null
                File(context.filesDir, FILE_NAME).delete()
            }
        }
    }
