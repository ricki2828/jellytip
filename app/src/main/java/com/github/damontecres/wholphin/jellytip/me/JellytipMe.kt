package com.github.damontecres.wholphin.jellytip.me

import androidx.compose.runtime.mutableStateMapOf
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * follow the person across TV/web/phone, reactions, follows, taste, kids profile. The server applies the effects to
 * rows/specials/search; the app only renders. A 404 hides every personalisation feature.
 */

data class MePrefs(
    val pinned: List<String>,
    val hidden: List<String>,
    val order: List<String>,
    val specialsPerDay: Int?,
    val hideWatched: Boolean,
    val kidsEnabled: Boolean,
    /** 0, 7, 10, 13 or 16 (18 = no limit). */
    val kidsMaxAge: Int,
    val kidsPinSet: Boolean,
    val intro: Boolean,
    val trailerAutoplay: Boolean,
    val trailerSound: Boolean,
    val subtitleLangs: List<String>,
    val audioLangs: List<String>,
    val letterboxdUser: String?,
    /** none | pending | linked | error */
    val letterboxdStatus: String,
    val version: Int?,
)

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
        val kids = o.optJSONObject("kids") ?: JSONObject()
        val pb = o.optJSONObject("playback") ?: JSONObject()
        val lb = o.optJSONObject("letterboxd") ?: JSONObject()
        val maxAge =
            if (kids.has("maxAge") && !kids.isNull("maxAge")) {
                kids.optInt("maxAge", 18)
            } else {
                when (kids.str("maxRating")?.uppercase()) {
                    "G", "U", "E" -> 0
                    "PG" -> 7
                    "PG-13", "12A", "M" -> 13
                    else -> 16
                }
            }
        return MePrefs(
            pinned = strings(home.optJSONArray("pinned")),
            hidden = strings(home.optJSONArray("hidden")),
            order = strings(home.optJSONArray("order")),
            specialsPerDay = if (home.has("specialsPerDay") && !home.isNull("specialsPerDay")) home.optInt("specialsPerDay") else null,
            hideWatched = o.optBoolean("hideWatched", false),
            kidsEnabled = kids.optBoolean("enabled", false),
            kidsMaxAge = maxAge,
            kidsPinSet = kids.optBoolean("pinSet", false),
            intro = pb.optBoolean("intro", true),
            trailerAutoplay = pb.optBoolean("trailerAutoplay", true),
            trailerSound = pb.optBoolean("trailerSound", false),
            subtitleLangs = strings(pb.optJSONArray("subtitleLangs")),
            audioLangs = strings(pb.optJSONArray("audioLangs")),
            letterboxdUser = lb.str("username"),
            letterboxdStatus = lb.str("status") ?: "none",
            version = if (o.has("version") && !o.isNull("version")) o.optInt("version") else null,
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
            runCatching { MeParse.prefs(res.second) }.getOrNull()?.let { _prefs.value = it }
            _available.value = true
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
        if (res.first != 200) return
        runCatching {
            val o = JSONObject(res.second)
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
            // Changed elsewhere: take the server's copy, the caller can try again
            api?.userCall("v1/me/prefs")?.takeIf { it.first == 200 }?.let { r -> runCatching { MeParse.prefs(r.second) }.getOrNull()?.let { _prefs.value = it } }
        }
        val err = runCatching { JSONObject(res.second).optString("error").ifBlank { JSONObject(res.second).optString("code") } }.getOrNull()
        return res.first to err
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
        }
    }

    suspend fun taste(): MeTaste? {
        val res = api?.userCall("v1/me/taste") ?: return null
        if (res.first != 200) return null
        return runCatching { MeParse.taste(res.second) }.getOrNull()
    }

    /** Set (or change) the kids PIN; stored hashed on the server. */
    suspend fun setKidsPin(pin: String): Boolean = (api?.userCall("v1/me/kids/pin", JSONObject().put("pin", pin).toString())?.first ?: 0) in 200..299

    /** Leaving kids mode needs the PIN: 403 wrong, 429 too many tries (5 min). */
    suspend fun leaveKids(pin: String): String? {
        val (code, _) = patch(JSONObject().put("kids", JSONObject().put("enabled", false).put("pin", pin)))
        return when (code) {
            200 -> null
            403 -> "That PIN isn't right."
            429 -> "Too many tries. Wait 5 minutes."
            else -> "Couldn't leave the kids profile (server said $code)."
        }
    }
}
