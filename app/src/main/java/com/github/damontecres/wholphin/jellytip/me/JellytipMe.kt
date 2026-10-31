package com.github.damontecres.wholphin.jellytip.me

import androidx.compose.runtime.mutableStateMapOf
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
 * Personalisation (companion `/v1/me/...`, contract "Personalisation API" in ~/ai/plans/jellytip-10x.md): prefs that
 * follow the person across TV/web/phone, reactions, follows, taste. The server applies the effects to
 * rows/specials/search; the app only renders. A 404 hides every personalisation feature.
 */

data class MePrefs(
    val pinned: List<String>,
    val hidden: List<String>,
    val order: List<String>,
    val specialsPerDay: Int?,
    val hideWatched: Boolean,
    val intro: Boolean,
    val trailerAutoplay: Boolean,
    val trailerSound: Boolean,
    val subtitleLangs: List<String>,
    val audioLangs: List<String>,
    val letterboxdUser: String?,
    /** none | pending | linked | error */
    val letterboxdStatus: String,
    val version: Int?,
    /** `sport.muted` (plan "Learn sport taste"): never shown in live sport, upcoming or reminders. */
    val sportMuted: SportMuted = SportMuted(),
    /** `sport.spoilers` (plan "Match info"): scores and live stats shown on replays too. Default off. */
    val sportSpoilers: Boolean = false,
)

data class SportMuted(
    val sports: List<String> = emptyList(),
    val leagues: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    fun mutes(
        league: String?,
        sport: String?,
    ): Boolean =
        (league != null && leagues.any { it.equals(league, ignoreCase = true) }) ||
            (sport != null && sports.any { it.equals(sport, ignoreCase = true) })
}

data class MeFollow(
    val personId: UUID,
    val name: String,
    val imageTag: String?,
    val role: String?,
)

data class MeShare(
    val name: String,
    val share: Double,
)

data class MeTaste(
    val genres: List<MeShare>,
    val directors: List<MeShare>,
    val decades: List<MeShare>,
    val countries: List<MeShare>,
    val topRated: List<UUID>,
    val watchedCount: Int,
    val hours: Double,
)

object MeParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    fun prefs(json: String): MePrefs {
        val o = JSONObject(json)
        val home = o.optJSONObject("home") ?: JSONObject()
        val pb = o.optJSONObject("playback") ?: JSONObject()
        val lb = o.optJSONObject("letterboxd") ?: JSONObject()
        return MePrefs(
            pinned = strings(home.optJSONArray("pinned")),
            hidden = strings(home.optJSONArray("hidden")),
            order = strings(home.optJSONArray("order")),
            specialsPerDay = if (home.has("specialsPerDay") && !home.isNull("specialsPerDay")) home.optInt("specialsPerDay") else null,
            hideWatched = o.optBoolean("hideWatched", false),
            intro = pb.optBoolean("intro", true),
            trailerAutoplay = pb.optBoolean("trailerAutoplay", true),
            trailerSound = pb.optBoolean("trailerSound", false),
            subtitleLangs = strings(pb.optJSONArray("subtitleLangs")),
            audioLangs = strings(pb.optJSONArray("audioLangs")),
            letterboxdUser = lb.str("username"),
            letterboxdStatus = lb.str("status") ?: "none",
            version = if (o.has("version") && !o.isNull("version")) o.optInt("version") else null,
            sportMuted =
                o.optJSONObject("sport")?.optJSONObject("muted")?.let { m ->
                    SportMuted(strings(m.optJSONArray("sports")), strings(m.optJSONArray("leagues")), strings(m.optJSONArray("tags")))
                } ?: SportMuted(),
            sportSpoilers = o.optJSONObject("sport")?.optBoolean("spoilers", false) == true,
        )
    }

    fun follows(json: String): List<MeFollow> {
        val a = JSONObject(json).optJSONArray("follows") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val f = a.optJSONObject(i) ?: return@mapNotNull null
            val id = runCatching { f.str("personId")?.toUUID() }.getOrNull() ?: return@mapNotNull null
            MeFollow(id, f.str("name").orEmpty(), f.str("imageTag"), f.str("role"))
        }
    }

    private fun shares(a: JSONArray?): List<MeShare> =
        if (a == null) {
            emptyList()
        } else {
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                MeShare(s.str("name") ?: return@mapNotNull null, s.optDouble("share", 0.0))
            }
        }

    fun taste(json: String): MeTaste {
        val o = JSONObject(json)
        return MeTaste(
            genres = shares(o.optJSONArray("genres")),
            directors = shares(o.optJSONArray("directors")),
            decades = shares(o.optJSONArray("decades")),
            countries = shares(o.optJSONArray("countries")),
            topRated = strings(o.optJSONArray("topRated")).mapNotNull { runCatching { it.toUUID() }.getOrNull() },
            watchedCount = o.optInt("watchedCount", 0),
            hours = o.optDouble("hours", 0.0),
        )
    }
}

/**
 * The signed-in person's personalisation, process-wide (the long-press menu and person pages read it without plumbing).
 * [available] stays false until `/v1/me/prefs` answers 200, so a server without it shows none of these features.
 */
object JellytipMe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api get() = JellytipApi.shared

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> get() = _available

    private val _prefs = MutableStateFlow<MePrefs?>(null)
    val prefs: StateFlow<MePrefs?> get() = _prefs

    /** itemId -> 1 | -1 */
    val thumbs = mutableStateMapOf<String, Int>()

    /** "<itemId>" or "tmdb:<id>" -> true */
    val notInterested = mutableStateMapOf<String, Boolean>()
    val follows = mutableStateMapOf<UUID, MeFollow>()

    /** After sign-in: ask the server once; anything but 200 hides the features. */
    fun refresh() {
        scope.launch {
            val res = api?.userCall("v1/me/prefs", timeoutMs = 6_000)
            if (res == null || res.first != 200) {
                _available.value = false
                JellytipTrace.log("me", "prefs unavailable (${res?.first})")
                return@launch
            }
            val p = runCatching { MeParse.prefs(res.second) }.getOrNull()
            p?.let { _prefs.value = it }
            _available.value = true
            // Intro / trailer settings follow the person: write them into this device's settings
            p?.let {
                runCatching { api?.applyPlaybackPrefs(it.intro, it.trailerAutoplay, it.trailerSound) }
                JellytipTrace.log("me", "device settings from prefs: intro=${it.intro} trailers=${it.trailerAutoplay} sound=${it.trailerSound}")
            }
            loadReactions()
            loadFollows()
        }
    }

    fun reset() {
        _available.value = false
        _prefs.value = null
        thumbs.clear()
        notInterested.clear()
        follows.clear()
    }

    private suspend fun loadReactions() {
        val res = api?.userCall("v1/me/reactions") ?: return
        applyReactions(res)
    }

    /** `GET /reactions` and every thumbs / not-interested change answer `{thumbs, notInterested}`: the server's truth. */
    private fun applyReactions(res: Pair<Int, String>?) {
        if (res == null || res.first != 200) return
        runCatching {
            val o = JSONObject(res.second)
            if (!o.has("thumbs") && !o.has("notInterested")) return
            val t = o.optJSONObject("thumbs")
            thumbs.clear()
            t?.keys()?.forEach { k -> thumbs[k] = t.optInt(k) }
            notInterested.clear()
            val n = o.optJSONArray("notInterested")
            if (n != null) for (i in 0 until n.length()) notInterested[n.optString(i)] = true
        }
    }

    suspend fun loadFollows() {
        val res = api?.userCall("v1/me/follows") ?: return
        if (res.first != 200) return
        val list = runCatching { MeParse.follows(res.second) }.getOrNull() ?: return
        follows.clear()
        list.forEach { follows[it.personId] = it }
    }

    /** Deep-merged PATCH; returns (http code, error code) and keeps [prefs] in step with the answer. */
    suspend fun patch(body: JSONObject): Pair<Int, String?> {
        val v = _prefs.value?.version
        val res =
            api?.userCall("v1/me/prefs", body.toString(), method = "PATCH", headers = if (v != null) mapOf("If-Match" to v.toString()) else emptyMap())
                ?: return 0 to "network"
        JellytipTrace.log("me", "patch ${body.keys().asSequence().toList()} -> ${res.first}")
        if (res.first == 200) {
            runCatching { MeParse.prefs(res.second) }.getOrNull()?.let { _prefs.value = it }
            return 200 to null
        }
        if (res.first == 409) {
            // Changed elsewhere: the answer carries the server's copy ({code:"version", prefs}); the caller can try again
            runCatching { JSONObject(res.second).optJSONObject("prefs")?.toString()?.let(MeParse::prefs) }.getOrNull()?.let { _prefs.value = it }
        }
        val err = runCatching { JSONObject(res.second).optString("error").ifBlank { JSONObject(res.second).optString("code") } }.getOrNull()
        return res.first to err
    }

    /**
     * "Not interested in ‹league›" / "‹sport›" (`sport.muted.leagues` / `.sports`): add or remove [value], PATCHed as the
     * whole list (arrays replace). One retry after a 409 (the answer carried the server's copy). [onDone] gets success.
     */
    fun setSportMuted(
        kind: String,
        value: String,
        on: Boolean,
        onDone: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            var ok = false
            for (attempt in 0..1) {
                val m = _prefs.value?.sportMuted ?: SportMuted()
                val current = if (kind == "sports") m.sports else m.leagues
                val next = if (on) (current.filterNot { it.equals(value, true) } + value) else current.filterNot { it.equals(value, true) }
                val body = JSONObject().put("sport", JSONObject().put("muted", JSONObject().put(kind, JSONArray(next))))
                val (code, _) = patch(body)
                if (code == 200) {
                    ok = true
                    break
                }
                if (code != 409) break
            }
            JellytipTrace.log("me", "sport muted $kind '$value' on=$on ok=$ok")
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** 1 = like, -1 = not for me, 0 = clear. */
    fun thumb(
        itemId: UUID,
        value: Int,
    ) {
        val key = itemId.toString()
        if (value == 0) thumbs.remove(key) else thumbs[key] = value
        scope.launch {
            val r = api?.userCall("v1/me/thumbs", JSONObject().put("itemId", key).put("value", value).toString())
            JellytipTrace.log("me", "thumbs $key=$value -> ${r?.first}")
            applyReactions(r)
        }
    }

    fun thumbOf(itemId: UUID): Int = thumbs[itemId.toString()] ?: 0

    fun isNotInterested(itemId: UUID): Boolean = notInterested[itemId.toString()] == true

    fun setNotInterested(
        itemId: UUID,
        on: Boolean,
    ) {
        val key = itemId.toString()
        if (on) notInterested[key] = true else notInterested.remove(key)
        scope.launch {
            val body = JSONObject().put("itemId", key).toString()
            val r = api?.userCall("v1/me/not-interested", body, method = if (on) "POST" else "DELETE")
            JellytipTrace.log("me", "not-interested $key on=$on -> ${r?.first}")
            applyReactions(r)
        }
    }

    fun isFollowing(personId: UUID): Boolean = follows.containsKey(personId)

    fun follow(
        personId: UUID,
        name: String,
        on: Boolean,
    ) {
        if (on) follows[personId] = MeFollow(personId, name, null, null) else follows.remove(personId)
        scope.launch {
            val body = JSONObject().put("personId", personId.toString()).toString()
            val r = api?.userCall("v1/me/follow", body, method = if (on) "POST" else "DELETE")
            JellytipTrace.log("me", "follow $personId on=$on -> ${r?.first}")
            // The answer is {follows}: the server's list
            if (r?.first == 200) runCatching { MeParse.follows(r.second) }.getOrNull()?.let { list -> follows.clear(); list.forEach { follows[it.personId] = it } }
        }
    }

    suspend fun taste(): MeTaste? {
        val res = api?.userCall("v1/me/taste") ?: return null
        if (res.first != 200) return null
        return runCatching { MeParse.taste(res.second) }.getOrNull()
    }
}
