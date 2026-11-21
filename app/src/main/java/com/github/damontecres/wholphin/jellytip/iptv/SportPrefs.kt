package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject

/*
 * Sport settings logic (web lib/teams.ts + lib/sportMute.ts; gap row 6 "Sport settings", rows 22/23): favourite teams
 * by catalogue id (sport.teams names + sport.teamRefs refs, written together), followed / hidden sports, muted tags,
 * and the PATCH bodies. Pure; tested in SportPrefsTest. The server ranks with them; the TV only edits them.
 */

data class TeamRef(
    val teamId: String,
    val leagueKey: String?,
    val name: String,
)

/** One /v1/sport/teams?q= hit. */
data class TeamHit(
    val id: String,
    val name: String,
    val league: String?,
    val leagueKey: String?,
    val logo: String?,
    val sport: String?,
) {
    /** "UCLA Bruins · College Football". */
    val line get() = listOfNotNull(name, league).joinToString(" · ")
}

/** The sport block of the person's prefs, as the settings page edits it. */
data class SportSettings(
    val teams: List<String> = emptyList(),
    val teamRefs: List<TeamRef> = emptyList(),
    val leagues: List<String> = emptyList(),
    val sports: List<String> = emptyList(),
    val mutedSports: List<String> = emptyList(),
    val mutedLeagues: List<String> = emptyList(),
    val mutedTags: List<String> = emptyList(),
    /** The server knows `sport.sports` (the followed-sports picker is shown only then). */
    val sportsSupported: Boolean = false,
)

object SportPrefs {
    val SPORTS =
        listOf(
            "rugby league" to "Rugby league",
            "rugby union" to "Rugby union",
            "cricket" to "Cricket",
            "nfl" to "NFL",
            "college football" to "College football",
            "nba" to "NBA",
            "football" to "Football / soccer",
            "afl" to "AFL",
            "motorsport" to "Motorsport",
            "tennis" to "Tennis",
            "golf" to "Golf",
            "horse racing" to "Horse racing",
            "combat" to "Combat sports",
        )
    const val TAG_WOMENS = "womens"
    const val TAG_REPLAYS = "replays"

    /** Allowed default delays (s), stepped by the setting row. */
    val DELAYS = listOf(0, 10, 20, 30, 45, 60, 90, 120, 180, 300)

    private fun same(
        a: String,
        b: String,
    ) = a.equals(b, ignoreCase = true)

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }

    private fun JSONObject.id(k: String): String? = opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

    /** `sport` from GET /v1/me/prefs: teams mix plain names and {name, teamId, leagueKey}; teamRefs add refs. */
    fun parse(sp: JSONObject?): SportSettings {
        sp ?: return SportSettings()
        val names = ArrayList<String>()
        val refs = ArrayList<TeamRef>()
        val t = sp.optJSONArray("teams")
        if (t != null) {
            for (i in 0 until t.length()) {
                when (val v = t.opt(i)) {
                    is String -> if (v.isNotBlank() && names.none { same(it, v) }) names += v
                    is JSONObject -> {
                        val n = v.optString("name").takeIf { it.isNotBlank() } ?: continue
                        if (names.none { same(it, n) }) names += n
                        v.id("teamId")?.let { refs += TeamRef(it, v.optString("leagueKey").takeIf { s -> s.isNotBlank() }, n) }
                    }
                }
            }
        }
        sp.optJSONArray("teamRefs")?.let { r ->
            for (i in 0 until r.length()) {
                val o = r.optJSONObject(i) ?: continue
                val id = o.id("teamId") ?: continue
                val n = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                if (refs.none { it.teamId == id }) refs += TeamRef(id, o.optString("leagueKey").takeIf { it.isNotBlank() }, n)
            }
        }
        val m = sp.optJSONObject("muted")
        return SportSettings(
            teams = names,
            teamRefs = refs,
            leagues = sp.optJSONArray("leagues").strings(),
            sports = sp.optJSONArray("sports").strings(),
            mutedSports = m?.optJSONArray("sports").strings(),
            mutedLeagues = m?.optJSONArray("leagues").strings(),
            mutedTags = m?.optJSONArray("tags").strings(),
            sportsSupported = sp.optJSONArray("sports") != null,
        )
    }

    /** /v1/sport/teams?q= → hits ({items} or {teams} or a bare list), deduped by league + id, at most 20. */
    fun parseTeams(body: String): List<TeamHit> {
        val tr = body.trim()
        val a =
            when {
                tr.startsWith("[") -> JSONArray(tr)
                else -> JSONObject(tr).let { it.optJSONArray("items") ?: it.optJSONArray("teams") ?: JSONArray() }
            }
        val out = ArrayList<TeamHit>()
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.id("id") ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val lk = o.optString("leagueKey").takeIf { it.isNotBlank() }
            if (!seen.add("$lk:$id")) continue
            out += TeamHit(id, name, o.optString("league").takeIf { it.isNotBlank() }, lk, o.optString("logo").takeIf { it.isNotBlank() }, o.optString("sport").takeIf { it.isNotBlank() })
        }
        return out.take(20)
    }

    fun isFollowing(
        t: TeamHit,
        s: SportSettings,
    ): Boolean = s.teamRefs.any { it.teamId == t.id } || (t.leagueKey == null && s.teams.any { same(it, t.name) })

    /** Following a team: its name joins teams, its ref joins teamRefs (matched by id; at most 50 refs). */
    fun follow(
        t: TeamHit,
        s: SportSettings,
    ): SportSettings {
        val teams = if (s.teams.any { same(it, t.name) }) s.teams else s.teams + t.name
        val refs = (s.teamRefs.filter { it.teamId != t.id } + TeamRef(t.id, t.leagueKey, t.name)).takeLast(50)
        return s.copy(teams = teams, teamRefs = refs)
    }

    /** Unfollow: the ref goes, and the name goes unless another ref still uses it. */
    fun unfollow(
        t: TeamHit,
        s: SportSettings,
    ): SportSettings {
        val refs = s.teamRefs.filter { it.teamId != t.id }
        val stillUsed = refs.any { same(it.name, t.name) }
        return s.copy(teams = if (stillUsed) s.teams else s.teams.filterNot { same(it, t.name) }, teamRefs = refs)
    }

    /** Remove a favourite by name (the "your teams" chip): its refs go with it. */
    fun removeTeam(
        name: String,
        s: SportSettings,
    ): SportSettings = s.copy(teams = s.teams.filterNot { same(it, name) }, teamRefs = s.teamRefs.filterNot { same(it.name, name) })

    fun removeLeague(
        name: String,
        s: SportSettings,
    ): SportSettings = s.copy(leagues = s.leagues.filterNot { same(it, name) })

    enum class SportState { FOLLOW, MUTE, NEUTRAL }

    fun sportState(
        name: String,
        s: SportSettings,
    ): SportState =
        when {
            s.sports.any { same(it, name) } -> SportState.FOLLOW
            s.mutedSports.any { same(it, name) } -> SportState.MUTE
            else -> SportState.NEUTRAL
        }

    /** Tap cycle: neutral → follow → mute → neutral. */
    fun next(st: SportState): SportState =
        when (st) {
            SportState.NEUTRAL -> SportState.FOLLOW
            SportState.FOLLOW -> SportState.MUTE
            SportState.MUTE -> SportState.NEUTRAL
        }

    /** The lists after setting a sport's state (a sport is never followed and hidden at once). */
    fun applySport(
        name: String,
        st: SportState,
        s: SportSettings,
    ): SportSettings {
        val kept = s.sports.filterNot { same(it, name) }
        val muted = s.mutedSports.filterNot { same(it, name) }
        return s.copy(sports = if (st == SportState.FOLLOW) kept + name else kept, mutedSports = if (st == SportState.MUTE) muted + name else muted)
    }

    fun hasTag(
        s: SportSettings,
        tag: String,
    ) = s.mutedTags.any { same(it, tag) }

    fun withTag(
        s: SportSettings,
        tag: String,
        on: Boolean,
    ): SportSettings = s.copy(mutedTags = s.mutedTags.filterNot { same(it, tag) } + if (on) listOf(tag) else emptyList())

    /** Un-hide one muted sport / league / tag (the "Not interested" list). */
    fun unmute(
        s: SportSettings,
        kind: String,
        value: String,
    ): SportSettings =
        when (kind) {
            "sports" -> s.copy(mutedSports = s.mutedSports.filterNot { same(it, value) })
            "leagues" -> s.copy(mutedLeagues = s.mutedLeagues.filterNot { same(it, value) })
            else -> s.copy(mutedTags = s.mutedTags.filterNot { same(it, value) })
        }

    /** Next default delay in [DELAYS] (wraps). */
    fun nextDelay(cur: Int): Int = DELAYS.firstOrNull { it > cur } ?: DELAYS.first()

    /** The PATCH body for the team lists (names and refs in the same PATCH, as the web does). */
    fun teamsBody(s: SportSettings): JSONObject =
        JSONObject().put(
            "sport",
            JSONObject()
                .put("teams", JSONArray(s.teams))
                .put("teamRefs", JSONArray(s.teamRefs.map { r -> JSONObject().put("teamId", r.teamId).put("name", r.name).apply { r.leagueKey?.let { put("leagueKey", it) } } })),
        )

    fun leaguesBody(s: SportSettings): JSONObject = JSONObject().put("sport", JSONObject().put("leagues", JSONArray(s.leagues)))

    /** Sports + the whole muted block (arrays replace on the server). */
    fun sportsBody(s: SportSettings): JSONObject =
        JSONObject().put(
            "sport",
            JSONObject().put("sports", JSONArray(s.sports)).put("muted", mutedJson(s)),
        )

    fun mutedBody(s: SportSettings): JSONObject = JSONObject().put("sport", JSONObject().put("muted", mutedJson(s)))

    private fun mutedJson(s: SportSettings) =
        JSONObject().put("sports", JSONArray(s.mutedSports)).put("leagues", JSONArray(s.mutedLeagues)).put("tags", JSONArray(s.mutedTags))
}
