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
}
