package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Team colours from /v1/iptv/sport/fixtures with the contrast and clash guard. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class FixtureColoursTest {
    @Test
    fun parsesSideColoursLowerCasedAndDropsJunk() {
        val body =
            """{"items":[{"fid":"a","home":{"name":"Warriors","color":"#0B2265","altColor":"#A5ACAF"},"away":{"name":"Panthers","color":"bad"},
              "kickoff":1,"status":"pre"},{"fid":"b","title":"Monaco GP","kickoff":1,"status":"pre"}]}"""
        val items = SportFixtures.parse(body).items
        val a = items.first { it.fid == "a" }
        assertEquals("#0b2265", a.homeColor)
        assertEquals("#a5acaf", a.homeAltColor)
        assertNull(a.awayColor)
        assertNull(a.awayAltColor)
        val ev = items.first { it.fid == "b" }
        assertNull(FixtureColours.of(ev)) // no colours: the match record is asked, as before
    }

    @Test
    fun usableColoursPassThrough() {
        assertEquals("#0b2265" to "#e03a3e", FixtureColours.resolve("#0b2265", null, "#e03a3e", null))
    }

    @Test
    fun tooDarkOrTooLightFallsBackToAltThenNeutral() {
        // Near-black primary: the alt; white primary with a white alt: neutral
        assertEquals("#c8102e" to null, FixtureColours.resolve("#0d0f14", "#c8102e", "#ffffff", "#fafafa"))
        // Unusable with no alt: neutral
        assertEquals(null to "#00843d", FixtureColours.resolve("#000000", null, "#00843d", null))
    }

    @Test
    fun clashGivesAwayItsAltOrNeutral() {
        // Two reds: away takes its navy alt
        assertEquals("#c8102e" to "#002244", FixtureColours.resolve("#c8102e", null, "#c4122f", "#002244"))
        // Two reds, the alt is red too: away neutral
        assertEquals("#c8102e" to null, FixtureColours.resolve("#c8102e", null, "#c4122f", "#d01030"))
        // No alt: neutral
        assertEquals("#c8102e" to null, FixtureColours.resolve("#c8102e", null, "#c4122f", null))
    }

    @Test
    fun allUnusableIsNoColourButNoFetch() {
        val f =
            SportFixtures.parse(
                """{"items":[{"fid":"x","home":{"name":"A","color":"#000000"},"away":{"name":"B","color":"#ffffff"},"kickoff":1,"status":"pre"}]}""",
            ).items.single()
        assertEquals(null to null, FixtureColours.of(f))
    }
}
