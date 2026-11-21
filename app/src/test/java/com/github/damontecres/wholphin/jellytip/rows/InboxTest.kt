package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.inbox.Inbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Availability inbox (web lib/inbox.ts applyObservation). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class InboxTest {
    private fun ep(
        id: String,
        s: Int,
        e: Int,
    ) = Inbox.ObservedEpisode(id, "Show", s, e, 1)

    @Test
    fun firstLookSeedsLaterLooksReport() {
        var st = Inbox.Store("u")
        st = Inbox.apply(st, Inbox.Observed(eps = mapOf("s1" to listOf(ep("a", 1, 1)))), 1000)
        assertTrue(st.events.isEmpty())
        st = Inbox.apply(st, Inbox.Observed(eps = mapOf("s1" to listOf(ep("a", 1, 1), ep("b", 1, 2)))), 2000)
        assertEquals(listOf("New episode: Show S1E2"), st.events.map(Inbox::text))
        // a re-import of a known episode (new id, same S/E) is not news; two at once are one event
        st = Inbox.apply(st, Inbox.Observed(eps = mapOf("s1" to listOf(ep("a2", 1, 1), ep("c", 1, 3), ep("d", 1, 4)))), 3000)
        assertEquals("Show: 2 new episodes", Inbox.text(st.events.first()))
    }

    @Test
    fun requestArrivals() {
        var st = Inbox.apply(Inbox.Store("u"), Inbox.Observed(titles = emptyMap()), 1000)
        st = Inbox.addRequest(st, Inbox.Request("movie:1", 1, "movie", "Heat", 1000))
        st = Inbox.apply(st, Inbox.Observed(titles = mapOf("movie:1" to Inbox.ObservedTitle(1, "Heat", true, iptvId = "p:m:1"))), 2000)
        assertEquals("Heat is now available via IPTV", Inbox.text(st.events.first()))
        // not looked up on IPTV this time: the known rank stays (no event)
        st = Inbox.apply(st, Inbox.Observed(titles = mapOf("movie:1" to Inbox.ObservedTitle(0, "Heat", true, partial = true))), 3000)
        assertEquals(1, st.titles["movie:1"])
        st = Inbox.apply(st, Inbox.Observed(titles = mapOf("movie:1" to Inbox.ObservedTitle(2, "Heat", true, libId = "x"))), 4000)
        assertEquals("Your request for Heat is now in your library", Inbox.text(st.events.first()))
        assertEquals(2, Inbox.unread(st, 4000))
        assertEquals(0, Inbox.unread(Inbox.markAllRead(st), 4000))
    }

    @Test
    fun storageRoundTripAndOtherPerson() {
        val st = Inbox.addRequest(Inbox.apply(Inbox.Store("u"), Inbox.Observed(eps = mapOf("s" to listOf(ep("a", 1, 1)))), 5), Inbox.Request("tv:2", 2, "tv", "X", 5))
        assertEquals(st, Inbox.fromJson(Inbox.toJson(st), "u"))
        assertEquals(Inbox.Store("v"), Inbox.fromJson(Inbox.toJson(st), "v"))
        assertEquals(Inbox.Store("u"), Inbox.fromJson("junk", "u"))
    }

    @Test
    fun navBadge() {
        assertEquals(null, com.github.damontecres.wholphin.jellytip.inbox.inboxBadge(0))
        assertEquals(null, com.github.damontecres.wholphin.jellytip.inbox.inboxBadge(-1))
        assertEquals("3", com.github.damontecres.wholphin.jellytip.inbox.inboxBadge(3))
        assertEquals("9", com.github.damontecres.wholphin.jellytip.inbox.inboxBadge(9))
        assertEquals("9+", com.github.damontecres.wholphin.jellytip.inbox.inboxBadge(10))
    }
}
