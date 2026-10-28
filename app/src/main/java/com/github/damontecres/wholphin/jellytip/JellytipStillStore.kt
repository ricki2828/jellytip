package com.github.damontecres.wholphin.jellytip

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.jellytipStillDataStore by preferencesDataStore("jellytip_stills")

/**
 * Remembers, per item and only on this device, which still the user picked with "Change still".
 *
 * The stored value is a counter: 0 means automatic (slideshow); otherwise the effective choice is
 * `counter % (stills + 1)`, where 0 is automatic and k is the k-th still. Cycling is just counter + 1.
 */
@Singleton
class JellytipStillStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private fun key(id: UUID) = intPreferencesKey(id.toString())

        fun choice(id: UUID): Flow<Int> = context.jellytipStillDataStore.data.map { it[key(id)] ?: 0 }

        suspend fun cycle(id: UUID) {
            context.jellytipStillDataStore.edit { it[key(id)] = (it[key(id)] ?: 0) + 1 }
        }
    }
