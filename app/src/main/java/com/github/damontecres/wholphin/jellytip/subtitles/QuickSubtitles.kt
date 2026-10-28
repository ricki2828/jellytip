package com.github.damontecres.wholphin.jellytip.subtitles

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.getPreferredLanguage
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.playback.SubtitleSearchStatus
import com.github.damontecres.wholphin.ui.playback.downloadAndSwitchSubtitles
import com.github.damontecres.wholphin.ui.showToast
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import timber.log.Timber

/**
 * Picks the best remote subtitle: hash match, then release-name similarity to the playing file, then most
 * downloads, then rating. Forced and hearing-impaired tracks are only used when nothing else exists.
 */
object JellytipSubtitleRanker {
    private fun tokens(s: String?): Set<String> =
        s
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.lowercase()
            ?.split(Regex("[^a-z0-9]+"))
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    /** 0 = unrelated, 1 = loosely similar, 2 = same release */
    private fun releaseMatch(
        fileName: String?,
        subtitleName: String?,
    ): Int {
        val a = tokens(fileName)
        val b = tokens(subtitleName)
        if (a.isEmpty() || b.isEmpty()) return 0
        val sim = a.intersect(b).size.toDouble() / a.union(b).size
        return when {
            sim >= 0.6 -> 2
            sim >= 0.3 -> 1
            else -> 0
        }
    }

    fun pick(
        options: List<RemoteSubtitleInfo>,
        playingFileName: String?,
    ): RemoteSubtitleInfo? {
        val usable = options.filter { it.id != null }
        val pool =
            usable.filter { it.forced != true && it.hearingImpaired != true }
                .ifEmpty { usable.filter { it.forced != true } }
                .ifEmpty { usable }
        return pool.sortedWith(
            compareByDescending<RemoteSubtitleInfo> { it.isHashMatch == true }
                .thenByDescending { releaseMatch(playingFileName, it.name) }
                .thenByDescending { it.downloadCount ?: 0 }
                .thenByDescending { it.communityRating ?: 0f },
        ).firstOrNull()
    }
}

/**
 * One-press "Get subtitles": search in the user's preferred subtitle language (English if none), auto-pick
 * the best match, download and select it using the existing download flow. The search dialog shows a spinner
 * while the server queries its providers (25-60 s); empty results and errors say so plainly.
 */
fun PlaybackViewModel.jellytipQuickSubtitles() {
    if (subtitleSearchState.value.status != SubtitleSearchStatus.Inactive) return
    val playback = state.value.currentPlayback ?: return
    val language =
        getPreferredLanguage(
            MediaStreamType.SUBTITLE,
            preferences,
            serverRepository.currentUserDto?.configuration,
        )?.takeIf { it.isNotBlank() } ?: "eng"
    subtitleSearchState.update { it.copy(status = SubtitleSearchStatus.Searching, language = language) }
    viewModelScope.launchIO {
        try {
            val results = JellytipSubtitleSearch.search(context, playback.itemId, language)
            val best = JellytipSubtitleRanker.pick(results, playback.mediaSourceInfo.path)
            if (best == null) {
                subtitleSearchState.update { it.copy(status = SubtitleSearchStatus.Inactive) }
                showToast(context, JellytipSubtitleSearch.NONE_FOUND_MESSAGE, Toast.LENGTH_LONG)
                return@launchIO
            }
            val label =
                buildString {
                    append(best.name ?: best.providerName ?: "subtitles")
                    if (best.isHashMatch == true) append(" (hash match)")
                    best.downloadCount?.let { append(", $it downloads") }
                }
            Timber.i("Jellytip quick subtitles picked %s for %s", label, playback.itemId)
            showToast(context, context.getString(R.string.jellytip_subtitles_picked, label), Toast.LENGTH_LONG)
            val wasPlaying = onMain { player.isPlaying }
            downloadAndSwitchSubtitles(best.id, wasPlaying)
        } catch (ex: Exception) {
            Timber.e(ex, "Jellytip quick subtitles failed")
            subtitleSearchState.update { it.copy(status = SubtitleSearchStatus.Inactive) }
            showToast(context, JellytipSubtitleSearch.messageFor(ex), Toast.LENGTH_LONG)
        }
    }
}
