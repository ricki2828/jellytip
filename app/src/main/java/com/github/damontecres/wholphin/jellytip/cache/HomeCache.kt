package com.github.damontecres.wholphin.jellytip.cache

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for the Jellytip home cache: stale-while-revalidate snapshot, image prefetch and
 * row-usage counts. Everything here is best-effort and must never fail the home page.
 */
@Singleton
class HomeCache
    @Inject
    constructor(
        private val store: HomeSnapshotStore,
        private val prefetcher: HomeImagePrefetcher,
        private val usage: RowUsageStore,
        private val serverRepository: ServerRepository,
        @param:StandardOkHttpClient private val httpClient: OkHttpClient,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Called as early as possible (during the intro): connect to the server and warm images from the snapshot. */
        fun warmUp() {
            scope.launch {
                try {
                    store.read()?.let { prefetcher.warmFromSnapshot(it) }
                } catch (ex: Exception) {
                    Timber.w(ex, "Snapshot warm-up failed")
                }
            }
            scope.launch {
                // Opens the TLS + HTTP/2 connection the home requests will reuse (the pool is shared by all clients)
                try {
                    val server = serverRepository.currentServerFlow.filterNotNull().first()
                    httpClient
                        .newCall(Request.Builder().url(server.url.trimEnd('/') + "/System/Ping").build())
                        .execute()
                        .close()
                } catch (ex: Exception) {
                    Timber.d(ex, "Connection pre-warm failed")
                }
            }
        }

        fun restore(
            userId: UUID,
            settings: HomePageResolvedSettings,
        ): List<HomeRowLoadingState>? =
            try {
                store.restore(userId, settings)
            } catch (ex: Exception) {
                Timber.w(ex, "Home snapshot restore failed")
                null
            }

        /** A full home load finished: persist it and prefetch images for the rows that matter most. */
        fun onLoaded(
            userId: UUID,
            settings: HomePageResolvedSettings,
            rows: List<HomeRowLoadingState>,
        ) {
            scope.launch {
                try {
                    val keys = settings.rows.map { rowKey(it.config) }
                    prefetcher.onHomeLoaded(keys, rows)
                    val snapshotRows =
                        settings.rows.mapIndexedNotNull { index, row ->
                            val loaded = rows.getOrNull(index) as? HomeRowLoadingState.Success ?: return@mapIndexedNotNull null
                            if (loaded.items.isEmpty()) return@mapIndexedNotNull null
                            RowSnapshot(
                                key = keys[index],
                                items = loaded.items.filterNotNull().take(SNAPSHOT_ITEMS).map(::slim),
                                showViewMore = loaded.showViewMore,
                                thumbs = prefetcher.thumbsFor(loaded),
                            )
                        }
                    if (snapshotRows.isEmpty()) return@launch
                    val hero =
                        rows
                            .firstNotNullOfOrNull { (it as? HomeRowLoadingState.Success)?.items?.firstOrNull() }
                            ?.let(prefetcher::heroUrl)
                    store.save(userId, HomeSnapshot(userId.toString(), snapshotRows, hero))
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not cache home page")
                }
            }
        }

        fun onFocus(
            rows: List<HomeRowLoadingState>,
            row: Int,
            column: Int,
        ) = prefetcher.onFocus(rows, row, column)

        /** An item was opened from [row]; counts toward which rows get prefetched first. */
        fun recordOpen(row: HomeRowLoadingState?) {
            val config = (row as? HomeRowLoadingState.Success)?.rowType ?: return
            usage.record(rowKey(config))
        }

        private fun slim(item: com.github.damontecres.wholphin.data.model.BaseItem) =
            item.copy(data = item.data.copy(people = null, chapters = null, trickplay = null))

        companion object {
            private const val SNAPSHOT_ITEMS = 12
        }
    }
