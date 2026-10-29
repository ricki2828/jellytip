package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject

/*
 * IPTV (companion `/v1/iptv/*`; contract: ~/ai/plans/jellytip-iptv.md). Live TV guide, playback and recordings.
 * Provider credentials never reach the app: streams and logos come through companion links.
 */

/** A guide tab: an opted-in provider group (`<pid>:<catId>`), "fav", or the app's own "recent". */
data class IptvGroup(
    val id: String,
    val name: String,
    val count: Int,
    val providerName: String?,
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
)

/** One guide programme; times are epoch seconds. */
data class IptvProg(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    val catchup: Boolean,
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
            IptvGroup(id, o.str("name") ?: id, o.optInt("count", 0), o.str("providerName"))
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
        )
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

    fun prog(o: JSONObject?): IptvProg? {
        o ?: return null
        val start = o.longOrNull("start") ?: return null
        val stop = o.longOrNull("stop") ?: return null
        if (stop <= start) return null
        return IptvProg(start, stop, o.str("title") ?: "", o.str("desc"), o.optBoolean("catchup", false))
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
