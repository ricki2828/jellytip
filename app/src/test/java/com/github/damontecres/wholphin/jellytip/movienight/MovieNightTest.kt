package com.github.damontecres.wholphin.jellytip.movienight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Vectors from web/test/movieNight.test.ts (origin/jellytip-web 0e7e7787). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MovieNightTest {
    private fun id(n: Int) = n.toString(16).padStart(32, '0')

    private fun c(
        y: Int,
        m: Int,
        n: Int,
    ) = MnCounts(y, m, n)

    private fun sess(
        open: Boolean = true,
        members: List<MnMember> = emptyList(),
        expiresAt: Long = 10_000,
    ) = MnSession("K7M2QX", open, true, listOf(id(1), id(2)), expiresAt, emptyMap(), emptyMap(), members)

    private fun m(voted: Int) = MnMember("n", voted, false, false)

    @Test
    fun scoring() {
        assertEquals(3, MovieNight.scoreOf(c(2, 1, 1)))
        assertEquals(0, MovieNight.scoreOf(c(0, 0, 0)))
    }

    @Test
    fun tallyOrder() {
        val items = listOf(id(1), id(2), id(3), id(4), id(5))
        val rows =
            MovieNight.tally(
                items,
                mapOf(id(1) to c(1, 0, 0), id(2) to c(0, 2, 0), id(3) to c(2, 0, 1), id(4) to c(1, 0, 0)),
            )
        assertEquals(listOf(id(1), id(4), id(2), id(3), id(5)), rows.map { it.id })
        assertEquals(0, rows[4].score)
    }

    @Test
    fun yesNoSplitLosesToTwoMaybes() {
        assertEquals(id(2), MovieNight.tally(listOf(id(1), id(2)), mapOf(id(1) to c(1, 0, 1), id(2) to c(0, 2, 0)))[0].id)
    }

    @Test
    fun tallyIgnoresUnknownIds() {
        val rows = MovieNight.tally(listOf(id(2), id(1)), mapOf(id(9) to c(5, 0, 0)))
        assertEquals(listOf(id(2), id(1)), rows.map { it.id })
    }

    @Test
    fun winner() {
        assertNull(MovieNight.winner(MovieNight.tally(listOf(id(1)), mapOf(id(1) to c(0, 0, 2)))))
        assertNull(MovieNight.winner(MovieNight.tally(listOf(id(1)), emptyMap())))
        assertEquals(id(2), MovieNight.winner(MovieNight.tally(listOf(id(1), id(2)), mapOf(id(2) to c(0, 1, 0))))?.id)
        assertNull(MovieNight.winner(emptyList()))
    }

    @Test
    fun allVotedAndFinished() {
        assertFalse(MovieNight.allVoted(sess(members = listOf(m(2)))))
        assertFalse(MovieNight.allVoted(sess(members = listOf(m(2), m(1)))))
        assertTrue(MovieNight.allVoted(sess(members = listOf(m(2), m(2)))))
        assertFalse(MovieNight.finished(sess(members = listOf(m(2), m(1)))))
        assertTrue(MovieNight.finished(sess(open = false, members = listOf(m(0)))))
        assertTrue(MovieNight.finished(sess(members = listOf(m(2), m(2)))))
    }

    @Test
    fun minutesLeft() {
        assertEquals(2L, MovieNight.minutesLeft(sess(expiresAt = 1000), 1000 - 90))
        assertEquals(0L, MovieNight.minutesLeft(sess(expiresAt = 1000), 5000))
    }

    @Test
    fun codesAndIds() {
        assertEquals("K7M 2QX", MovieNight.showCode("K7M2QX"))
        assertEquals("0123456789abcdef0123456789abcdef", MovieNight.itemId("01234567-89AB-CDEF-0123-456789ABCDEF"))
        assertNull(MovieNight.itemId("iptv:m:5"))
    }

    @Test
    fun parseSession() {
        val raw =
            """
            {"code":"K7M2QX","status":"open","host":true,"items":["${id(1)}","${id(2)}"],"expiresAt":5,"now":1,
             "mine":{"${id(1)}":"yes","${id(2)}":"love","${id(9)}":"no"},
             "counts":{"${id(1)}":{"yes":1,"maybe":0,"no":0},"${id(2)}":{"yes":-1}},
             "members":[{"name":"Ann","voted":1,"me":true,"host":true},null,{"name":5,"voted":"x"}]}
            """.trimIndent()
        val s = MovieNight.parse(raw)!!
        assertEquals(mapOf(id(1) to MnVote.YES), s.mine)
        assertEquals(setOf(id(1)), s.counts.keys)
        assertEquals(2, s.members.size)
        assertEquals("", s.members[1].name)
        assertEquals(0, s.members[1].voted)
        assertTrue(s.open)
        assertNull(MovieNight.parse(null))
        assertNull(MovieNight.parse("""{"error":"x"}"""))
        assertNull(MovieNight.parse(raw.replace("\"open\"", "\"weird\"")))
        assertNull(MovieNight.parse("""{"code":"K7M2QX","status":"open","items":[1]}"""))
    }

    @Test
    fun errorText() {
        assertEquals("Only the host can close it", MovieNight.errorText(403, """{"error":"only the host can close it"}"""))
        assertEquals("Can't reach the server.", MovieNight.errorText(0, null))
    }

    @Test
    fun remoteVoteCycle() {
        assertEquals(MnVote.YES, MovieNight.nextVote(null))
        assertEquals(MnVote.MAYBE, MovieNight.nextVote(MnVote.YES))
        assertEquals(MnVote.NO, MovieNight.nextVote(MnVote.MAYBE))
        assertEquals(MnVote.YES, MovieNight.nextVote(MnVote.NO))
    }

    @Test
    fun optimisticVoteMovesOneCount() {
        val s = sess().copy(counts = mapOf(id(1) to c(2, 0, 1)))
        val a = MovieNight.withVote(s, id(1), MnVote.YES)
        assertEquals(MnVote.YES, a.mine[id(1)])
        assertEquals(c(3, 0, 1), a.counts[id(1)])
        val b = MovieNight.withVote(a, id(1), MnVote.NO)
        assertEquals(c(2, 0, 2), b.counts[id(1)])
        assertEquals(b, MovieNight.withVote(b, id(1), MnVote.NO))
        // a film with no counts yet
        assertEquals(c(0, 1, 0), MovieNight.withVote(s, id(2), MnVote.MAYBE).counts[id(2)])
    }
}
