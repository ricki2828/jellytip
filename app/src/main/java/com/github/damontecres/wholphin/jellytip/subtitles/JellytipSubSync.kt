package com.github.damontecres.wholphin.jellytip.subtitles

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.playback.getAudioStreams
import com.github.damontecres.wholphin.ui.playback.getSubtitleStreams
import com.github.damontecres.wholphin.ui.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONObject
import timber.log.Timber

/*
 * "Auto-match timing" for the library player's subtitle (web cafd5d8d / e241899a, lib/subSync.ts): the companion lines
 * the selected subtitle up with the speech (`POST v1/subs/sync {itemId, streamIndex, mediaSourceId}` -> {jobId} or
 * {cached}; poll `GET v1/subs/sync/{jobId}`) and adds a re-timed track, which the player then switches to.
 */
object SubSync {
    data class Result(
        val offsetSec: Double,
        val speed: Double,
        val confidence: Double,
        val label: String,
        val newStreamIndex: Int?,
        val fileName: String?,
        val alreadySynced: Boolean,
    )

    sealed interface Start {
        data class Job(
            val id: String,
        ) : Start

        data class Done(
            val result: Result,
        ) : Start

        data class Error(
            val message: String,
        ) : Start
    }

    sealed interface Poll {
        data object Waiting : Poll

        data class Done(
            val result: Result,
        ) : Poll

        data class Failed(
            val message: String,
        ) : Poll
    }

    private val JOB_RE = Regex("^[0-9a-f]{12}$")
    const val POLL_MS = 1500L
    const val GIVE_UP_MS = 11 * 60_000L
    const val CHECK_FAILED = "Couldn't check the match"

    /** One match at a time (a second tap would start a second job and a second switch). */
    @Volatile
    var running = false

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null

    fun result(o: JSONObject?): Result? {
        o ?: return null
        val offset = o.num("offsetSec") ?: return null
        return Result(
            offsetSec = offset,
            speed = o.num("speed") ?: 1.0,
            confidence = o.num("confidence") ?: 0.0,
            label = o.optString("label").takeIf { !o.isNull("label") }.orEmpty(),
            newStreamIndex = o.num("newStreamIndex")?.toInt(),
            fileName = o.optString("fileName").takeIf { !o.isNull("fileName") && it.isNotBlank() },
            alreadySynced = o.optBoolean("alreadySynced", false),
        )
    }

    private fun obj(body: String?) = runCatching { JSONObject(body ?: "{}") }.getOrDefault(JSONObject())

    fun start(
        code: Int,
        body: String?,
    ): Start {
        val o = obj(body)
        if (code == 200 || code == 202) {
            o.optString("jobId").takeIf { JOB_RE.matches(it) }?.let { return Start.Job(it) }
            result(o.optJSONObject("cached"))?.let { return Start.Done(it) }
        }
        return when {
            code == 429 -> Start.Error("Matching is busy, try again in a minute")
            code == 401 -> Start.Error("Sign in again to match subtitles")
            code == 0 || (code == 404 && !o.has("error")) -> Start.Error("Auto-match isn't available right now")
            else -> Start.Error(o.optString("error").takeIf { it.isNotBlank() } ?: "Couldn't match this one")
        }
    }

    fun poll(
        code: Int,
        body: String?,
    ): Poll {
        val o = obj(body)
        if (code == 200) {
            when (o.optString("state")) {
                "done" -> result(o.optJSONObject("result"))?.let { return Poll.Done(it) }
                "queued", "running" -> return Poll.Waiting
                "failed" -> return Poll.Failed(o.optString("error").takeIf { it.isNotBlank() } ?: "Couldn't match this one")
            }
        }
        if (code == 404) return Poll.Failed("The match was lost, try again")
        return Poll.Failed(CHECK_FAILED)
    }

    /** "+1.2 s", "−0.5 s". */
    fun offsetLabel(s: Double): String = (if (s > 0) "+" else if (s < 0) "−" else "") + String.format(java.util.Locale.US, "%.1f s", Math.abs(s))
}

/** Start the match for the subtitle playing now; toasts say what happens; on success the re-timed track is selected. */
fun PlaybackViewModel.jellytipMatchSubtitleTiming() {
    val playback = state.value.currentPlayback ?: return
    val streamIndex = playback.subtitleIndex
    if (streamIndex < 0) {
        Toast.makeText(context, "Pick a subtitle first, then match its timing", Toast.LENGTH_LONG).show()
        return
    }
    val companion = JellytipApi.shared ?: return
    if (SubSync.running) {
        Toast.makeText(context, "Already matching these subtitles…", Toast.LENGTH_SHORT).show()
        return
    }
    SubSync.running = true
    // The subtitle files there are now: the re-timed one is the track that isn't among them
    val before = playback.mediaSourceInfo.mediaStreams?.filter { it.type == MediaStreamType.SUBTITLE }?.mapNotNull { it.path }?.toSet().orEmpty()
    viewModelScope.launchIO {
        try {
            val body =
                JSONObject()
                    .put("itemId", playback.itemId.toString().replace("-", ""))
                    .put("streamIndex", streamIndex)
                    .apply { playback.mediaSourceInfo.id?.let { put("mediaSourceId", it) } }
                    .toString()
            val first = companion.userCall("v1/subs/sync", body, timeoutMs = 20_000)
            val result =
                when (val s = SubSync.start(first?.first ?: 0, first?.second)) {
                    is SubSync.Start.Error -> {
                        showToast(context, s.message, Toast.LENGTH_LONG)
                        return@launchIO
                    }
                    is SubSync.Start.Done -> s.result
                    is SubSync.Start.Job -> {
                        showToast(context, "Matching subtitle timing… this can take a minute or two", Toast.LENGTH_LONG)
                        val t0 = System.currentTimeMillis()
                        var misses = 0
                        var got: SubSync.Result? = null
                        while (System.currentTimeMillis() - t0 < SubSync.GIVE_UP_MS) {
                            delay(SubSync.POLL_MS)
                            val r = companion.userCall("v1/subs/sync/${s.id}")
                            when (val p = SubSync.poll(r?.first ?: 0, r?.second)) {
                                is SubSync.Poll.Done -> {
                                    got = p.result
                                    break
                                }
                                is SubSync.Poll.Failed -> {
                                    if (p.message == SubSync.CHECK_FAILED && ++misses < 4) continue
                                    showToast(context, p.message, Toast.LENGTH_LONG)
                                    return@launchIO
                                }
                                SubSync.Poll.Waiting -> misses = 0
                            }
                        }
                        got ?: run {
                            showToast(context, "Timed out while matching this one", Toast.LENGTH_LONG)
                            return@launchIO
                        }
                    }
                }
            if (result.alreadySynced || (result.newStreamIndex == null && result.fileName == null)) {
                showToast(context, "These subtitles already match the speech", Toast.LENGTH_LONG)
                return@launchIO
            }
            // Still the same title? (autoplay / Next may have moved on during a long match: never reload the old one)
            fun stillHere() = state.value.currentPlayback?.itemId == playback.itemId && currentItem.item.id == playback.itemId
            if (!stillHere()) {
                showToast(context, "Subtitles matched: pick the re-timed track next time you play this", Toast.LENGTH_LONG)
                return@launchIO
            }
            // The server added the re-timed track: re-read the item until it shows, then switch to it
            var found: Pair<org.jellyfin.sdk.model.api.MediaSourceInfo, Int>? = null
            repeat(6) {
                if (found != null) return@repeat
                val base = BaseItem(jellytipUserItem(playback.itemId))
                val source = base.data.mediaSources?.firstOrNull { it.id?.toUUIDOrNull() == playback.sourceId } ?: base.data.mediaSources?.firstOrNull()
                val subs = source?.mediaStreams?.filter { it.type == MediaStreamType.SUBTITLE }.orEmpty()
                // Only a track that wasn't there before; with a file name, it must be that file
                // The re-timed track is always an external file
                val fresh = subs.filter { it.path != null && it.path !in before }
                val stream =
                    if (result.fileName != null) {
                        fresh.firstOrNull { f -> f.path?.endsWith(result.fileName) == true }
                    } else {
                        fresh.firstOrNull { it.index == result.newStreamIndex }
                    }
                if (source != null && stream != null && stillHere()) {
                    currentItem =
                        when (currentItem) {
                            is PlaylistItem.Intro -> PlaylistItem.Intro(base)
                            is PlaylistItem.Media -> PlaylistItem.Media(base)
                        }
                    found = source to stream.index
                } else {
                    delay(1500)
                }
            }
            if (!stillHere()) {
                showToast(context, "Subtitles matched: pick the re-timed track next time you play this", Toast.LENGTH_LONG)
                return@launchIO
            }
            val (source, index) =
                found ?: run {
                    showToast(context, "Matched, but the new subtitles didn't show up yet. Pick them from the list in a moment.", Toast.LENGTH_LONG)
                    return@launchIO
                }
            // A new external subtitle can be numbered before the chosen audio track: shift that choice (as the subtitle download does)
            var audioIndex = playback.audioIndex
            if (audioIndex != TrackIndex.UNSPECIFIED && audioIndex >= index) {
                audioIndex += 1
                if ((state.value.currentItemPlayback?.audioIndex ?: TrackIndex.UNSPECIFIED) != TrackIndex.UNSPECIFIED) {
                    saveTrackSelection(audioIndex, MediaStreamType.AUDIO)
                }
            }
            saveTrackSelection(index, MediaStreamType.SUBTITLE)
            updateCurrentMedia { it.copy(sourceId = source.id, audioStreams = getAudioStreams(source), subtitleStreams = getSubtitleStreams(source)) }
            updateCurrentPlayback { it?.copy(item = currentItem.item, mediaSourceInfo = source, audioIndex = audioIndex, subtitleIndex = index) }
            changeStreams(
                item = currentItem.item,
                sourceId = source.id,
                audioIndex = audioIndex,
                subtitleIndex = index,
                positionMs = onMain { player.currentPosition },
                enableDirectPlay = true,
            )
            showToast(
                context,
                "Subtitles matched (${SubSync.offsetLabel(result.offsetSec)}" + (result.label.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + ")",
                Toast.LENGTH_LONG,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Jellytip subtitle auto-match failed")
            showToast(context, "Couldn't match this one", Toast.LENGTH_LONG)
        } finally {
            SubSync.running = false
        }
    }
}

private suspend fun PlaybackViewModel.jellytipUserItem(id: java.util.UUID) = api.userLibraryApi.getItem(itemId = id).content
