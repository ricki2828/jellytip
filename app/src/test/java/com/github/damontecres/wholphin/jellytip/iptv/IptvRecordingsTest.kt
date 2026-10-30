package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvRecordingsTest {
    private fun rec(
        id: String,
        state: String,
        start: Long,
        watched: Boolean = false,
        playable: Boolean = state == "done",
    ) = IptvRecording(id, "c", "Chan", id, null, start, start + 3600, state, 0, null, watched, playable, null, null)

    @Test
    fun homeOrderRecordingNewScheduledWatched() {
        val list =
            listOf(
                rec("watched-old", "done", 100, watched = true),
                rec("sched-late", "scheduled", 9000),
                rec("new-old", "done", 200),
                rec("cancelled", "cancelled", 50),
                rec("now", "recording", 500),
                rec("failed", "failed", 60),
                rec("sched-soon", "scheduled", 8000),
                rec("new-recent", "done", 300),
                rec("watched-recent", "done", 400, watched = true),
            )
        assertEquals(
            listOf("now", "new-recent", "new-old", "sched-soon", "sched-late", "watched-recent", "watched-old"),
            homeRecordings(list).map { it.id },
        )
    }
}
