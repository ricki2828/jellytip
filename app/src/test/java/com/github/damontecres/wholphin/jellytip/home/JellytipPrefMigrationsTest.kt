package com.github.damontecres.wholphin.jellytip.home

import com.github.damontecres.wholphin.preferences.AppPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class JellytipPrefMigrationsTest {
    @Test
    fun theOldDefaultMovesOnceAndAChoiceStays() {
        // The old default of 25 per row becomes 50, once
        assertEquals(50, JellytipPrefMigrations.rowsLimit(25, alreadyMoved = false))
        assertNull(JellytipPrefMigrations.rowsLimit(25, alreadyMoved = true))
        // A value he chose is his
        assertNull(JellytipPrefMigrations.rowsLimit(30, alreadyMoved = false))
        assertNull(JellytipPrefMigrations.rowsLimit(10, alreadyMoved = false))
        assertNull(JellytipPrefMigrations.rowsLimit(50, alreadyMoved = false))
    }

    @Test
    fun newInstallsStartAtFiftyAndTheSliderGoesFurther() {
        assertEquals(50L, AppPreference.HomePageItems.defaultValue)
        assertEquals(100L, AppPreference.HomePageItems.max)
    }
}
