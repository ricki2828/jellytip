package com.github.damontecres.wholphin.jellytip.journeys

import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** A load: the answer, a real "not there" (404 only), a server without Journeys v1, or a failure (Retry, never cached). */
sealed interface JvLoad<out T> {
    data class Ok<T>(
        val value: T,
    ) : JvLoad<T>

    data object NotFound : JvLoad<Nothing>

    /** The list route is unknown to this server (Journeys v1 not deployed): the page falls back to the client-built journeys. */
    data object NoRoute : JvLoad<Nothing>

    data class Failed(
        val status: Int?,
    ) : JvLoad<Nothing>
}

/** "Get it" (admin): queued, or why not (the caps / disk floor text from /v1/request). */
sealed interface JvGet {
    data class Queued(
        val tmdbId: Int?,
    ) : JvGet

    data class Refused(
        val reason: String,
    ) : JvGet
}

/*
 * Journeys v1 calls (spec ~/ai/state/fleet/jellytip-journeys-v1-spec.md): every route needs the person's own token (userCall).
 * Nothing is cached across people: [active] (the Home hero slide) is reset on a profile switch, and a page always loads fresh.
 */
@Singleton
class JellytipJourneysV1Service
    @Inject
    constructor(
        private val api: JellytipApi,
        @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    ) {
        init {
            shared = this
        }

        /** A short confirmation (start / remove / drop), on the main thread. */
        fun toast(text: String) {
            scope.launch { runCatching { com.github.damontecres.wholphin.ui.showToast(context, text, android.widget.Toast.LENGTH_SHORT) } }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The active journey for the Home hero slide, with the person it was loaded for. */
        private val _active = MutableStateFlow<Pair<String, JvSummary>?>(null)
        val active: StateFlow<Pair<String, JvSummary>?> = _active.asStateFlow()

        /** The active journey's summary if it belongs to whoever is signed in now. */
        fun activeFor(user: String): JvSummary? = _active.value?.takeIf { it.first == user }?.second

        private fun enc(id: String) = URLEncoder.encode(id, "UTF-8")

        suspend fun home(): JvLoad<JvHome> {
            val user = api.currentUserKey()
            val r = api.userCall("v1/journeys", timeoutMs = 12_000) ?: return JvLoad.Failed(null)
            if (JourneysV1.isRouteMissing(r.first, r.second)) return JvLoad.NoRoute
            if (r.first != 200) {
                JellytipTrace.log("journeys", "list ${r.first}")
                return JvLoad.Failed(r.first)
            }
            val h = JourneysV1.home(r.second, api.companionBase()) ?: return JvLoad.Failed(r.first)
            if (user == api.currentUserKey()) _active.value = h.activeSummary?.let { user to it }
            return JvLoad.Ok(h)
        }

        suspend fun journey(id: String): JvLoad<JvJourney> {
            val r = api.userCall("v1/journeys/${enc(id)}", timeoutMs = 12_000) ?: return JvLoad.Failed(null)
            if (JourneysV1.isNotFound(r.first)) return JvLoad.NotFound
            if (r.first != 200) return JvLoad.Failed(r.first)
            return JourneysV1.journey(r.second, api.companionBase())?.let { JvLoad.Ok(it) } ?: JvLoad.Failed(r.first)
        }

        /** Home: refresh the hero slide's journey (quietly; a failure keeps what was there for this person). */
        fun refreshActive() {
            val user = api.currentUserKey()
            if (_active.value?.first != user) _active.value = null
            scope.launch {
                try {
                    home()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // stays as it was
                }
            }
        }

        /** start | pause | drop: fire and forget (the server records the journey event and its taste signal). */
        fun action(
            id: String,
            what: String,
        ) {
            JellytipTrace.log("journeys", "$what $id")
            scope.launch { runCatching { api.userCall("v1/journeys/${enc(id)}/$what", body = "{}") } }
        }

        /** play | skip of a step (server: journey event + taste signal). */
        fun event(
            id: String,
            type: String,
            tmdbId: Int?,
        ) {
            tmdbId ?: return
            JellytipTrace.log("journeys", "$type $id tmdb=$tmdbId")
            val body = JSONObject().put("type", type).put("tmdbId", tmdbId).toString()
            scope.launch { runCatching { api.userCall("v1/journeys/${enc(id)}/event", body = body) } }
        }

        /** Get it: [body] {} = the next missing step; {"index", "tmdbId"} = that step ([JourneysV1.getBody]). */
        suspend fun get(
            id: String,
            body: String = "{}",
        ): JvGet {
            val r = api.userCall("v1/journeys/${enc(id)}/get", body = body, timeoutMs = 20_000) ?: return JvGet.Refused("Couldn't reach the server. Try again.")
            val o = runCatching { JSONObject(r.second) }.getOrNull()
            return when (r.first) {
                200 -> JvGet.Queued(o?.optInt("tmdbId", 0)?.takeIf { it > 0 })
                403 -> JvGet.Refused("Only an admin can get films.")
                409 -> JvGet.Refused(o?.optString("reason")?.takeIf { it.isNotBlank() } ?: "Can't get it right now.")
                404 -> JvGet.Refused("That step isn't in this journey any more. Go back and open it again.")
                else -> JvGet.Refused("Couldn't ask for it (${r.first}). Try again.")
            }
        }

        companion object {
            /** Set when Hilt builds the singleton (Home reaches it without plumbing, as JellytipApi.shared). */
            @Volatile
            var shared: JellytipJourneysV1Service? = null
                private set
        }
    }
