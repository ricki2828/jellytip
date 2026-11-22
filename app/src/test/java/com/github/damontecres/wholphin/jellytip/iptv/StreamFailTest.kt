package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Failed-stream memory + Ways to watch (web f12e4580 / 846292d8), TV side. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class StreamFailTest {
    private val now = 1_800_000_000L

    @Test
    fun notes() {
        assertNull(IptvStreamFail.note(null, now))
        assertEquals("Failed just now", IptvStreamFail.note(IptvLastFail(now - 30, "stall", 1), now))
        assertEquals("Failed 5 min ago", IptvStreamFail.note(IptvLastFail(now - 300, "stall", 1), now))
        assertEquals("Failed 2 h ago", IptvStreamFail.note(IptvLastFail(now - 7200, "stall", 1), now))
        assertEquals("Failed 1 day ago", IptvStreamFail.note(IptvLastFail(now - 86400, "stall", 1), now))
        assertEquals("Failed 3× this week", IptvStreamFail.note(IptvLastFail(now - 7200, "stall", 3), now))
        assertNull(IptvStreamFail.note(IptvLastFail(now - 8 * 86400, "stall", 5), now))
    }

    @Test
    fun parseLastFail() {
        assertNull(IptvStreamFail.parse(JSONObject("""{"at":0}""")))
        val lf = IptvStreamFail.parse(JSONObject("""{"at":$now,"reason":"weird","count7d":0}"""))!!
        assertEquals("open_failed", lf.reason)
        assertEquals(1, lf.count7d)
    }

    @Test
    fun outcomeTargets() {
        assertEquals("p:m:1" to "vod", IptvStreamFail.outcomeTarget(IptvTarget("", "x", vodId = "p:m:1")))
        assertEquals("p:e:1" to "episode", IptvStreamFail.outcomeTarget(IptvTarget("", "x", seriesId = "p:s:1", episodeId = "p:e:1")))
        assertEquals("c1" to "live", IptvStreamFail.outcomeTarget(IptvTarget("c1", "x")))
        assertNull(IptvStreamFail.outcomeTarget(IptvTarget("c1", "x", start = 1L)))
        assertNull(IptvStreamFail.outcomeTarget(IptvTarget("c1", "x", recordingId = "r")))
        val body = JSONObject(IptvStreamFail.outcomeBody("c1", "live", false, null, -5))
        assertEquals("open_failed", body.getString("reason"))
        assertEquals(0, body.getInt("posSec"))
        assertFalse(JSONObject(IptvStreamFail.outcomeBody("c1", "live", true, "stall", null)).has("reason"))
    }

    @Test
    fun reasons() {
        assertEquals("open_failed", IptvStreamFail.reasonFor(2004, 404))
        assertNull(IptvStreamFail.reasonFor(2004, 403))
        assertNull(IptvStreamFail.reasonFor(2004, 502))
        assertEquals("decode", IptvStreamFail.reasonFor(4003, null))
        assertNull(IptvStreamFail.reasonFor(2001, null))
        assertNull(IptvStreamFail.reasonFor(4003, null, wasPlaying = true))
        assertNull(IptvStreamFail.reasonFor(1000, null, wasPlaying = true))
        // connections in use (409) and expired links are never the stream's fault; the companion code wins over HTTP
        assertNull(IptvStreamFail.reasonFor(2004, 409))
        assertNull(IptvStreamFail.reasonFor(2004, 403, code = "expired"))
        assertNull(IptvStreamFail.reasonFor(2004, 409, code = "max_connections"))
        assertEquals("refused", IptvStreamFail.reasonFor(2004, 502, code = "provider_refused"))
        assertNull(IptvStreamFail.reasonForCode("disabled"))
        assertEquals("refused", IptvStreamFail.reasonForCode("provider_refused"))
    }

    @Test
    fun copiesAndRememberedPick() {
        val a =
            JSONArray(
                """[{"id":"p1:m:1","providerName":"PROVIDER","quality":["fhd"],"lang":"en","langLabel":"English","current":true},
                {"id":"p1:m:2","providerTitle":"PROVIDER","quality":[],"res":"4k","opted":false,"lastFail":{"at":${now - 60},"reason":"stall","count7d":1}},
                {"id":"bad id"},{"id":"p1:m:1"}]""",
            )
        val copies = IptvVodParse.copies(a)
        assertEquals(listOf("p1:m:1", "p1:m:2"), copies.map { it.id })
        assertEquals(listOf("1080p"), copies[0].qualityWords)
        assertEquals(listOf("4K"), copies[1].qualityWords)
        assertEquals(false, copies[1].opted)
        // remembered copy that failed lately is not reopened; the current one never redirects
        assertNull(IptvStreamFail.rememberedTarget("p1:m:2", "p1:m:1", copies, now))
        assertNull(IptvStreamFail.rememberedTarget("p1:m:1", "p1:m:1", copies, now))
        assertEquals("p1:m:1", IptvStreamFail.rememberedTarget("p1:m:1", "p1:m:2", copies, now))
        assertNull(IptvStreamFail.rememberedTarget("p9:m:9", "p1:m:2", copies, now))
        assertTrue(IptvStreamFail.pickKey("movie", 603, "The Matrix", 1999) == "movie:t603")
        assertEquals("movie:nthe matrix:1999", IptvStreamFail.pickKey("movie", null, "The  Matrix!", 1999))
    }

    @Test
    fun outcomeCarriesStreamTraitsForFilmsAndEpisodesOnly() {
        // S4 (22-Nov): the server ranks copies by what players saw (Celebrity Traitors S2E2: an mkv with no Cues)
        val tr = IptvStreamFail.StreamTraits(seekable = false, remuxed = false, container = "mkv")
        val o = JSONObject(IptvStreamFail.outcomeBody("p:e:2111120", "episode", false, IptvStreamFail.UNSEEKABLE, null, tr))
        assertEquals("unseekable", o.getString("reason"))
        assertFalse(o.getBoolean("seekable"))
        assertFalse(o.getBoolean("remuxed"))
        assertEquals("mkv", o.getString("container"))
        val live = JSONObject(IptvStreamFail.outcomeBody("p:1", "live", true, null, 120, tr))
        assertFalse("live: no traits", live.has("seekable") || live.has("container"))
        val unknown = JSONObject(IptvStreamFail.outcomeBody("p:m:1", "vod", true, null, 90, IptvStreamFail.StreamTraits(remuxed = true)))
        assertTrue(unknown.getBoolean("remuxed"))
        assertFalse("null fields are left out", unknown.has("seekable") || unknown.has("container"))
    }

    @Test
    fun containerFromTheStreamLink() {
        assertEquals("mkv", IptvStreamFail.containerOf("v1/iptv/s/abc.def.mkv"))
        assertEquals("mp4", IptvStreamFail.containerOf("https://h/v1/iptv/s/tok.MP4?x=1"))
        assertNull(IptvStreamFail.containerOf("v1/iptv/s/token"))
        assertNull(IptvStreamFail.containerOf("v1/iptv/s/t.verylongext"))
        assertNull(IptvStreamFail.containerOf(null))
    }
}
