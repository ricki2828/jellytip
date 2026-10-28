package com.github.damontecres.wholphin.jellytip.subtitles

import android.content.Context
import android.widget.Toast
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.ui.showToast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whole-season subtitle fetch: asks the companion to run one job per season (it searches the subtitle providers
 * for every episode lacking the language), polls the jobs and reports progress and a final summary as toasts.
 * Runs in an app-level scope so it survives navigating away.
 */
@Singleton
class JellytipSeasonSubtitles
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            shared = this
        }

        private data class Job(
            val id: String,
            var done: Int = 0,
            var total: Int = 0,
            var finished: Boolean = false,
            var summary: String = "",
        )

        /** Which seasons a menu on [item] covers: its own season for an episode, itself for a season, every season for a series. */
        private suspend fun seasonIds(item: BaseItem): List<UUID> =
            when (item.type) {
                BaseItemKind.SEASON -> listOf(item.id)
                BaseItemKind.EPISODE -> listOfNotNull(item.data.seasonId)
                BaseItemKind.SERIES ->
                    api.tvShowsApi
                        .getSeasons(item.id)
                        .content.items
                        .map { it.id }
                else -> emptyList()
            }

        fun start(item: BaseItem) {
            val language =
                serverRepository.currentUserDto
                    ?.configuration
                    ?.subtitleLanguagePreference
                    ?.takeIf { it.isNotBlank() } ?: "eng"
            scope.launch {
                try {
                    val companion = JellytipApi.shared
                    val seasons = seasonIds(item)
                    if (companion == null || seasons.isEmpty()) {
                        showToast(context, "Could not start the season subtitle search", Toast.LENGTH_LONG)
                        return@launch
                    }
                    showToast(context, "Searching $language subtitles for ${if (seasons.size > 1) "${seasons.size} seasons" else "the season"}...")
                    val jobs =
                        seasons.mapNotNull { sid ->
                            val res =
                                companion.postJson(
                                    "/v1/subtitles/season",
                                    JSONObject().put("seasonId", sid.toString()).put("language", language).toString(),
                                )
                            res?.takeIf { it.first in 200..299 }?.let { Job(JSONObject(it.second).getString("jobId")) }
                        }
                    if (jobs.isEmpty()) {
                        showToast(context, "Subtitle search is unavailable right now", Toast.LENGTH_LONG)
                        return@launch
                    }
                    track(companion, jobs)
                } catch (ex: Exception) {
                    Timber.w(ex, "Season subtitles failed")
                    showToast(context, "Season subtitle search failed", Toast.LENGTH_LONG)
                }
            }
        }

        private suspend fun track(
            companion: JellytipApi,
            jobs: List<Job>,
        ) {
            var lastShown = ""
            var failures = 0
            repeat(MAX_POLLS) {
                delay(POLL_MS)
                for (job in jobs.filter { !it.finished }) {
                    val body = companion.getJson("/v1/subtitles/jobs/${job.id}")
                    if (body == null) {
                        failures++
                        continue
                    }
                    failures = 0
                    val v = JSONObject(body)
                    job.total = v.optInt("total")
                    job.done = job.total - v.optInt("remaining")
                    job.finished = v.optString("state") in setOf("done", "failed")
                    job.summary = v.optString("summary")
                    v.optString("message").takeIf { it.isNotBlank() && it != "null" }?.let { job.summary += " ($it)" }
                }
                if (failures > 5) {
                    showToast(context, "Lost contact with the subtitle service", Toast.LENGTH_LONG)
                    return
                }
                if (jobs.all { it.finished }) {
                    showToast(context, jobs.joinToString("\n") { it.summary }.ifBlank { "Subtitle search finished" }, Toast.LENGTH_LONG)
                    return
                }
                val progress = "Subtitles: ${jobs.sumOf { it.done }} of ${jobs.sumOf { it.total }} episodes checked..."
                if (progress != lastShown && jobs.all { it.total > 0 }) {
                    lastShown = progress
                    showToast(context, progress, Toast.LENGTH_SHORT)
                }
            }
            showToast(context, "Subtitle search is still running on the server", Toast.LENGTH_LONG)
        }

        companion object {
            @Volatile
            var shared: JellytipSeasonSubtitles? = null
                private set

            private const val POLL_MS = 4_000L
            private const val MAX_POLLS = 450 // 30 min
        }
    }
