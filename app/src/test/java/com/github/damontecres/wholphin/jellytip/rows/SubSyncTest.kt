package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.subtitles.SubSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Subtitle auto-match client (web lib/subSync.ts parseStart / parsePoll). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SubSyncTest {
    @Test
    fun start() {
        assertEquals(SubSync.Start.Job("0123456789ab"), SubSync.start(202, """{"jobId":"0123456789ab"}"""))
        val cached = SubSync.start(200, """{"cached":{"offsetSec":-1.5,"speed":1,"confidence":0.9,"label":"x","newStreamIndex":4,"fileName":"a.srt"}}""")
        assertEquals(4, (cached as SubSync.Start.Done).result.newStreamIndex)
        assertEquals(SubSync.Start.Error("Matching is busy, try again in a minute"), SubSync.start(429, null))
        assertEquals(SubSync.Start.Error("Auto-match isn't available right now"), SubSync.start(404, "{}"))
        assertEquals(SubSync.Start.Error("bad track"), SubSync.start(400, """{"error":"bad track"}"""))
        assertTrue(SubSync.start(200, """{"jobId":"nope"}""") is SubSync.Start.Error)
    }

    @Test
    fun poll() {
        assertEquals(SubSync.Poll.Waiting, SubSync.poll(200, """{"state":"running","etaSec":20}"""))
        assertTrue(SubSync.poll(200, """{"state":"done","result":{"offsetSec":2}}""") is SubSync.Poll.Done)
        assertEquals(SubSync.Poll.Failed("no speech"), SubSync.poll(200, """{"state":"failed","error":"no speech"}"""))
        assertEquals(SubSync.Poll.Failed("The match was lost, try again"), SubSync.poll(404, null))
        assertEquals(SubSync.Poll.Failed(SubSync.CHECK_FAILED), SubSync.poll(500, null))
        assertEquals("+1.2 s", SubSync.offsetLabel(1.23))
        assertEquals("−0.5 s", SubSync.offsetLabel(-0.5))
    }
}
