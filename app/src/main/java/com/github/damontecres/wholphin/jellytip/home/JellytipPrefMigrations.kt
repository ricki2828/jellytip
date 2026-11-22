package com.github.damontecres.wholphin.jellytip.home

import android.content.Context
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.updateHomePagePreferences
import kotlinx.coroutines.flow.first

/*
 * One-shot preference moves for Jellytip (upstream's AppUpgradeHandler keys on upstream version strings, which Jellytip
 * builds don't follow). Each runs once per install, flagged in its own SharedPreferences.
 */
object JellytipPrefMigrations {
    private const val PREFS = "jellytip_migrations"

    /** Home rows: the old default of 25 per row becomes 50 (Ricki 22-Nov: rows much longer); a chosen value stays. */
    const val OLD_ROW_DEFAULT = 25
    const val NEW_ROW_DEFAULT = 50

    /** The row limit to store, or null to leave it (already moved once, or a value he chose). */
    fun rowsLimit(
        stored: Int,
        alreadyMoved: Boolean,
    ): Int? = if (!alreadyMoved && stored == OLD_ROW_DEFAULT) NEW_ROW_DEFAULT else null

    suspend fun run(
        context: Context,
        dataStore: DataStore<AppPreferences>,
    ) {
        val flags = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean("rows50", false)) return
        try {
            val stored = dataStore.data.first().homePagePreferences.maxItemsPerRow
            rowsLimit(stored, alreadyMoved = false)?.let { limit ->
                dataStore.updateData { it.updateHomePagePreferences { maxItemsPerRow = limit } }
                JellytipTrace.log("app", "home rows: $stored -> $limit per row (old default)")
            }
            flags.edit().putBoolean("rows50", true).apply()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: tried again next start
            JellytipTrace.log("app", "home rows migration failed: ${e.javaClass.simpleName}")
        }
    }
}
