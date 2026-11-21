package com.github.damontecres.wholphin.jellytip.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV collection hits are Collections-hub rows (web d0444d51 4d0ad94f): kept, opened by hubId, never the Top match. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SearchHubCollectionTest {
    @Test
    fun hubCollectionsKeptAndNeverTop() {
        val json =
            """{"q":"nordic noir","results":[
              {"kind":"collection","source":"library","title":"Nordic Noir","hubId":"world:tv-nordic","total":6},
              {"kind":"collection","source":"library","title":"Heist","tmdbCollectionId":5,"total":4,"inLibrary":2}
            ]}"""
        val a = SearchParse.answer(json)
        assertNull(a.top?.hubId)
        val coll = a.rankedGroups.first { it.type == "collections" }.collections
        assertEquals(listOf("world:tv-nordic", null), coll.map { it.hubId })
    }
}
