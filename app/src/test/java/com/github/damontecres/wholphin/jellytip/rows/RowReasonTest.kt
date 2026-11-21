package com.github.damontecres.wholphin.jellytip.rows

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** For you's "reason" line (stage 7ea8477): a string, or the key absent. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RowReasonTest {
    private fun row(extra: String) =
        JellytipRowsParser.row(
            JSONObject(
                """{"id":"for-you","family":"for-you","title":"For you","itemIds":["01234567-89ab-cdef-0123-456789abcdef"]$extra}""",
            ),
        )

    @Test
    fun reasonParsedWhenSent() {
        assertEquals("Because you like Martin Scorsese and 2010s drama", row(""","reason":"Because you like Martin Scorsese and 2010s drama"""")?.reason)
    }

    @Test
    fun absentNullOrBlankIsNoReason() {
        assertNull(row("")?.reason)
        assertNull(row(""","reason":null""")?.reason)
        assertNull(row(""","reason":"  """")?.reason)
    }
}
