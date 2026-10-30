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
) {
    fun isOn(at: Long) = at in start until stop
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
) {
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
) {
    val upcoming get() = state == "scheduled" || state == "recording"
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
)

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
            out += IptvOnNow(ch, title, start, stop, explicit ?: IptvRules.looksLikeSport(hint), kind)
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
        )
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
            IptvHolder(h.str("kind") ?: "watch", h.str("userName") ?: h.str("user"), h.str("label"), h.longOrNull("since"))
        }
    }

    fun recording(o: JSONObject?): IptvRecording? {
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
        )
    }

    fun recordings(json: String): Pair<IptvSpace?, List<IptvRecording>> {
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
        return space to (0 until a.length()).mapNotNull { recording(a.optJSONObject(it)) }
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
                IptvPlayAnswer(url, o.str("mime"), o.str("kind") ?: "live", o.longOrNull("expires"), o.optBoolean("growing", false)),
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
