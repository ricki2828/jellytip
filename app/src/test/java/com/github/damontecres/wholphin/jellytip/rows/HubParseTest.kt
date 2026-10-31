package com.github.damontecres.wholphin.jellytip.rows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Collections hub against Ricki's real `/v1/collections` answer (trimmed: a few items per section, ids replaced;
 * from palawan:~/jellytip-iptv/fake/fixtures/collections_ricki.json, 31-Oct). Robolectric for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class HubParseTest {
    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResource("collections_ricki.json")) { "fixture missing" }.readText()

    @Test
    fun everySectionParsesIncludingFranchises() {
        val sections = HubParse.sections(fixture())
        assertEquals(listOf("collections", "top-lists", "franchises", "countries", "decades", "awards", "directors", "actors"), sections.map { it.key })
        val franchises = sections.first { it.key == "franchises" }
        assertEquals("Franchises", franchises.title)
        assertEquals(6, franchises.items.size)
        val bond = franchises.items.first()
        assertEquals("franchise:645", bond.id)
        assertEquals("James Bond", bond.title)
        assertEquals(26, bond.total)
        assertEquals(23, bond.iptvCount)
        assertNotNull(bond.poster)
        assertNotNull(bond.artItemId)
    }

    @Test
    fun aBadItemDropsOnlyItself() {
        val json =
            """{"sections":[{"key":"franchises","title":"Franchises","items":[
               {"title":"No id"},{"id":"franchise:1","title":"Ok","art":"not an object","total":"x"}]}]}"""
        val sections = HubParse.sections(json)
        assertEquals(1, sections.size)
        assertEquals(listOf("franchise:1"), sections[0].items.map { it.id })
        assertTrue(sections[0].items[0].poster == null)
    }

    @Test
    fun aCardWithNoLibraryTitlesOrArtStays() {
        val json = """{"sections":[{"key":"franchises","title":"Franchises","items":[{"id":"franchise:9","title":"All IPTV","total":4,"libCount":0,"iptvCount":4}]}]}"""
        val c = HubParse.sections(json).single().items.single()
        assertEquals(0, c.libCount)
        assertEquals(null, c.artItemId)
    }

    /** James Bond is IPTV-preferred: 0 library ids, 26 IPTV titles; the collection page must still have every one, in order. */
    @Test
    fun bondIsAnAllIptvCollection() {
        val body = requireNotNull(javaClass.classLoader?.getResource("collection_bond_ricki.json")).readText()
        val row = requireNotNull(JellytipRowsParser.row(org.json.JSONObject(body)))
        assertEquals("franchise:645", row.id)
        assertTrue(row.itemIds.isEmpty())
        assertEquals(26, row.iptvItems.size)
        val tiles =
            com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse.ordered(row.order, emptyList(), row.iptvItems, { "lib" }, { it.title })
        assertEquals(26, tiles.size)
        assertEquals("Dr. No", tiles.first())
    }
}
