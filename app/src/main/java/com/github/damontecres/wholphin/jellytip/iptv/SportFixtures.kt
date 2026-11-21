package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * Fixture-first Game Centre (web lib/gameCentre.ts, contract `GET v1/iptv/sport/fixtures?from&to&limit&tz[&cap=0]`
 * -> { items, degraded?, more? }). The server ranks, links channels and decides favourites; this file only parses,
 * groups and picks the channel to open. Tested in SportFixturesTest.
 */

data class FxChannel(
    val id: String,
    val name: String,
    val num: Int?,
    val logo: String?,
    val quality: IptvQuality?,
    val confidence: Double,
    val maybe: Boolean,
    val dormant: Boolean,
    val start: Long?,
    val stop: Long?,
    val startOver: Boolean,
    val autoRecord: Boolean?,
    /** Epoch s of the newest failed play the server knows of; null = none. */
    val lastFailAt: Long? = null,
) {
    /** A real link: not a "may carry this" guess, not a dormant league feed. */
    val solid get() = !maybe && !dormant

    fun toIptvChannel(): IptvChannel {
        val (pid, _) = DelayMath.parseChannelKey(id)
        return IptvChannel(id, pid, num, name, logo, "", null, 0, false)
    }
}

data class FxScore(
    val home: Int,
    val away: Int,
    val clock: String?,
    val period: String?,
)

data class FxItem(
    val fid: String,
    val league: String?,
    val leagueKey: String?,
    val sport: String?,
    val home: String,
    val away: String,
    val homeId: String?,
    val awayId: String?,
    val homeShort: String?,
    val awayShort: String?,
    val homeLogo: String?,
    val awayLogo: String?,
    /** Epoch s. */
    val kickoff: Long,
    /** pre | in | post */
    val status: String,
    val stage: String?,
    /** Only sent when the person's spoilers setting is on; shown only after the two-press reveal. */
    val score: FxScore?,
    val significance: Double,
    val rank: Double?,
    val favourite: Boolean,
    val autoRecord: Boolean,
    val channels: List<FxChannel>,
    val best: String?,
    val reminderId: String?,
    val recordingId: String?,
    val matchId: String?,
) {
    val title get() = if (away.isNotBlank()) "$home v $away" else home

    /** The id the live companion asks /v1/sport/match/{id}/live with (the web falls back to the fixture id too). */
    val companionId get() = matchId ?: fid

    val leagueGroupKey get() = leagueKey ?: league ?: ""
}

data class FxMore(
    val league: String,
    val leagueKey: String?,
    /** Local day YYYY-MM-DD in the tz we sent. */
    val day: String,
    val hidden: Int,
)

data class FxList(
    val items: List<FxItem>,
    val degraded: Boolean,
    val more: List<FxMore>,
)

data class FxGroup(
    val id: String,
    val label: String,
    val items: List<FxItem>,
)

object SportFixtures {
    /** Fallback when the server sends no `maybe`: a link below this confidence is a guess. */
    const val MAYBE_BELOW = 0.8
    const val FAIL_FRESH_SEC = 24 * 3600L
    const val PAST_S = 2 * 86400L
    const val AHEAD_S = 4 * 86400L

    private fun JSONObject.s(k: String): String? = if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.n(k: String): Double? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        return (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
    }

    private fun JSONObject.id(k: String): String? {
        if (!has(k) || isNull(k)) return null
        return when (val v = opt(k)) {
            is String -> v.takeIf { it.isNotEmpty() }
            is Int, is Long -> v.toString()
            is Number -> if (v.toDouble().isFinite()) v.toDouble().let { d -> if (d == Math.floor(d)) d.toLong().toString() else d.toString() } else null
            else -> null
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private data class Side(
        val name: String,
        val id: String?,
        val logo: String?,
        val short: String?,
    )

    private fun side(
        o: JSONObject,
        k: String,
        img: (String) -> String?,
    ): Side? {
        if (!o.has(k) || o.isNull(k)) return null
        val v = o.opt(k)
        if (v is String) return v.trim().takeIf { it.isNotEmpty() }?.let { Side(it, null, null, null) }
        val x = v as? JSONObject ?: return null
        val name = x.s("name") ?: x.s("short") ?: return null
        return Side(name, x.id("teamId"), x.s("logo")?.let(img), x.s("short"))
    }

    private fun channel(
        c: JSONObject,
        img: (String) -> String?,
    ): FxChannel? {
        val id = c.id("id") ?: return null
        val conf = c.n("confidence")
        return FxChannel(
            id = id,
            name = c.s("name") ?: "Channel",
            num = c.n("num")?.toInt(),
            logo = c.s("logo")?.let(img),
            quality = runCatching { IptvParse.quality(c.optJSONObject("quality")) }.getOrNull(),
            confidence = (conf ?: 0.5).coerceIn(0.0, 1.0),
            maybe = if (c.has("maybe") && c.opt("maybe") is Boolean) c.optBoolean("maybe") else (conf ?: 0.5) < MAYBE_BELOW,
            dormant = c.optBoolean("dormant", false),
            start = c.n("start")?.toLong(),
            stop = c.n("stop")?.toLong(),
            startOver = c.optBoolean("startOver", false),
            autoRecord = if (c.has("autoRecord") && c.opt("autoRecord") is Boolean) c.optBoolean("autoRecord") else null,
            lastFailAt = c.optJSONObject("lastFail")?.n("at")?.toLong()?.takeIf { it > 0 },
        )
    }

    private fun kickoff(o: JSONObject): Long? {
        val n = o.n("kickoff")
        if (n != null) return if (n > 1e11) (n / 1000).toLong() else n.toLong()
        return IptvParse.isoSeconds(o.s("kickoff"))
    }

    /** Answer -> items. Items without id, sides, kickoff or status are dropped; duplicate ids keep the first. */
    fun parse(
        body: String,
        img: (String) -> String? = { it },
    ): FxList {
        val trimmed = body.trim()
        val root = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
        val list = if (root == null && trimmed.startsWith("[")) JSONArray(trimmed) else root?.optJSONArray("items")
        val out = ArrayList<FxItem>()
        val seen = HashSet<String>()
        for (e in list.objects()) {
            val fid = e.id("fid") ?: continue
            val ko = kickoff(e) ?: continue
            val status = e.s("status")?.takeIf { it == "pre" || it == "in" || it == "post" } ?: continue
            val title = e.s("title")
            val home = side(e, "home", img) ?: title?.let { Side(it, null, null, null) } ?: continue
            val away = side(e, "away", img) ?: if (title != null) Side("", null, null, null) else continue
            if (!seen.add(fid)) continue
            val sc = e.optJSONObject("score")
            val sh = sc?.n("home")
            val sa = sc?.n("away")
            out +=
                FxItem(
                    fid = fid,
                    league = e.s("league"),
                    leagueKey = e.s("leagueKey"),
                    sport = e.s("sport"),
                    home = home.name,
                    away = away.name,
                    homeId = home.id,
                    awayId = away.id,
                    homeShort = home.short,
                    awayShort = away.short,
                    homeLogo = home.logo,
                    awayLogo = away.logo,
                    kickoff = ko,
                    status = status,
                    stage = e.s("stage"),
                    score = if (sc != null && sh != null && sa != null) FxScore(sh.toInt(), sa.toInt(), sc.s("clock"), sc.s("period")) else null,
                    significance = e.n("significance") ?: 0.0,
                    rank = e.n("rank"),
                    favourite = e.optBoolean("favourite", false),
                    autoRecord = e.optBoolean("autoRecord", false),
                    channels = e.optJSONArray("channels").objects().mapNotNull { channel(it, img) },
                    best = e.id("best"),
                    reminderId = e.id("reminderId"),
                    recordingId = e.id("recordingId"),
                    matchId = e.id("matchId"),
                )
        }
        val more =
            root?.optJSONArray("more").objects().mapNotNull { m ->
                val league = m.s("league") ?: return@mapNotNull null
                val day = m.s("day") ?: return@mapNotNull null
                val hidden = m.n("hidden")?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                FxMore(league, m.s("leagueKey"), day, hidden)
            }
        return FxList(out, root?.optBoolean("degraded", false) == true, more)
    }

    fun localDay(
        t: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = Instant.ofEpochSecond(t).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

    data class MoreRow(
        val league: String,
        val key: String,
        val hidden: Int,
    )

    /** "+N more <league> games today · Show all" rows for today, one per league, skipping leagues already shown in full. */
    fun todayMore(
        more: List<FxMore>,
        now: Long,
        expanded: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MoreRow> {
        val day = localDay(now, zone)
        val by = LinkedHashMap<String, MoreRow>()
        for (m in more) {
            val key = m.leagueKey ?: m.league
            if (m.day != day || key in expanded) continue
            by[key] = MoreRow(m.league, key, (by[key]?.hidden ?: 0) + m.hidden)
        }
        return by.values.toList()
    }

    /** The capped list with the "Show all" leagues swapped for their games from the uncapped answer. */
    fun merge(
        base: List<FxItem>,
        full: List<FxItem>,
        expanded: Set<String>,
    ): List<FxItem> = if (expanded.isEmpty()) base else base.filter { it.leagueGroupKey !in expanded } + full.filter { it.leagueGroupKey in expanded }

    /** Live first, then starred (favourite) upcoming, then the rest by server rank / significance / kickoff; finished last. */
    fun group(items: List<FxItem>): List<FxGroup> {
        val cmp =
            compareByDescending<FxItem> { it.favourite }
                .thenBy { it.rank ?: Double.MAX_VALUE }
                .thenByDescending { it.significance }
                .thenBy { it.kickoff }
                .thenBy { it.fid }
        fun pick(f: (FxItem) -> Boolean) = items.filter(f).sortedWith(cmp)
        return listOf(
            FxGroup("live", "Live now", pick { it.status == "in" }),
            FxGroup("yours", "Your teams", pick { it.status == "pre" && it.favourite }),
            FxGroup("big", "Upcoming", pick { it.status == "pre" && !it.favourite }),
            FxGroup("finished", "Finished", items.filter { it.status == "post" }.sortedByDescending { it.kickoff }),
        ).filter { it.items.isNotEmpty() }
    }

    private val BUCKET = listOf("uhd", "fhd50", "fhd", "hd", "unknown", "sd", "slow", "dead")

    private fun bucketRank(c: FxChannel) = BUCKET.indexOf(c.quality?.bucket ?: "unknown").let { if (it < 0) 4 else it }

    /** Channels in the order to try: the server's `best` when solid, then solid links, then quality, dormant last. */
    fun ranked(f: FxItem): List<FxChannel> {
        val given = f.channels.firstOrNull { it.id == f.best }
        val r =
            f.channels
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<FxChannel>> { it.value.dormant }
                        .thenBy { it.value.maybe }
                        .thenBy { bucketRank(it.value) }
                        .thenBy { it.index },
                ).map { it.value }
        return if (given != null && given.solid) listOf(given) + r.filter { it !== given } else r
    }

    data class ChannelPick(
        val channel: FxChannel?,
        /** Name of the channel passed over because it failed in the last 24 h. */
        val skipped: String?,
        /** Every channel in try order (the chosen one first). */
        val order: List<FxChannel>,
    )

    /** The channel to open: a channel that failed lately gives way to the next that did not; all failed = the oldest failure. */
    fun pickChannel(
        f: FxItem,
        nowS: Long,
    ): ChannelPick {
        val order = ranked(f)
        if (order.isEmpty()) return ChannelPick(null, null, order)
        fun bad(c: FxChannel) = c.lastFailAt != null && nowS - c.lastFailAt < FAIL_FRESH_SEC
        val first = order.first()
        val chosen: FxChannel
        val skipped: String?
        if (!bad(first)) {
            chosen = first
            skipped = null
        } else {
            val alt = order.firstOrNull { !bad(it) }
            if (alt != null) {
                chosen = alt
                skipped = first.name
            } else {
                chosen = order.minBy { it.lastFailAt ?: 0L }
                skipped = if (chosen === first) null else first.name
            }
        }
        return ChannelPick(chosen, skipped, listOf(chosen) + order.filter { it !== chosen })
    }

    private fun hm(
        t: Long,
        zone: ZoneId,
    ) = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

    /** "Starts 19:30" today, "Starts Sat 19:30" otherwise. */
    fun startsText(
        start: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val d: LocalDate = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val day = if (d == today) "" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
        return "Starts $day${hm(start, zone)}"
    }

    /** The status line under a fixture tile. Never a score. */
    fun note(
        f: FxItem,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val best = pickChannel(f, now).channel
        val solid = best?.solid == true
        return when (f.status) {
            "pre" ->
                if (best == null) {
                    "${startsText(f.kickoff, now, zone)} · No stream found yet"
                } else {
                    "${startsText(f.kickoff, now, zone)} · ${if (solid) best.name else "may be on ${best.name}"}"
                }
            "in" -> if (best == null) "Live · No stream found yet" else if (solid) "Live on ${best.name}" else "Live · may be on ${best.name}"
            else -> if (best?.startOver == true && best.start != null && best.stop != null && !best.dormant) "Finished — watch from the start" else "Finished — no replay available"
        }
    }

    /**
     * Match id to hand the player for a Game Centre launch of a live fixture (web 74acc58b: live launches carry it, on any
     * of the fixture's channels). Catch-up / recordings stay spoiler-free: no id.
     */
    fun liveMatchId(
        f: FxItem,
        chosen: FxChannel?,
    ): String? = if (f.status == "in" && chosen != null) f.companionId else null

    const val SOON_S = 15 * 60L

    /** A channel opened directly: the live (or about-to-start) fixture whose channels include it; guesses come back as maybe. */
    data class ChannelMatch(
        val matchId: String,
        val maybe: Boolean,
        val title: String,
    )

    fun resolveChannelMatch(
        items: List<FxItem>,
        channel: String?,
        nowSec: Long,
    ): ChannelMatch? {
        if (channel.isNullOrBlank()) return null
        val want = channel.trim().lowercase()
        var best: Triple<ChannelMatch, Double, Boolean>? = null
        for (f in items) {
            val mid = f.companionId
            val live = f.status == "in"
            if (!live && !(f.status == "pre" && f.kickoff - nowSec <= SOON_S && f.kickoff - nowSec > -SOON_S)) continue
            val c = f.channels.firstOrNull { it.id.trim().lowercase() == want } ?: continue
            val b = best
            if (b == null || (live && !b.third) || (live == b.third && c.confidence > b.second)) {
                best = Triple(ChannelMatch(mid, c.maybe || c.dormant, if (f.away.isNotBlank()) "${f.home} v ${f.away}" else f.league ?: ""), c.confidence, live)
            }
        }
        return best?.first
    }
}
