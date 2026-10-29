package com.github.damontecres.wholphin.jellytip.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Companion IPTV calls for the TV: guide groups/channels/EPG, favourites, /play. Every call carries the person's token. */
@Singleton
class JellytipIptvService
    @Inject
    constructor(
        private val api: JellytipApi,
        @param:ApplicationContext private val context: Context,
    ) {
        @Volatile
        private var base: String? = null

        @Volatile
        private var liveCheck: Pair<Long, Boolean>? = null

        /**
         * One stable id for this TV, sent as `device` on every /play: the guide preview and the full-screen player are the
         * same screen, so a new /play replaces the old stream (zap) instead of taking a second connection.
         */
        @SuppressLint("HardwareIds")
        val deviceId: String =
            "tv-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "jellytip")

        private suspend fun base(): String = base ?: api.companionBase().also { base = it }

        /** Absolute link for a relative companion path (`v1/iptv/s/…`, `v1/iptv/img/…`); raw provider URLs are refused. */
        fun resolve(
            base: String,
            rel: String,
        ): String? {
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(rel) || rel.startsWith("//")) return null
            return "$base/${rel.trimStart('/')}"
        }

        suspend fun streamUrl(rel: String): String? = resolve(base(), rel)

        private suspend fun get(path: String): Pair<Int, String>? = api.userCall(path)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Opted-in groups in the server's order (`fav` first when there are favourites); null = unavailable. */
        suspend fun groups(): List<IptvGroup>? {
            val (code, body) = get("v1/iptv/live/groups") ?: return null
            if (code != 200) return null
            return runCatching { IptvParse.groups(body) }.getOrNull()
        }

        /** "Live TV" in the sidebar only when the person has opted-in channels (asked at most every 10 minutes). */
        suspend fun hasLiveTv(force: Boolean = false): Boolean {
            val cached = liveCheck
            if (!force && cached != null && System.currentTimeMillis() - cached.first < LIVE_CHECK_TTL_MS) return cached.second
            val has = groups()?.any { it.id != FAV && it.count > 0 } ?: false
            liveCheck = System.currentTimeMillis() to has
            return has
        }

        /** Every channel of [group] (pages of 500, capped at [MAX_CHANNELS]); null = unavailable. */
        suspend fun channels(group: String): List<IptvChannel>? {
            val b = base()
            val out = ArrayList<IptvChannel>()
            var offset = 0
            while (offset < MAX_CHANNELS) {
                val (code, body) = get("v1/iptv/live/channels?group=${enc(group)}&offset=$offset&limit=$PAGE") ?: return null
                if (code != 200) return if (out.isEmpty()) null else out
                val (total, page) = runCatching { IptvParse.channels(body) { resolve(b, it) } }.getOrNull() ?: return null
                out += page
                offset += page.size
                if (page.isEmpty() || offset >= total) break
            }
            return out
        }

        @Volatile
        private var onNowCache: Pair<Long, List<IptvOnNow>>? = null

        /** Last "on now" answer if it is younger than 2 minutes (Home shows it on the first frame). */
        fun peekOnNow(): List<IptvOnNow>? = onNowCache?.takeIf { System.currentTimeMillis() - it.first < ON_NOW_TTL_MS }?.second

        /** Channels with something on now (live sport first); empty for people without a provider. */
        suspend fun onNow(limit: Int = 40): List<IptvOnNow> {
            val b = base()
            val (code, body) = get("v1/iptv/live/onnow?limit=$limit") ?: return emptyList()
            if (code != 200) return emptyList<IptvOnNow>().also { onNowCache = System.currentTimeMillis() to it }
            val list = runCatching { IptvParse.onNow(body, System.currentTimeMillis() / 1000) { resolve(b, it) } }.getOrDefault(emptyList())
            onNowCache = System.currentTimeMillis() to list
            return list
        }

        /** Recently watched channels (recorded by /play), newest first. */
        suspend fun recent(): List<IptvChannel> {
            val b = base()
            val (code, body) = get("v1/iptv/recent") ?: return emptyList()
            if (code != 200) return emptyList()
            return runCatching { IptvParse.channels(body) { resolve(b, it) }.second }.getOrDefault(emptyList())
        }

        /** Programmes for [ids] in [from, to) (epoch s); asked in batches of 300. Channels without data map to empty lists. */
        suspend fun epg(
            ids: List<String>,
            from: Long,
            to: Long,
        ): Map<String, List<IptvProg>>? {
            if (ids.isEmpty()) return emptyMap()
            val out = HashMap<String, List<IptvProg>>()
            for (chunk in ids.chunked(EPG_BATCH)) {
                val path = "v1/iptv/epg?channels=${chunk.joinToString(",") { enc(it) }}&from=$from&to=$to"
                val (code, body) = get(path) ?: return null
                if (code != 200) return null
                val map = runCatching { IptvParse.epg(body) }.getOrNull() ?: return null
                chunk.forEach { id -> out[id] = map[id].orEmpty() }
            }
            return out
        }

        /** Now/next for a whole group in one call (decides which event channels are dark before the list shows). */
        suspend fun epgNow(group: String): Map<String, IptvNowNext> {
            val (code, body) = get("v1/iptv/epg/now?group=${enc(group)}") ?: return emptyMap()
            if (code != 200) return emptyMap()
            return runCatching { IptvParse.epgNow(body) }.getOrDefault(emptyMap())
        }

        suspend fun epgNowFor(ids: List<String>): Map<String, IptvNowNext> {
            if (ids.isEmpty()) return emptyMap()
            val (code, body) = get("v1/iptv/epg/now?channels=${ids.take(EPG_BATCH).joinToString(",") { enc(it) }}") ?: return emptyMap()
            if (code != 200) return emptyMap()
            return runCatching { IptvParse.epgNow(body) }.getOrDefault(emptyMap())
        }

        suspend fun setFavourite(
            channelId: String,
            on: Boolean,
        ): Boolean {
            val body = JSONObject().put("channel", channelId).put("on", on).toString()
            val ok = api.userCall("v1/iptv/favourites", body)?.first == 200
            JellytipTrace.log("iptv", "favourite $channelId on=$on ok=$ok")
            return ok
        }

        /** `/play` for live [channelId], or catch-up when [start]/[stop] are set. */
        suspend fun playChannel(
            channelId: String,
            start: Long? = null,
            stop: Long? = null,
        ): IptvPlayResult {
            val q =
                buildString {
                    append("v1/iptv/play?channel=").append(enc(channelId))
                    if (start != null && stop != null) append("&start=$start&stop=$stop") else append("&format=ts")
                    append("&device=").append(enc(deviceId))
                }
            return play(q)
        }

        /** `/play` for a recording, an IPTV film, or an episode of an IPTV series. */
        suspend fun playItem(target: IptvTarget): IptvPlayResult {
            val q =
                buildString {
                    append("v1/iptv/play?")
                    when {
                        target.recordingId != null -> append("recording=").append(enc(target.recordingId))
                        target.episodeId != null -> append("series=").append(enc(target.seriesId.orEmpty())).append("&episode=").append(enc(target.episodeId))
                        target.vodId != null -> append("vod=").append(enc(target.vodId))
                        else -> return playChannel(target.channelId, target.start, target.stop)
                    }
                    append("&device=").append(enc(deviceId))
                }
            return play(q)
        }

        /** Recordings and the server's space; null = unavailable. */
        suspend fun recordings(): Pair<IptvSpace?, List<IptvRecording>>? {
            val (code, body) = get("v1/iptv/recordings") ?: return null
            if (code != 200) return null
            return runCatching { IptvParse.recordings(body) }.getOrNull()
        }

        /** Record the guide programme of [channelId] starting at [programmeStart] (epoch s). */
        suspend fun record(
            channelId: String,
            programmeStart: Long,
        ): IptvRecordResult {
            val body = JSONObject().put("channel", channelId).put("programme", programmeStart).toString()
            val res = api.userCall("v1/iptv/recordings", body) ?: return IptvRecordResult(false, "The server can't be reached.")
            JellytipTrace.log("iptv", "record $channelId@$programmeStart -> ${res.first}")
            return IptvParse.recordResult(res.first, res.second)
        }

        suspend fun cancelRecording(id: String): Boolean = api.userCall("v1/iptv/recordings/${enc(id)}/cancel", "{}")?.first == 200

        suspend fun deleteRecording(id: String): Boolean = api.userCall("v1/iptv/recordings/${enc(id)}/delete", "{}")?.first == 200

        /** Resume point for a recording ("rec:<id>"), IPTV film or episode id; the server marks it played at 90 %. */
        suspend fun saveProgress(
            item: String,
            positionSecs: Double,
            durationSecs: Double,
        ) {
            val body = JSONObject().put("item", item).put("position", positionSecs).put("duration", durationSecs).toString()
            api.userCall("v1/iptv/progress", body)
        }

        suspend fun play(query: String): IptvPlayResult {
            val res = api.userCall(query) ?: return IptvPlayResult.Failed("network", "The server can't be reached.")
            return IptvParse.play(res.first, res.second)
        }

        companion object {
            const val FAV = "fav"
            const val RECENT = "recent"
            private const val PAGE = 500
            private const val MAX_CHANNELS = 3000
            private const val EPG_BATCH = 300
            private const val LIVE_CHECK_TTL_MS = 10 * 60_000L
            private const val ON_NOW_TTL_MS = 2 * 60_000L
        }
    }
