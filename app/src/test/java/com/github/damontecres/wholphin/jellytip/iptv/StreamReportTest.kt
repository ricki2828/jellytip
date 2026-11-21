package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "This stream isn't working" next-copy rules + per-episode lastFail (web f12e4580). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class StreamReportTest {
    private val now = 1_800_000_000L

    private fun ep(
        n: Int,
        id: String,
        fail: IptvLastFail? = null,
    ) = IptvEpisode(id, n, "E$n", null, null, null, null, fail)

    @Test
    fun nextCopySkipsCurrentAndRecentFailuresAndPrefersSwitchedOn() {
        val copies =
            listOf(
                IptvCopy("p:m:1", "A", emptyList(), current = true),
                IptvCopy("p:m:2", "B", emptyList(), lastFail = IptvLastFail(now - 60, "stall", 1)),
                IptvCopy("p:m:3", "C", emptyList(), opted = false),
                IptvCopy("p:m:4", "D", emptyList()),
            )
        assertEquals("p:m:4", IptvStreamReport.nextCopy("p:m:1", copies, now)?.id)
        assertEquals("p:m:3", IptvStreamReport.nextCopy("p:m:1", copies.dropLast(1), now)?.id)
        assertNull(IptvStreamReport.nextCopy("p:m:1", copies.take(2), now))
    }

    @Test
    fun sameEpisodeOnAnotherCopy() {
        val here = listOf(IptvSeason(2, listOf(ep(1, "a:e:1"), ep(2, "a:e:2"))))
        assertEquals(2 to 2, IptvStreamReport.episodeAt(here, "a:e:2"))
        assertNull(IptvStreamReport.episodeAt(here, "x"))
        val other = listOf(IptvSeason(2, listOf(ep(2, "b:e:2", IptvLastFail(now - 10, "stall", 1)))), IptvSeason(2, listOf(ep(2, "b:e:2b"))))
        assertEquals("b:e:2b", IptvStreamReport.sameEpisode(other, 2 to 2, now)?.id)
        assertNull(IptvStreamReport.sameEpisode(other, 3 to 2, now))
    }

    @Test
    fun episodeLastFailParses() {
        val d =
            IptvVodParse.detail(
                JSONObject(
                    """{"id":"p:s:1","kind":"series","title":"S","seasons":[{"season":1,"episodes":[{"id":"p:e:1","episode":1,"title":"x","lastFail":{"at":$now,"reason":"refused","count7d":2}}]}]}""",
                ).toString(),
                "https://x.example/jellytip-api",
            )!!
        val lf = d.seasons[0].episodes[0].lastFail!!
        assertEquals("refused", lf.reason)
        assertEquals(2, lf.count7d)
    }
}
