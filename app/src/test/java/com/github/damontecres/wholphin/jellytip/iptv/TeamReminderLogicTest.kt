package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TeamReminderLogicTest {
    @Test
    fun parsesAndSkipsUnusable() {
        val r = TeamReminderLogic.parse("""{"items":[{"id":"7","teamId":"espn:nrl:3","teamName":"Warriors","league":"NRL","sport":null},{"id":"8"},{"teamId":"x"}]}""")
        assertEquals(1, r.size)
        assertEquals("Warriors", r[0].teamName)
        assertEquals(null, r[0].sport)
    }

    @Test
    fun sidesAndTexts() {
        val m =
            IptvMatch(
                "m",
                "NRL",
                "nrl",
                MatchTeam("New Zealand Warriors", "Warriors", null, null, teamId = "espn:nrl:3"),
                MatchTeam("Penrith Panthers", null, null, null),
                0,
                null,
                "pre",
                false,
                null,
            )
        val sides = TeamReminderLogic.offerSides(TeamReminderLogic.matchSides(m, "rugby league"))
        assertEquals(1, sides.size)
        assertEquals("Warriors", TeamReminderLogic.shortTeam(sides[0]))
        assertEquals("Remind me for every Warriors game", TeamReminderLogic.remindEveryText("Warriors"))
        assertTrue(TeamReminderLogic.matchSides(null, null).isEmpty())
    }
}
