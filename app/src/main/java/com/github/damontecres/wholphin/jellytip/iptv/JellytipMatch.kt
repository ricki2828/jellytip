package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject

/*
 * Match info (~/ai/plans/jellytip-live-sport.md, "Match info: identify unnamed listings, plus a match modal"): an
 * optional `matchInfo` on live programmes, eventGroups, upcoming items and EPG programmes (`match` stays the older
 * favourite marker {team, league}), and
 * `GET /v1/sport/match/{id}` for the modal. Every field is optional.
 */

data class MatchTeam(
    val name: String,
    val short: String?,
    val logo: String?,
    val record: String?,
    /** Team colours, hex ("#0B2265" or "0B2265"; plan "match info: more pop"); null = neutral greys. */
    val color: String? = null,
    val altColor: String? = null,
)

data class MatchScore(
    val home: String,
    val away: String,
    val clock: String?,
    val period: String?,
)

data class IptvMatch(
    val id: String,
    val league: String?,
    /** Machine league key ("nfl", "nba"…; plan "fantasy, round 2"). */
    val leagueKey: String? = null,
    val home: MatchTeam?,
    val away: MatchTeam?,
    /** Epoch s; null = unknown. */
    val startsAt: Long?,
    val venue: String?,
    /** pre | in | post */
    val status: String?,
    /** Identified from the fixtures, not named by the listing: tiles show the matchup with an "identified" mark. */
    val inferred: Boolean,
    /** Only sent when the person's spoilers setting is on. */
    val score: MatchScore?,
) {
    /** "Crusaders v Blues" when both teams are known. */
    val matchup: String?
        get() = if (home != null && away != null) "${home.name} v ${away.name}" else null
}

data class MatchPlayer(
    val name: String,
    val pos: String?,
    val num: String?,
    val starter: Boolean,
)

data class MatchOddsSide(
    val moneyline: String?,
    val spread: String?,
)

data class MatchOdds(
    val provider: String?,
    val home: MatchOddsSide?,
    val away: MatchOddsSide?,
    val draw: String?,
    val overUnder: String?,
    /** Epoch s. */
    val updatedAt: Long?,
)

data class MatchDetail(
    val match: IptvMatch,
    val lineupsAnnounced: Boolean?,
    val homeLineup: List<MatchPlayer>,
    val awayLineup: List<MatchPlayer>,
    val odds: MatchOdds?,
    val homeForm: List<String>,
    val awayForm: List<String>,
    /** Last meetings as text lines ("2025-08-02 Crusaders 24-17 Blues"). */
    val h2h: List<String>,
    val broadcasts: List<String>,
    val channels: List<IptvChannel>,
    val pending: Boolean,
    /** Stadium photo (`venue.image`), shown faint behind the hero; null = none. */
    val venueImage: String? = null,
)

object MatchParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }

    private fun team(
        o: JSONObject?,
        resolve: (String) -> String?,
    ): MatchTeam? {
        o ?: return null
        val name = o.str("name") ?: return null
        return MatchTeam(name, o.str("short"), o.str("logo")?.let { if (it.startsWith("http")) it else resolve(it) }, o.str("record"), o.str("color"), o.str("altColor"))
    }

    fun isFixture(o: JSONObject?): Boolean = o != null && o.str("id") != null && o.optJSONObject("home") != null && o.optJSONObject("away") != null

    /** `matchInfo` (needs id + home + away). */
    fun match(
        o: JSONObject?,
        resolve: (String) -> String? = { it },
    ): IptvMatch? {
        o ?: return null
        // A fixture has an id and both teams
        if (!isFixture(o)) return null
        val id = o.str("id") ?: return null
        val venue = o.optJSONObject("venue")?.let { v -> listOfNotNull(v.str("name"), v.str("city")).joinToString(", ").ifBlank { null } } ?: o.str("venue")
        val sc =
            o.optJSONObject("score")?.let { s ->
                val h = s.str("home") ?: return@let null
                val a = s.str("away") ?: return@let null
                MatchScore(h, a, s.str("clock"), s.str("period"))
            }
        return IptvMatch(
            id = id,
            league = o.str("league"),
            leagueKey = o.str("leagueKey"),
            home = team(o.optJSONObject("home"), resolve),
            away = team(o.optJSONObject("away"), resolve),
            startsAt = IptvParse.isoSeconds(o.str("startsAt")),
            venue = venue,
            status = o.str("status"),
            inferred = o.optBoolean("inferred", false),
            score = sc,
        )
    }

    private fun players(a: JSONArray?): List<MatchPlayer> =
        if (a == null) {
            emptyList()
        } else {
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                MatchPlayer(p.str("name") ?: return@mapNotNull null, p.str("pos"), p.str("num"), p.optBoolean("starter", true))
            }
        }

    private fun side(o: JSONObject?): MatchOddsSide? = o?.let { MatchOddsSide(it.str("moneyline"), it.str("spread")) }

    fun detail(
        json: String,
        resolve: (String) -> String?,
    ): MatchDetail? {
        val root = JSONObject(json)
        val o = root.optJSONObject("matchInfo") ?: root.optJSONObject("match")?.takeIf { isFixture(it) } ?: root
        val m = match(o, resolve) ?: return null
        val lineups = o.optJSONObject("lineups")
        val odds =
            o.optJSONObject("odds")?.let {
                MatchOdds(it.str("provider"), side(it.optJSONObject("home")), side(it.optJSONObject("away")), it.str("draw"), it.str("overUnder"), IptvParse.isoSeconds(it.str("updatedAt")))
            }
        val form = o.optJSONObject("form")
        val h2h =
            o.optJSONArray("h2h")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { h ->
                        listOfNotNull(h.str("date")?.take(10), h.str("home"), listOfNotNull(h.str("homeScore"), h.str("awayScore")).joinToString("-").ifBlank { null }, h.str("away"))
                            .joinToString(" ")
                            .ifBlank { null }
                    } ?: a.optString(i).takeIf { it.isNotBlank() && it != "null" }
                }
            }.orEmpty()
        val channels =
            o.optJSONArray("channels")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { IptvParse.channel(it, resolve) } } }.orEmpty()
        return MatchDetail(
            match = m,
            lineupsAnnounced = lineups?.let { if (it.has("announced")) it.optBoolean("announced") else null },
            homeLineup = players(lineups?.optJSONArray("home")),
            awayLineup = players(lineups?.optJSONArray("away")),
            odds = odds,
            homeForm = strings(form?.optJSONArray("home")),
            awayForm = strings(form?.optJSONArray("away")),
            h2h = h2h,
            broadcasts = strings(o.optJSONArray("broadcasts")),
            channels = channels,
            pending = root.optBoolean("pending", false) || o.optBoolean("pending", false),
            venueImage = o.optJSONObject("venue")?.str("image")?.let { if (it.startsWith("http")) it else resolve(it) },
        )
    }
}
