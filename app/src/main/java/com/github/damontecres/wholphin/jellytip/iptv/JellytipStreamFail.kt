package com.github.damontecres.wholphin.jellytip.iptv

import android.content.Context
import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Failed-stream memory (web f12e4580 / 846292d8), TV side. The companion remembers failures per channel / VOD copy /
 * episode and sends `lastFail: {at, reason, count7d}` while the newest failure is newer than the last good play. This
 * file: the parsed shape, a small overlay of failures reported from this device, the note text, the outcome report
 * (`POST v1/iptv/play/outcome`), and the remembered "Ways to watch" pick. Pure apart from [report] and the pick store.
 */
data class IptvLastFail(
    /** Epoch seconds of the newest failure. */
    val at: Long,
    val reason: String,
    /** Failures in the last 7 days. */
    val count7d: Int,
)

object IptvStreamFail {
    val REASONS = setOf("open_failed", "unreachable", "refused", "stall", "recovery_gave_up", "user_report", "decode")

    /** Failed within this long ago = sorted below / not reopened. */
    const val DING_S = 24 * 3600L

    /** A success counts (resets the server's streak) only after this much playback. */
    const val GOOD_PLAY_S = 60L

    fun parse(o: JSONObject?): IptvLastFail? {
        o ?: return null
        val at = o.optDouble("at", 0.0)
        if (at.isNaN() || at <= 0) return null
        val reason = o.optString("reason").takeIf { it in REASONS } ?: "open_failed"
        val n = o.optInt("count7d", 1).coerceAtLeast(1)
        return IptvLastFail(at.toLong(), reason, n)
    }

    // Failures reported from this device (a ding shows at once, before any list refreshes)
    private val local = ConcurrentHashMap<String, IptvLastFail>()

    fun rememberLocal(
        id: String,
        reason: String,
        nowS: Long,
    ) {
        val prev = local[id]
        local[id] = IptvLastFail(nowS, reason, (if (prev != null && prev.at > nowS - 7 * 86400) prev.count7d else 0) + 1)
    }

    fun forgetLocal(id: String) {
        local.remove(id)
    }

    /** The newer of the payload's lastFail and this device's own report. */
    fun failOf(
        id: String,
        given: IptvLastFail?,
    ): IptvLastFail? {
        val l = local[id]
        return when {
            l != null && given != null -> if (l.at > given.at) l.copy(count7d = maxOf(l.count7d, given.count7d)) else given
            else -> l ?: given
        }
    }

    fun failedLately(
        lf: IptvLastFail?,
        nowS: Long,
    ): Boolean = lf != null && lf.at > nowS - DING_S

    /** "Failed 2 h ago" / "Failed 3× this week"; null when nothing failed in the last 7 days. */
    fun note(
        lf: IptvLastFail?,
        nowS: Long,
    ): String? {
        lf ?: return null
        val age = nowS - lf.at
        if (age > 7 * 86400) return null
        if (lf.count7d >= 3) return "Failed ${lf.count7d}× this week"
        if (age < 90) return "Failed just now"
        if (age < 3600) return "Failed ${Math.round(age / 60.0)} min ago"
        if (age < 86400) return "Failed ${Math.round(age / 3600.0)} h ago"
        val d = Math.round(age / 86400.0)
        return "Failed $d day${if (d == 1L) "" else "s"} ago"
    }

    /** Reason for a companion /play refusal; null = not the stream's fault (sign-in, Live TV off, our server, catch-up). */
    fun reasonForCode(code: String?): String? =
        when (code) {
            "provider_refused", "provider_error" -> "refused"
            "provider_unreachable" -> "unreachable"
            "missing", "not_found", "gone" -> "open_failed"
            else -> null
        }

    /**
     * Reason for a player error ([errorCode] = Media3 PlaybackException code, [http] = the link's HTTP status):
     * 404 = gone, 401/403 = refused, decoder faults = this device only (`decode`, never counted against the stream),
     * network = unreachable, anything after playback started = recovery gave up.
     */
    fun reasonFor(
        errorCode: Int,
        http: Int?,
        wasPlaying: Boolean = false,
    ): String =
        when {
            http == 404 || http == 410 -> "open_failed"
            http == 401 || http == 403 -> "refused"
            errorCode in 4001..4005 -> "decode"
            errorCode == 2001 || errorCode == 2002 -> "unreachable"
            wasPlaying -> "recovery_gave_up"
            else -> "open_failed"
        }

    /** id + kind the companion records for a target; null for recordings and catch-up (never dinged). */
    fun outcomeTarget(t: IptvTarget): Pair<String, String>? =
        when {
            t.recordingId != null -> null
            t.vodId != null -> t.vodId to "vod"
            t.episodeId != null -> t.episodeId to "episode"
            t.start != null -> null // catch-up faults never ding the live channel
            t.channelId.isNotBlank() -> t.channelId to "live"
            else -> null
        }

    fun outcomeBody(
        id: String,
        kind: String,
        ok: Boolean,
        reason: String?,
        posSec: Long?,
    ): String =
        JSONObject()
            .apply {
                put("id", id)
                put("kind", kind)
                put("ok", ok)
                if (!ok) put("reason", reason ?: "open_failed")
                if (posSec != null) put("posSec", posSec.coerceAtLeast(0))
            }.toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire and forget; an older server without the route just answers 404. */
    fun report(
        target: IptvTarget,
        ok: Boolean,
        reason: String? = null,
        posSec: Long? = null,
    ) {
        val (id, kind) = outcomeTarget(target) ?: return
        val now = System.currentTimeMillis() / 1000
        if (ok) forgetLocal(id) else rememberLocal(id, reason ?: "open_failed", now)
        val api = JellytipApi.shared ?: return
        scope.launch { runCatching { api.userCall("v1/iptv/play/outcome", outcomeBody(id, kind, ok, reason, posSec)) } }
    }

    // ---- "Ways to watch": the copy picked for a title opens first next time (per device) ----

    /** One key per title: TMDB id when known, else normalised title + year. */
    fun pickKey(
        kind: String,
        tmdbId: Int?,
        title: String,
        year: Int?,
    ): String =
        if (tmdbId != null && tmdbId > 0) {
            "$kind:t$tmdbId"
        } else {
            "$kind:n${title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()}:${year ?: ""}"
        }

    /** The remembered copy when it is still on offer, is not [currentId], and has not failed in the last 24 h. */
    fun rememberedTarget(
        remembered: String?,
        currentId: String,
        copies: List<IptvCopy>,
        nowS: Long,
    ): String? {
        if (remembered == null || remembered == currentId) return null
        val c = copies.firstOrNull { it.id == remembered } ?: return null
        return if (failedLately(failOf(c.id, c.lastFail), nowS)) null else c.id
    }

    private const val PREFS = "jellytip_copy_pick"

    fun recallPick(
        context: Context,
        key: String,
    ): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)

    fun rememberPick(
        context: Context,
        key: String,
        copyId: String,
    ) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, copyId).apply()
}

/** One way to watch a title (companion `copies`, ranked best first by the server). Fields after lastFail are optional. */
data class IptvCopy(
    val id: String,
    val providerName: String?,
    val quality: List<String>,
    /** 4k | fhd | hd | sd */
    val res: String? = null,
    /** "English", "Subtitled", "German"… */
    val langLabel: String? = null,
    /** False = outside the person's switched-on groups. */
    val opted: Boolean? = null,
    val current: Boolean = false,
    val lastFail: IptvLastFail? = null,
) {
    /** Quality words for the chips: provider tokens mapped to the same words as the web, nothing invented. */
    val qualityWords: List<String>
        get() {
            val toks = quality.map { it.lowercase() }.toSet()
            val out = mutableListOf<String>()
            when {
                "4k" in toks || "uhd" in toks || "2160p" in toks -> out += "4K"
                "1080p" in toks || "fhd" in toks -> out += "1080p"
                "720p" in toks || "hd" in toks -> out += "720p"
            }
            if (out.isEmpty()) res?.let { out += RES_WORD[it] ?: it.uppercase() }
            if ("hdr" in toks) out += "HDR"
            if ("dv" in toks || "dolby vision" in toks) out += "Dolby Vision"
            return out
        }

    companion object {
        private val RES_WORD = mapOf("4k" to "4K", "fhd" to "1080p", "hd" to "720p", "sd" to "SD")
    }
}
