package com.github.damontecres.wholphin.jellytip.cards

import org.junit.Assert.assertEquals
import org.junit.Test

class JellytipTechTest {
    private fun tags(vararg raw: String) = raw.flatMap(JellytipTech::normalize).toSet()

    @Test
    fun tileShowsBestVideoAndAudioOnly() {
        assertEquals(listOf("4K DV", "ATMOS"), JellytipTech.tileBadges(tags("uhd", "dovi", "hdr", "atmos", "lossless", "imax")))
        assertEquals(listOf("4K HDR"), JellytipTech.tileBadges(tags("4k-hdr")))
        assertEquals(listOf("HDR", "DTS:X"), JellytipTech.tileBadges(tags("hdr", "dtsx")))
        assertEquals(emptyList<String>(), JellytipTech.tileBadges(tags("lossless")))
    }

    @Test
    fun displayNamesAreAccepted() {
        assertEquals(tags("uhd", "dovi", "atmos"), tags("4K", "Dolby Vision", "Dolby Atmos"))
        assertEquals(emptySet<String>(), tags("stereo", ""))
    }

    @Test
    fun detailsListsEverything() {
        assertEquals(listOf("4K", "Dolby Vision", "Dolby Atmos"), JellytipTech.fullNames(tags("uhd", "dovi", "hdr", "atmos", "lossless")))
        assertEquals(listOf("HDR", "Lossless audio"), JellytipTech.fullNames(tags("hdr", "lossless")))
    }
}
