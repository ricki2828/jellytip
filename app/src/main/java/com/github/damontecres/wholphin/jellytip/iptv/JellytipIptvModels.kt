package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject

/*
 * IPTV (companion `/v1/iptv/...`; contract: ~/ai/plans/jellytip-iptv.md). Live TV guide, playback and recordings.
 * Provider credentials never reach the app: streams and logos come through companion links.
 */

/** A guide tab: an opted-in provider group (`<pid>:<catId>`), "fav", or the app's own "recent". */
data class IptvGroup(
    val id: String,
    val name: String,
    val count: Int,
    val providerName: String?,
    /** The server's all-channels "Guide" (`default:true`): dark event feeds already left out. */
    val isDefault: Boolean = false,
)

data class IptvChannel(
    val id: String,
    val providerId: String,
    val num: Int?,
    val name: String,
    /** Absolute companion image link (signed, no login), or null: draw initials. */
    val logo: String?,
    val group: String,
    val epgId: String?,
    val catchupDays: Int,
    val favourite: Boolean,
    /** Server flag: PPV / event feed ("live when on"); null = old server, the app guesses from the names. */
    val event: Boolean? = null,
    /** e.g. "Event feed, live when on" | "No guide for this channel". */
    val guideNote: String? = null,
    /** Probed stream quality (live-sport plan §1); null = old server or never probed. */
    val quality: IptvQuality? = null,
    /** Server: a sport channel (sport icon); null = not said. */
    val sport: Boolean? = null,
    /** Same event on other channels, best first (`event.alternates`); empty = none known. */
    val alternates: List<String> = emptyList(),
    /** The same alternates described (`eventGroup.alternateChannels`: name, num, logo, quality), best first. */
    val alternateChannels: List<IptvChannel> = emptyList(),
)

/** "also on 2 channels" for a card that stands for an event shown on several channels. */
fun alsoOnText(alternates: Int): String? =
    when {
        alternates <= 0 -> null
        alternates == 1 -> "also on 1 channel"
        else -> "also on $alternates channels"
    }

/**
 * Probed stream quality (`quality: {bucket, height, fps, audio, checkedAt}`, ~/ai/plans/jellytip-live-sport.md §1/1b).
 * Buckets: uhd, fhd50, fhd, hd, sd, slow, dead, unknown. Audio: atmos, 5.1, stereo.
 */
data class IptvQuality(
    val bucket: String?,
    val height: Int?,
    val fps: Double?,
    val audio: String?,
    /** Server hint, e.g. "may buffer" (slow, or below real time but not yet confirmed). */
    val hint: String? = null,
) {
    /** Tile chip: UHD / 1080 50fps / 1080 / 720 / SD; a slow channel is labelled by its resolution. Null = no chip. */
    val label: String?
        get() =
            when (bucket) {
                "uhd" -> "UHD"
                "fhd50" -> "1080 50fps"
                "fhd" -> "1080"
                "hd" -> "720"
                "sd" -> "SD"
                "slow" ->
                    height?.let {
                        when {
                            it >= 2000 -> "UHD"
                            it >= 1000 -> "1080"
                            it >= 700 -> "720"
                            it > 0 -> "SD"
                            else -> null
                        }
                    }
                else -> null
            }

    /** ATMOS / 5.1; nothing for stereo or unknown. */
    val audioLabel: String?
        get() =
            when (audio?.lowercase()) {
                "atmos" -> "ATMOS"
                "5.1" -> "5.1"
                else -> null
            }

    /** Delivered below real time on recent checks: show "may buffer". */
    val slow get() = bucket == "slow" || hint?.contains("buffer", ignoreCase = true) == true
}

/** One guide programme; times are epoch seconds. */
data class IptvProg(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    val catchup: Boolean,
    /** live | replay | highlights | other (server, live-sport plan §2); null = old server. */
    val kind: String? = null,
    val sport: Boolean? = null,
    /** A live programme that can be restarted (catch-up of what is on now, plan "8h Block 3"). */
    val startOver: Boolean = false,
    /** pre | live | post (plan "Pre-game state"); null = unknown / older server. */
    val phase: String? = null,
    /** The real start of the event (epoch s), when the server knows it. */
    val startsAt: Long? = null,
) {
    fun isOn(at: Long) = at in start until stop

    /** "From kick-off" is offered: the server says so, or the programme has catch-up. */
    val canStartOver get() = startOver || catchup
}

data class IptvNowNext(
    val now: IptvProg?,
    val next: IptvProg?,
)

/** Who holds a provider connection (409 answers, connections). */
data class IptvHolder(
    val kind: String,
    val userName: String?,
    val label: String?,
    val since: Long?,
    /** A recording holding the slot (plan "Stop recording" pop-up): its id, so the client can stop it. */
    val recordingId: String? = null,
    val title: String? = null,
    val channel: String? = null,
    /** Epoch seconds the holder lets go (a recording's padded stop); null = unknown. */
    val endsAt: Long? = null,
) {
    val isRecording get() = kind == "recording"

    val text: String
        get() =
            listOfNotNull(userName, if (kind == "recording") "recording" else "watching", label)
                .joinToString(" ")
}

data class IptvPlayAnswer(
    val url: String,
    val mime: String?,
    val kind: String,
    val expires: Long?,
    val growing: Boolean,
    /**
     * Direct from provider (plan "IPTV playback on phones", part C; owner + `client=native` only): [url] is the
     * provider's own absolute link with the login inside (never trace it); the one-connection guard can't hold.
     */
    val direct: Boolean = false,
)

/** A server-side recording (ffmpeg copy on the seedbox). Times are epoch seconds. */
data class IptvRecording(
    val id: String,
    val channelId: String,
    val channel: String,
    val title: String,
    val desc: String?,
    val start: Long,
    val stop: Long,
    /** scheduled | recording | done | failed | cancelled */
    val state: String,
    val bytes: Long,
    val error: String?,
    val watched: Boolean,
    val playable: Boolean,
    val positionSecs: Double?,
    val durationSecs: Double?,
    /** Channel logo (absolute, resolved), when the server sends one; null = initials. */
    val logo: String? = null,
) {
    val upcoming get() = state == "scheduled" || state == "recording"

    /** Finished and playable, not watched yet ("NEW" on Home). */
    val readyNew get() = state == "done" && playable && !watched
}

/**
 * Home "Recordings" row order (plan "Recordings prominent"): recording now, ready and unwatched (newest first),
 * scheduled (soonest first), watched (newest first). Cancelled and failed ones are left out.
 */
fun homeRecordings(list: List<IptvRecording>): List<IptvRecording> {
    val now = list.filter { it.state == "recording" }.sortedBy { it.start }
    val fresh = list.filter { it.readyNew }.sortedByDescending { it.start }
    val scheduled = list.filter { it.state == "scheduled" }.sortedBy { it.start }
    val watched = list.filter { it.state == "done" && it.watched }.sortedByDescending { it.start }
    return now + fresh + scheduled + watched
}

data class IptvSpace(
    val usedGb: Double,
    val capGb: Double,
    val freeGb: Double?,
    val canRecord: Boolean,
    val reason: String?,
)

/** A `POST /recordings` answer: [ok] with an optional [warning] (e.g. it takes the only connection), or why not. */
data class IptvRecordResult(
    val ok: Boolean,
    val message: String?,
)

/** A channel with something airing now (`/live/onnow`); [sport] = live sport, listed first. */
data class IptvOnNow(
    val channel: IptvChannel,
    val title: String,
    val start: Long,
    val stop: Long,
    val sport: Boolean,
    /** live | replay | highlights | other; null = old server (the card shows LIVE as before). */
    val kind: String? = null,
    /** Other channels showing the same event, best first (already collapsed into this one card). */
    val alternates: List<IptvChannel> = emptyList(),
    val art: IptvArt? = null,
    /** League / sport names when the server knows them ("Not interested in ‹league›"); null = unknown. */
    val league: String? = null,
    val sportName: String? = null,
    /** The programme can be watched from its start (catch-up of what is on now). */
    val startOver: Boolean = false,
    /** pre | live | post (plan "Pre-game state"); null = unknown. */
    val phase: String? = null,
    /** The real start (epoch s) when known. */
    val startsAt: Long? = null,
) {
    fun isPre(now: Long) = phase == "pre" && (startsAt == null || now < startsAt)
}

/**
 * Sport artwork (`art: {league?: {badge, fanart}, home?: {name, badge}, away?: {name, badge}}`, plan "Sport artwork"):
 * real TheSportsDB art served by the companion. Links are absolute (resolved); null = not known.
 */
data class IptvArt(
    val leagueBadge: String?,
    val leagueFanart: String?,
    val homeName: String?,
    val homeBadge: String?,
    val awayName: String?,
    val awayBadge: String?,
) {
    val hasTeams get() = homeBadge != null && awayBadge != null

    /** A badge takes the tile's logo slot (the channel logo moves to a corner). */
    val hasBadge get() = hasTeams || leagueBadge != null
    val isEmpty get() = leagueBadge == null && leagueFanart == null && !hasTeams
}

/**
 * One upcoming sport event (`GET /v1/iptv/sport/upcoming`, plan "Upcoming sport"): the best channel and the others
 * showing it, whether it matches a favourite team/league, and whether it can be (or already is) recorded.
 */
data class IptvUpcoming(
    val key: String,
    val title: String,
    val league: String?,
    /** Matches a favourite team or league (★, sorted first by the server). */
    val favourite: Boolean,
    val start: Long,
    val stop: Long,
    val kind: String?,
    val channel: IptvChannel,
    val alternateChannels: List<IptvChannel>,
    val recordable: Boolean,
    val recordingId: String?,
    val art: IptvArt? = null,
    /** The person's reminder for this event (plan "Remind me"); null = none. */
    val reminderId: String? = null,
    /** Sport name ("cricket", "speedway") when the server sends one; null = unknown. */
    val sportName: String? = null,
    /** pre | live | post (plan "Pre-game state"); null = unknown. */
    val phase: String? = null,
    /** The real start (epoch s) when known; it can differ from the EPG [start]. */
    val startsAt: Long? = null,
) {
    fun isLive(now: Long) = now in start until stop

    /** Starts within 15 minutes (plan "Remind me": `soon`). */
    fun isSoon(now: Long) = !isLive(now) && start > now && start - now <= SOON_SECS

    companion object {
        const val SOON_SECS = 15 * 60L
    }
}

/** What a `/play` call came back with. */
sealed interface IptvPlayResult {
    data class Ok(
        val answer: IptvPlayAnswer,
    ) : IptvPlayResult

    /** Every connection is taken (409): [message] names who holds it. */
    data class Full(
        val message: String,
        val holders: List<IptvHolder>,
    ) : IptvPlayResult

    data class Failed(
        val code: String?,
        val message: String,
    ) : IptvPlayResult
}

object IptvParse {
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)

    fun groups(json: String): List<IptvGroup> {
        val a = JSONObject(json).optJSONArray("groups") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            IptvGroup(id, o.str("name") ?: id, o.optInt("count", 0), o.str("providerName"), o.optBoolean("default", false))
        }
    }

    /** [resolve] turns a relative companion path (logos) into an absolute link; raw provider URLs are dropped. */
    fun channel(
        o: JSONObject,
        resolve: (String) -> String?,
    ): IptvChannel? {
        val id = o.str("id") ?: return null
        return IptvChannel(
            id = id,
            providerId = o.str("providerId") ?: id.substringBefore(':'),
            num = if (o.isNull("num")) null else o.optInt("num", 0).takeIf { it > 0 },
            name = o.str("name") ?: id,
            logo = o.str("logo")?.let(resolve),
            group = o.str("group").orEmpty(),
            epgId = o.str("epgId"),
            catchupDays = o.optInt("catchupDays", 0),
            favourite = o.optBoolean("favourite", false),
            event = if (o.has("event") && !o.isNull("event") && o.opt("event") is Boolean) o.optBoolean("event") else null,
            guideNote = o.str("guideNote"),
            quality = quality(o.optJSONObject("quality")),
            // Guide default list: `nowSport` says whether what is on now is sport
            sport =
                when {
                    o.has("sport") && !o.isNull("sport") -> o.optBoolean("sport")
                    o.has("nowSport") && !o.isNull("nowSport") -> o.optBoolean("nowSport")
                    else -> null
                },
            alternates = alternates(eventGroup(o)),
            alternateChannels = alternateChannels(eventGroup(o), resolve),
        )
    }

    fun quality(o: JSONObject?): IptvQuality? {
        o ?: return null
        return IptvQuality(
            bucket = o.str("bucket"),
            height = if (o.isNull("height")) null else o.optInt("height", 0).takeIf { it > 0 },
            fps = if (o.isNull("fps")) null else o.optDouble("fps").takeIf { !it.isNaN() && it > 0 },
            audio = o.str("audio"),
            hint = o.str("hint"),
        )
    }

    /**
     * Same-event group: `eventGroup: {key, alternates}` (server as built). `event` on a channel is the PPV-feed boolean;
     * an `event` object is accepted only for the draft contract.
     */
    private fun eventGroup(o: JSONObject): JSONObject? = o.optJSONObject("eventGroup") ?: o.optJSONObject("event")

    /** `eventGroup.alternateChannels: [{id, name, num, logo, quality}]` as channels (group/provider from the id). */
    fun alternateChannels(
        event: JSONObject?,
        resolve: (String) -> String?,
    ): List<IptvChannel> {
        val a = event?.optJSONArray("alternateChannels") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { channel(it, resolve) } }
    }

    /** Alternate [id] of [main]: described by the server, else found in [known] (the lineup), else a bare channel to tune. */
    fun resolveAlternate(
        id: String,
        main: IptvChannel,
        known: Map<String, IptvChannel>,
    ): IptvChannel = main.alternateChannels.firstOrNull { it.id == id } ?: known[id] ?: altChannel(id, main)

    private fun alternates(event: JSONObject?): List<String> {
        val a = event?.optJSONArray("alternates") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.opt(i)?.let { v -> (v as? JSONObject)?.str("id") ?: (v as? String) }?.takeIf { it.isNotBlank() } }
    }

    fun channels(
        json: String,
        resolve: (String) -> String?,
    ): Pair<Int, List<IptvChannel>> {
        val root = JSONObject(json)
        val a = root.optJSONArray("channels") ?: JSONArray()
        val list = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { channel(it, resolve) } }
        return root.optInt("total", list.size) to list
    }

    /**
     * `/live/onnow`, read defensively until its shape is pinned: `channels[]` or `items[]` (or a bare array), each a
     * channel (or `{channel:{…}}`) with `now` / `programme` = {start, stop, title}. Live sport first, then server order.
     */
    fun onNow(
        json: String,
        now: Long,
        resolve: (String) -> String?,
    ): List<IptvOnNow> {
        val trimmed = json.trimStart()
        val list: JSONArray =
            if (trimmed.startsWith("[")) {
                JSONArray(json)
            } else {
                val r = JSONObject(json)
                r.optJSONArray("channels") ?: r.optJSONArray("items") ?: JSONArray()
            }
        val out = ArrayList<IptvOnNow>()
        val seen = HashSet<String>()
        val events = HashMap<String, Int>() // event key -> index in out
        val byId = HashMap<String, IptvChannel>()
        var ranked = false // the new server ranks (plan §4): keep its order exactly
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val chObj = c.optJSONObject("channel") ?: c
            val p = c.optJSONObject("now") ?: c.optJSONObject("programme") ?: c.optJSONObject("program") ?: continue
            val base = channel(chObj, resolve) ?: continue
            // Quality / event may sit on the item instead of the channel object
            val event = eventGroup(c) ?: eventGroup(chObj)
            val ch =
                base.copy(
                    quality = base.quality ?: quality(c.optJSONObject("quality")),
                    alternates = base.alternates.ifEmpty { alternates(event) },
                    alternateChannels = base.alternateChannels.ifEmpty { alternateChannels(event, resolve) },
                )
            byId[ch.id] = ch
            if (!seen.add(ch.id)) continue
            val start = p.longOrNull("start") ?: continue
            val stop = p.longOrNull("stop") ?: continue
            val title = p.str("title") ?: continue
            if (now !in start until stop) continue
            val kind = p.str("kind") ?: c.str("kind")
            if (kind != null || ch.quality != null || event != null) ranked = true
            val explicit =
                when {
                    p.has("sport") && !p.isNull("sport") -> p.optBoolean("sport")
                    c.has("sport") && !c.isNull("sport") -> c.optBoolean("sport")
                    chObj.has("sport") && !chObj.isNull("sport") -> chObj.optBoolean("sport")
                    else -> null
                }
            // One card per event: later channels of the same event become its alternates
            val key = event?.str("key")
            val firstOfEvent = key?.let { events[it] }
            if (firstOfEvent != null) {
                val f = out[firstOfEvent]
                if (f.channel.alternates.none { it == ch.id }) {
                    out[firstOfEvent] = f.copy(channel = f.channel.copy(alternates = f.channel.alternates + ch.id))
                }
                continue
            }
            val hint = listOfNotNull(c.str("genre"), c.str("category"), chObj.str("groupName"), ch.group, ch.name, title).joinToString(" ")
            if (key != null) events[key] = out.size
            val art = art(c.optJSONObject("art") ?: p.optJSONObject("art"), resolve)
            val match = c.optJSONObject("match") ?: p.optJSONObject("match")
            out +=
                IptvOnNow(
                    ch,
                    title,
                    start,
                    stop,
                    explicit ?: IptvRules.looksLikeSport(hint),
                    kind,
                    art = art,
                    league = c.str("league") ?: p.str("league") ?: match?.str("league"),
                    startOver = p.optBoolean("startOver", false) || c.optBoolean("startOver", false) || p.optBoolean("catchup", false),
                    phase = p.str("phase") ?: c.str("phase") ?: event?.str("phase"),
                    startsAt = isoSeconds(p.str("startsAt") ?: c.str("startsAt") ?: event?.str("startsAt")),
                    sportName = c.str("sportName") ?: p.str("sportName") ?: match?.str("sportName"),
                )
        }
        val resolved =
            out.map { o ->
                // Ids only in `alternates` (older answer) still get the described channel when alternateChannels has it
                val ids = (o.channel.alternates + o.channel.alternateChannels.map { it.id }).distinct().filter { it != o.channel.id }
                o.copy(alternates = ids.map { id -> resolveAlternate(id, o.channel, byId) })
            }
        return if (ranked) resolved else resolved.filter { it.sport } + resolved.filter { !it.sport }
    }

    /** An alternate the answer named but did not describe: enough to tune it (name from the main channel). */
    fun altChannel(
        id: String,
        main: IptvChannel,
    ): IptvChannel = IptvChannel(id, id.substringBefore(':'), null, "${main.name} (alt)", null, main.group, null, 0, false)

    fun art(
        o: JSONObject?,
        resolve: (String) -> String?,
    ): IptvArt? {
        o ?: return null
        fun img(v: String?) = v?.let { if (it.startsWith("https://")) it else resolve(it) }
        val league = o.optJSONObject("league")
        val home = o.optJSONObject("home")
        val away = o.optJSONObject("away")
        return IptvArt(
            leagueBadge = img(league?.str("badge")),
            leagueFanart = img(league?.str("fanart")),
            homeName = home?.str("name"),
            homeBadge = img(home?.str("badge")),
            awayName = away?.str("name"),
            awayBadge = img(away?.str("badge")),
        ).takeIf { !it.isEmpty }
    }

    /** `{items: [...]}` (or `events`, or a bare array); entries without a channel or times are dropped. Server order kept. */
    /** [reminders] = a `GET /v1/iptv/reminders` answer (same item shape; the item's `id` is the reminder's id). */
    fun upcoming(
        json: String,
        reminders: Boolean = false,
        resolve: (String) -> String?,
    ): List<IptvUpcoming> {
        val t = json.trimStart()
        val a =
            if (t.startsWith("[")) {
                JSONArray(json)
            } else {
                JSONObject(json).let { it.optJSONArray("items") ?: it.optJSONArray("events") ?: JSONArray() }
            }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ch = o.optJSONObject("channel")?.let { channel(it, resolve) } ?: return@mapNotNull null
            val start = o.longOrNull("start") ?: return@mapNotNull null
            val stop = o.longOrNull("stop") ?: (start + 2 * 3600)
            val match = o.optJSONObject("match")
            val alts =
                o.optJSONArray("alternateChannels")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { c -> channel(c, resolve) } } }.orEmpty()
            IptvUpcoming(
                key = o.str("key") ?: "${ch.id}@$start",
                title = o.str("title") ?: return@mapNotNull null,
                league = o.str("league") ?: match?.str("league"),
                favourite = match != null && (match.str("team") != null || match.str("league") != null),
                start = start,
                stop = stop,
                kind = o.str("kind"),
                channel = ch,
                alternateChannels = alts.filter { it.id != ch.id },
                recordable = o.optBoolean("recordable", true),
                recordingId = o.str("recordingId"),
                art = art(o.optJSONObject("art"), resolve),
                reminderId = o.str("reminderId") ?: if (reminders) o.str("id") else null,
                sportName = o.str("sportName") ?: match?.str("sportName"),
                phase = o.str("phase"),
                startsAt = isoSeconds(o.str("startsAt")),
            )
        }
    }

    fun prog(o: JSONObject?): IptvProg? {
        o ?: return null
        val start = o.longOrNull("start") ?: return null
        val stop = o.longOrNull("stop") ?: return null
        if (stop <= start) return null
        return IptvProg(
            start,
            stop,
            o.str("title") ?: "",
            o.str("desc"),
            o.optBoolean("catchup", false),
            kind = o.str("kind"),
            sport = if (o.has("sport") && !o.isNull("sport")) o.optBoolean("sport") else null,
            startOver = o.optBoolean("startOver", false),
            phase = o.str("phase"),
            startsAt = isoSeconds(o.str("startsAt")),
        )
    }

    /** ISO-8601 UTC ("2026-10-31T18:00:00Z", offsets allowed) or epoch seconds as text -> epoch seconds; null if neither. */
    fun isoSeconds(v: String?): Long? {
        v ?: return null
        v.toLongOrNull()?.let { return it }
        return runCatching { java.time.OffsetDateTime.parse(v).toEpochSecond() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(v).epochSecond }.getOrNull()
    }

    /** `/epg` answer: programmes per channel, sorted, overlaps trimmed so cells never stack. */
    fun epg(json: String): Map<String, List<IptvProg>> {
        val chans = JSONObject(json).optJSONObject("channels") ?: return emptyMap()
        return chans.keys().asSequence().associateWith { id ->
            val a = chans.optJSONArray(id) ?: JSONArray()
            IptvRules.tidy((0 until a.length()).mapNotNull { prog(a.optJSONObject(it)) })
        }
    }

    fun epgNow(json: String): Map<String, IptvNowNext> {
        val chans = JSONObject(json).optJSONObject("channels") ?: return emptyMap()
        return chans.keys().asSequence().associateWith { id ->
            val o = chans.optJSONObject(id)
            IptvNowNext(prog(o?.optJSONObject("now")), prog(o?.optJSONObject("next")))
        }
    }

    fun holders(o: JSONObject?): List<IptvHolder> {
        val conn = o?.optJSONObject("connection")
        val a = conn?.optJSONArray("active") ?: o?.optJSONArray("holders") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val h = a.optJSONObject(i) ?: return@mapNotNull null
            IptvHolder(
                kind = h.str("kind") ?: "watch",
                userName = h.str("userName") ?: h.str("user") ?: h.str("name"),
                label = h.str("label"),
                since = h.longOrNull("since"),
                recordingId = h.str("recordingId"),
                title = h.str("title"),
                channel = h.str("channel"),
                endsAt = h.longOrNull("endsAt"),
            )
        }
    }

    fun recording(
        o: JSONObject?,
        resolve: (String) -> String? = { null },
    ): IptvRecording? {
        o ?: return null
        val id = o.str("id") ?: return null
        val prog = o.optJSONObject("progress")
        return IptvRecording(
            id = id,
            channelId = o.str("channelId").orEmpty(),
            channel = o.str("channel").orEmpty(),
            title = o.str("title") ?: "Recording",
            desc = o.str("desc"),
            start = o.optLong("start"),
            stop = o.optLong("stop"),
            state = o.str("state") ?: "scheduled",
            bytes = o.optLong("bytes", 0),
            error = o.str("error"),
            watched = o.optBoolean("watched", false),
            playable = o.optBoolean("playable", false),
            positionSecs = prog?.takeUnless { it.isNull("position") }?.optDouble("position"),
            durationSecs = prog?.takeUnless { it.isNull("duration") }?.optDouble("duration"),
            logo = (o.str("logo") ?: o.optJSONObject("channelInfo")?.str("logo"))?.let(resolve),
        )
    }

    fun recordings(
        json: String,
        resolve: (String) -> String? = { null },
    ): Pair<IptvSpace?, List<IptvRecording>> {
        val root = JSONObject(json)
        val sp = root.optJSONObject("space")
        val space =
            sp?.let {
                IptvSpace(
                    it.optDouble("usedGb", 0.0),
                    it.optDouble("capGb", 0.0),
                    if (it.isNull("freeGb")) null else it.optDouble("freeGb"),
                    it.optBoolean("canRecord", true),
                    it.str("reason"),
                )
            }
        val a = root.optJSONArray("recordings") ?: JSONArray()
        return space to (0 until a.length()).mapNotNull { recording(a.optJSONObject(it), resolve) }
    }

    fun recordResult(
        code: Int,
        json: String,
    ): IptvRecordResult {
        val o = runCatching { JSONObject(json) }.getOrNull()
        if (code in 200..299) return IptvRecordResult(true, o?.str("warning"))
        val text =
            when (o?.str("code")) {
                "too_many" -> "Too many recordings already (20 upcoming, 2 at once)."
                "no_space" -> "Not enough space on the server to record."
                "too_long" -> "Recordings can be at most 6 hours."
                "too_far" -> "Recordings can be set at most 14 days ahead."
                "bad_window" -> "That programme can't be recorded (it's over)."
                else -> o?.str("error") ?: "Server said $code"
            }
        return IptvRecordResult(false, text)
    }

    fun play(
        code: Int,
        json: String,
    ): IptvPlayResult {
        val o = runCatching { JSONObject(json) }.getOrNull()
        if (code == 200 && o != null) {
            val url = o.str("url") ?: return IptvPlayResult.Failed("bad_answer", "The server sent no stream link.")
            return IptvPlayResult.Ok(
                IptvPlayAnswer(url, o.str("mime"), o.str("kind") ?: "live", o.longOrNull("expires"), o.optBoolean("growing", false), o.optBoolean("direct", false)),
            )
        }
        val errCode = o?.str("code")
        val text = o?.str("error")
        if (code == 409 || errCode == "max_connections") {
            val holders = holders(o)
            return IptvPlayResult.Full(
                text ?: ("All connections are in use" + (holders.firstOrNull()?.let { ": ${it.text}" } ?: "")),
                holders,
            )
        }
        return IptvPlayResult.Failed(errCode, IptvRules.failureText(errCode, text ?: "Server said $code"))
    }
}
