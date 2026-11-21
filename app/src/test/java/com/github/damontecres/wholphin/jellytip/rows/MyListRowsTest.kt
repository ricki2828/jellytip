package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

/** My list row on Movies / Shows (web 1ec4d7bf): taken from the Home answer, first on the page, right media type. */
class MyListRowsTest {
    private fun row(
        id: String,
        family: String,
        mediaType: String,
        ids: Int = 1,
    ) = JellytipServerRow(id, "jellytip", family, id, "standard", mediaType, List(ids) { UUID.randomUUID() }, null)

    private val home =
        JellytipRowsAnswer(
            listOf(row("for-you", "foryou", "movie"), row("my-shows", "mylist", "tv"), row("my-films", "mylist", "movie")),
            emptySet(),
        )
    private val movies = JellytipRowsAnswer(listOf(row("action", "genre", "movie")), emptySet())

    @Test
    fun moviesGetTheFilmsListFirst() {
        val out = requireNotNull(JellytipMyListRows.withMyList("movies", movies, home))
        assertEquals(listOf("mylist-movies", "action"), out.rows.map { it.id })
        assertEquals("My list", out.rows.first().title)
        assertEquals("movie", out.rows.first().mediaType)
    }

    @Test
    fun showsGetTheShowsList() {
        val out = requireNotNull(JellytipMyListRows.withMyList("shows", movies, home))
        assertEquals("tv", out.rows.first().mediaType)
    }

    @Test
    fun otherPagesOldServersAndEmptyListsAreUntouched() {
        assertSame(movies, JellytipMyListRows.withMyList("home", movies, home))
        assertNull(JellytipMyListRows.withMyList("movies", null, home))
        assertSame(movies, JellytipMyListRows.withMyList("movies", movies, null))
        val empty = JellytipRowsAnswer(listOf(row("my-films", "mylist", "movie", ids = 0)), emptySet())
        assertSame(movies, JellytipMyListRows.withMyList("movies", movies, empty))
    }

    @Test
    fun pageIdsRoundTrip() {
        assertEquals("jt-mylist:movie", JellytipMyListRows.pageId("movie"))
        assertEquals("jt-mylist:series", JellytipMyListRows.pageId("tv"))
        assertEquals("series", JellytipMyListRows.pageKind("jt-mylist:series"))
        assertNull(JellytipMyListRows.pageKind("jt-mylist:other"))
        assertNull(JellytipMyListRows.pageKind("imdb-top-250"))
    }

    @Test
    fun pageRowKeepsOrderAndMixesSources() {
        val a = UUID.randomUUID()
        val vod =
            com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem("p1:m:1", "movie", 1, "X", 2000, emptyList(), null, null, "", null, null, null)
        val row =
            JellytipMyListRows.pageRow(
                "jt-mylist:movie",
                "movie",
                listOf(
                    com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry.Iptv(vod),
                    com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry.Library(a),
                ),
            )
        assertEquals(listOf("p1:m:1", a.toString()), row.order)
        assertEquals(listOf(a), row.itemIds)
        assertEquals("mylist", row.family)
        assertEquals("movie", row.mediaType)
    }
}
