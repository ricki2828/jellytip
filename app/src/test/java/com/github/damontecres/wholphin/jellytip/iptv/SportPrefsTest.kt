package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SportPrefsTest {
    private val sp =
        JSONObject(
            """{"teams":["Warriors",{"name":"Lakers","teamId":"espn:nba:13","leagueKey":"nba"}],"teamRefs":[{"teamId":"espn:nrl:3","leagueKey":"nrl","name":"Warriors"}],
            "leagues":["NRL"],"sports":["cricket"],"muted":{"sports":["golf"],"leagues":["NBL"],"tags":["womens"]}}""",
        )

    @Test
    fun parsesNamesRefsAndLists() {
        val s = SportPrefs.parse(sp)
        assertEquals(listOf("Warriors", "Lakers"), s.teams)
        assertEquals(listOf("espn:nba:13", "espn:nrl:3"), s.teamRefs.map { it.teamId })
        assertEquals(listOf("NRL"), s.leagues)
        assertEquals(listOf("cricket"), s.sports)
        assertEquals(listOf("golf"), s.mutedSports)
        assertTrue(s.sportsSupported)
        assertFalse(SportPrefs.parse(JSONObject("{}")).sportsSupported)
    }

    @Test
    fun followAndUnfollowByCatalogueId() {
        val s = SportPrefs.parse(sp)
        val hit = TeamHit("espn:nfl:9", "Packers", "NFL", "nfl", null, "nfl")
        assertFalse(SportPrefs.isFollowing(hit, s))
        val f = SportPrefs.follow(hit, s)
        assertTrue(SportPrefs.isFollowing(hit, f))
        assertEquals("Packers", f.teams.last())
        val u = SportPrefs.unfollow(hit, f)
        assertFalse(u.teams.contains("Packers"))
        // A name shared by another ref stays
        val dup = TeamHit("espn:nz:1", "Warriors", "NZ", "nz", null, null)
        val two = SportPrefs.follow(dup, s)
        assertTrue(SportPrefs.unfollow(dup, two).teams.contains("Warriors"))
        // The chip removes the name and every ref with it
        assertTrue(SportPrefs.removeTeam("warriors", two).teamRefs.none { it.name == "Warriors" })
    }

    @Test
    fun sportsCycleAndTags() {
        val s = SportPrefs.parse(sp)
        assertEquals(SportPrefs.SportState.FOLLOW, SportPrefs.sportState("Cricket", s))
        assertEquals(SportPrefs.SportState.MUTE, SportPrefs.sportState("golf", s))
        val st = SportPrefs.next(SportPrefs.sportState("cricket", s))
        val m = SportPrefs.applySport("cricket", st, s)
        assertTrue(m.mutedSports.contains("cricket"))
        assertFalse(m.sports.contains("cricket"))
        assertTrue(SportPrefs.hasTag(s, "WOMENS"))
        assertFalse(SportPrefs.hasTag(SportPrefs.withTag(s, "womens", false), "womens"))
        assertEquals(listOf("golf"), SportPrefs.unmute(s, "leagues", "nbl").mutedSports)
        assertTrue(SportPrefs.unmute(s, "leagues", "nbl").mutedLeagues.isEmpty())
    }

    @Test
    fun delaysAndBodies() {
        assertEquals(45, SportPrefs.nextDelay(30))
        assertEquals(0, SportPrefs.nextDelay(300))
        assertEquals(10, SportPrefs.nextDelay(5))
        val body = SportPrefs.teamsBody(SportPrefs.parse(sp)).getJSONObject("sport")
        assertEquals(2, body.getJSONArray("teams").length())
        assertEquals("nba", body.getJSONArray("teamRefs").getJSONObject(0).getString("leagueKey"))
        assertEquals(1, SportPrefs.sportsBody(SportPrefs.parse(sp)).getJSONObject("sport").getJSONObject("muted").getJSONArray("tags").length())
    }

    @Test
    fun parsesTeamHits() {
        val hits = SportPrefs.parseTeams("""{"items":[{"id":"1","name":"A","league":"L","leagueKey":"l"},{"id":"1","name":"A dup","leagueKey":"l"},{"name":"no id"}]}""")
        assertEquals(1, hits.size)
        assertEquals("A · L", hits[0].line)
    }
}
