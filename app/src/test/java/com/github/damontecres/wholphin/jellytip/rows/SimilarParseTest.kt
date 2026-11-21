package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "More like this" (`GET /v1/similar`, web 847f8810): query paths and the mixed library + IPTV row. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SimilarParseTest {
    private val base = "https://x.example/jellytip-api"

    @Test
    fun pathNeedsASeed() {
        assertNull(JellytipSimilar.path("movie", null))
        assertNull(JellytipSimilar.path("movie", 0))
        assertEquals("v1/similar?kind=movie&tmdb=603", JellytipSimilar.path("movie", 603))
        assertEquals("v1/similar?kind=series&iptvId=p1%3As%3A9", JellytipSimilar.path("series", null, iptvId = "p1:s:9"))
        assertEquals(
            "v1/similar?kind=series&tmdb=1&jfId=0123456789abcdef0123456789abcdef",
            JellytipSimilar.path("series", 1, jfId = "01234567-89ab-cdef-0123-456789abcdef"),
        )
    }

    @Test
    fun orderKeysDedupeAndCapAt20() {
        val keys = (1..30).map { "id$it" } + listOf("ID1", "id-2")
        val out = JellytipSimilar.orderKeys(listOf("a-b", "AB") + keys)
        assertEquals("a-b", out.first())
        assertEquals(20, out.size)
    }

    @Test
    fun mixedRowKeepsServerOrder() {
        val body =
            """{"items":[
              {"source":"library","id":"0123456789abcdef0123456789abcdef","kind":"movie","title":"Heat","year":1995},
              {"source":"iptv","id":"p1:m:2","kind":"movie","tmdbId":603,"title":"The Matrix","year":1999,"quality":["1080p"],"poster":"https://image.tmdb.org/t/p/w342/a.jpg","providerName":"PROVIDER"},
              {"source":"library","id":"0123456789abcdef0123456789abcdef","kind":"movie","title":"Heat","year":1995}
            ],"source":"tmdb"}"""
        val row = requireNotNull(JellytipSimilar.parse(body, base, "v1/similar?kind=movie&tmdb=1"))
        assertEquals("More like this", row.title)
        assertEquals(1, row.itemIds.size)
        assertEquals(listOf("p1:m:2"), row.iptvItems.map { it.id })
        assertEquals(2, row.order.size)
        assertEquals("movie", row.mediaType)
    }

    @Test
    fun emptyAnswerIsNoRow() {
        assertNull(JellytipSimilar.parse("""{"items":[],"source":"fallback"}""", base, "v1/similar?kind=series&tmdb=1"))
    }
}
