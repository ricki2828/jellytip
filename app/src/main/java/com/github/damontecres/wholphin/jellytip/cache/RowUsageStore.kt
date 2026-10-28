package com.github.damontecres.wholphin.jellytip.cache

import android.content.Context
import androidx.core.content.edit
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stable identity of a home row across sessions. */
fun rowKey(config: HomeRowConfig): String = config.toString()

/**
 * Local counts of which home rows items are opened from. Drives which rows get prefetched first;
 * never leaves the device.
 */
@Singleton
class RowUsageStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("jellytip_row_usage", Context.MODE_PRIVATE)

        fun record(key: String) {
            prefs.edit { putInt(key, prefs.getInt(key, 0) + 1) }
        }

        fun count(key: String): Int = prefs.getInt(key, 0)

        /**
         * Indices of the [n] rows to prefetch: most-used first, padded with the first rows in
         * display order. [keys] is the row key per index (null for rows that cannot be keyed).
         */
        fun priorityRows(
            keys: List<String?>,
            n: Int = PREFETCH_ROWS,
        ): List<Int> {
            val used =
                keys
                    .withIndex()
                    .filter { it.value != null && count(it.value!!) > 0 }
                    .sortedWith(compareByDescending<IndexedValue<String?>> { count(it.value!!) }.thenBy { it.index })
                    .map { it.index }
            return (used + keys.indices).distinct().take(n)
        }

        companion object {
            const val PREFETCH_ROWS = 7
        }
    }
