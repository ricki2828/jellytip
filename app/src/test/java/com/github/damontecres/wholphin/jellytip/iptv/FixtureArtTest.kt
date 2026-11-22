package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class FixtureArtTest {
    // Worst case: a white photo pixel under a light grey that still passes the colour guard
    private val white = Color.White
    private val lightGrey = Color(0xFFE2E2E2)
    private val gold = Color(0xFFFFD100)

    @Test
    fun worstCaseTeamColoursStillPassTheGuard() {
        assertTrue(FixtureColours.usable(lightGrey))
        assertTrue(FixtureColours.usable(gold))
    }

    @Test
    fun heroTitleReadsAsLargeTextFromTheEdgeOfTheTextColumn() {
        // The title (44 sp bold) can run out to ~18 % of the width; large text needs 3:1
        for (team in listOf(lightGrey, gold)) {
            for (x in listOf(0.18f, 0.3f, 0.5f, 0.7f, 0.82f)) {
                val c = FixtureArt.contrast(Broadcast.Text, FixtureArt.heroBehind(x, white, team, team))
                assertTrue("title at $x on $team: $c", c >= 3.0)
            }
        }
    }

    @Test
    fun heroSmallTextReadsInTheMiddle() {
        // League line / counter (15 / 12 sp, white on art) sit in the middle ~half; small text needs 4.5:1
        for (team in listOf(lightGrey, gold)) {
            for (x in listOf(0.27f, 0.4f, 0.5f, 0.6f, 0.73f)) {
                val c = FixtureArt.contrast(Broadcast.Text, FixtureArt.heroBehind(x, white, team, team))
                assertTrue("small text at $x on $team: $c", c >= 4.5)
            }
        }
    }

    @Test
    fun tileTextReadsRightAtTheHomeEdge() {
        for (team in listOf(lightGrey, gold)) {
            for (x in listOf(0f, 0.06f, 0.2f, 0.5f)) {
                val c = FixtureArt.contrast(Broadcast.Text, FixtureArt.tileBehind(x, white, team))
                assertTrue("tile text at $x on $team: $c", c >= 4.5)
            }
        }
    }

    @Test
    fun homeHeroMatchSlideTextReadsBottomLeft() {
        // The slide's text block (Home hero, bottom left) runs from ~4 % to ~50 % of the width; all of it is held to the
        // small-text 4.5:1, on a white photo and on the plain wash, with the lightest team colours the guard lets through
        for (team in listOf(lightGrey, gold)) {
            for (x in listOf(0f, 0.04f, 0.15f, 0.3f, 0.4f, 0.5f)) {
                for (art in listOf(white, null)) {
                    val c = FixtureArt.contrast(Broadcast.Text, FixtureArt.slideTextBehind(x, art, team, team))
                    assertTrue("slide text at $x on $team art=${art != null}: $c", c >= 4.5)
                }
            }
        }
    }

    @Test
    fun withoutTheTextScrimTheWashWouldNotRead() {
        // Why the scrim is there: white on the bare light-grey wash
        assertTrue(FixtureArt.contrast(Broadcast.Text, FixtureArt.washBehind(0.04f, lightGrey, lightGrey)) < 4.5)
        assertEquals(0f, FixtureArt.textScrim(0.7f), 0f)
        assertEquals(FixtureArt.TEXT_SCRIM_ALPHA, FixtureArt.textScrim(0.1f), 0f)
    }

    @Test
    fun theGreySecondaryWouldNotHaveReadOnAPhoto() {
        // Why the hero / tile switch to white on art
        assertTrue(FixtureArt.contrast(Broadcast.Secondary, FixtureArt.tileBehind(0.06f, white, lightGrey)) < 4.5)
    }

    @Test
    fun sizedAppendsOnlyWithoutAQuery() {
        assertEquals("https://h/v1/iptv/img/art/1f.sig?s=960", FixtureArt.sized("https://h/v1/iptv/img/art/1f.sig", 960))
        assertEquals("https://h/a?s=96", FixtureArt.sized("https://h/a?s=96", 960))
    }

    @Test
    fun parsesArtAndBadgeAsResolvedLinks() {
        val body =
            """{"items":[
              {"fid":"a","kickoff":1800000000,"status":"in","home":{"name":"New Zealand"},"away":{"name":"England"},
               "art":"v1/iptv/img/art/123f1.sig","badge":"v1/iptv/img/art/9b.sig"},
              {"fid":"b","kickoff":1800000000,"status":"pre","home":{"name":"Sinner"},"away":{"name":"Alcaraz"}},
              {"fid":"c","kickoff":1800000000,"status":"pre","home":{"name":"A"},"away":{"name":"B"},"art":"https://evil/x.jpg","badge":""}
            ]}"""
        // Same rule as JellytipIptvService.resolve: relative paths only
        val resolve = { rel: String -> if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(rel) || rel.startsWith("//")) null else "https://api.x/$rel" }
        val items = SportFixtures.parse(body, resolve).items
        assertEquals("https://api.x/v1/iptv/img/art/123f1.sig", items[0].art)
        assertEquals("https://api.x/v1/iptv/img/art/9b.sig", items[0].badge)
        assertNull(items[1].art)
        assertNull(items[1].badge)
        // Raw URLs are refused by the resolver; an empty badge is none
        assertNull(items[2].art)
        assertNull(items[2].badge)
    }
}
