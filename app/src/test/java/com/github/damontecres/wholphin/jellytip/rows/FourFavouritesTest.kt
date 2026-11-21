package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecial
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** four_favourites (web b5a5c80a): parsed like the closet (clip + picks), with its own kicker and IPTV copies. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class FourFavouritesTest {
    @Test
    fun parsesWithKickerAndIptvCopy() {
        val json =
            """{"specials":[{"id":"ff1","layout":"four_favourites","position":2,"title":"","guest":"Guest","videoUrl":"https://image.tmdb.org/v.mp4","durationSec":90,
              "picks":[{"title":"In the Mood for Love","year":2000,"itemId":"0123456789abcdef0123456789abcdef","atSec":12},
                       {"title":"Yi Yi","year":2000,"tmdbId":25538,"iptv":{"id":"p1:m:9","kind":"movie","title":"Yi Yi","providerName":"PROVIDER"}},
                       {"year":1999}]}]}"""
        val s = JellytipSpecialsParser.parse(json).single() as JellytipSpecial.ClosetPicks
        assertEquals("Four favourites", s.kicker)
        assertEquals(2, s.picks.size)
        assertNull(s.picks[0].iptv)
        assertNotNull(s.picks[0].itemId)
        assertEquals("p1:m:9", s.picks[1].iptv?.id)
    }

    @Test
    fun closetKeepsItsKicker() {
        val json = """{"specials":[{"id":"c1","layout":"closet_picks","position":1,"title":"","guest":"G","videoUrl":"https://image.tmdb.org/v.mp4","picks":[{"title":"A"}]}]}"""
        assertEquals("Closet picks", (JellytipSpecialsParser.parse(json).single() as JellytipSpecial.ClosetPicks).kicker)
    }
}
