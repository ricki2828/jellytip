package com.github.damontecres.wholphin.jellytip.askchat

import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.ui.nav.Destination
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chat cards carry `sources` (plan S8: [{type:"library", id}, {type:"iptv", id, quality, tech, copies}], library first). They parse into
 * the card (libraryId = the library entry's id), map onto IptvVodItem.libraryId / .sources, and OK on a TV film that is also in the
 * library opens the library page (JellytipTitleOpen). Fixture: resources/jellytip/askchat-sources-v1.json (synthetic ids).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskChatSourcesTest {
    private val resp by lazy {
        val j = JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askchat-sources-v1.json")!!.bufferedReader().readText())
        AskChat.parseResponse(j.getJSONObject("mixed").toString())!!
    }
    private fun lib(id: String) = resp.cards.filterIsInstance<ChatTitle.Library>().first { it.id == id }

    private fun tv(id: String) = resp.cards.filterIsInstance<ChatTitle.Iptv>().first { it.iptvId == id }

    @Test
    fun allCardsOfTheFixtureParse() {
        assertEquals(9, resp.cards.size)
        assertEquals(3, resp.cards.filterIsInstance<ChatTitle.Library>().size)
        assertEquals(6, resp.cards.filterIsInstance<ChatTitle.Iptv>().size)
    }

    @Test
    fun aLibraryCardWithATvTwinListsBothSourcesLibraryFirst() {
        val c = lib("0a1b2c3d4e5f60718293a4b5c6d7e8f9")
        assertEquals(listOf("library", "iptv"), c.sources.map { it.type })
        assertEquals(IptvSource("iptv", "p1:m:7", listOf("1080p"), listOf("ATMOS"), 3), c.sources[1])
        assertEquals(listOf(IptvSource("library", "f0e1d2c3b4a5968778695a4b3c2d1e0f")), lib("f0e1d2c3b4a5968778695a4b3c2d1e0f").sources)
        assertTrue("a missing sources field is none", lib("11111111222233334444555566667777").sources.isEmpty())
    }

    @Test
    fun aTvCardsLibraryEntryBecomesItsLibraryId() {
        val c = tv("p1:m:7")
        assertEquals("0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9", c.libraryId)
        assertEquals(listOf("library", "iptv"), c.sources.map { it.type })
        assertEquals(3, c.sources[1].copies)
        val only = tv("p1:m:8")
        assertNull("no library entry = no libraryId", only.libraryId)
        assertEquals(listOf(IptvSource("iptv", "p1:m:8", listOf("4k"), emptyList(), 1)), only.sources)
        assertTrue(tv("p1:s:9").sources.isEmpty() && tv("p1:s:9").libraryId == null)
    }

    @Test
    fun unknownOrMalformedSourcesAreDropped() {
        val c = tv("p1:m:10")
        assertEquals(
            "only the iptv entry with an id survives (type dvd, a missing id and junk are dropped; copies 0 = none; a lone quality string is a one-item list)",
            listOf(IptvSource("iptv", "p1:m:10", listOf("1080p"), emptyList(), null)),
            c.sources,
        )
        assertNull(c.libraryId)
    }

    @Test
    fun theVodItemCarriesLibraryIdAndSources() {
        val v = tv("p1:m:7").toVodItem()
        assertEquals("0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9", v.libraryId)
        assertEquals(tv("p1:m:7").sources, v.sources)
        assertEquals("movie", v.kind)
        assertEquals("p1:m:7", v.id)
        assertEquals("series", tv("p1:s:9").toVodItem().kind)
        assertNull(tv("p1:m:8").toVodItem().libraryId)
    }

    @Test
    fun okOnATvFilmWithALibraryCopyOpensTheLibraryPage() {
        val d = JellytipTitleOpen.detailsFor(tv("p1:m:7").toVodItem())
        assertTrue(d is Destination.MediaItem)
        assertEquals(java.util.UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"), (d as Destination.MediaItem).itemId)
        assertEquals(Destination.JellytipIptvDetails("p1:m:8", "TV Only"), JellytipTitleOpen.detailsFor(tv("p1:m:8").toVodItem()))
        assertEquals(Destination.JellytipIptvDetails("p1:s:9", "No Sources"), JellytipTitleOpen.detailsFor(tv("p1:s:9").toVodItem()))
    }

    @Test
    fun aDashlessLowercaseLibraryIdTheRealWireFormatAlsoOpensTheLibraryPage() {
        val c = tv("p1:m:12")
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", c.libraryId)
        assertEquals(listOf("library", "iptv"), c.sources.map { it.type })
        val d = JellytipTitleOpen.detailsFor(c.toVodItem())
        assertTrue(d is Destination.MediaItem)
        assertEquals(java.util.UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"), (d as Destination.MediaItem).itemId)
        assertEquals(org.jellyfin.sdk.model.api.BaseItemKind.MOVIE, d.type)
    }

    @Test
    fun aSeriesWithALibraryCopyOpensTheLibrarySeriesPage() {
        // One page per title (22-Nov, Celebrity Traitors): series too; the library page's split merge shows the IPTV episodes
        val d = JellytipTitleOpen.detailsFor(tv("p1:s:11").toVodItem())
        assertEquals(Destination.MediaItem(itemId = java.util.UUID.fromString("aaaabbbb-cccc-dddd-eeee-ffff00001111"), type = org.jellyfin.sdk.model.api.BaseItemKind.SERIES), d)
        assertEquals("aaaabbbbccccddddeeeeffff00001111", tv("p1:s:11").libraryId)
    }

    @Test
    fun theLiveFixtureHasNoSourcesAndStillParses() {
        val live = JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askchat-live-20261122.json")!!.bufferedReader().readText())
        val r = AskChat.parseResponse(live.getJSONObject("headline").getJSONObject("resp").toString())!!
        assertEquals(24, r.cards.size)
        assertTrue(r.cards.filterIsInstance<ChatTitle.Library>().all { it.sources.isEmpty() })
    }
}
