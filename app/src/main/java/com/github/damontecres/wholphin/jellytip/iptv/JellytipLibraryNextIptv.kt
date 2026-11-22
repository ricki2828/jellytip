package com.github.damontecres.wholphin.jellytip.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.ShowNextUpWhen
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * Library player, split series only (t5n 22-Nov): when Jellyfin has no next episode at the end of an episode and the
 * show's next episode is only on IPTV (the series page's "via IPTV" seasons), offer it through the same Up next card and
 * countdown instead of leaving. Two hooks in upstream code: PlaybackViewModel's "No next up" branch and one call in
 * PlaybackPage.
 */

/**
 * At STATE_ENDED with no next playlist item: true when an IPTV next episode is now offered ([PlaybackViewModel.jellytipIptvNext]);
 * false = carry on as before (leave). Respects the person's "never show / never auto play next up" choice.
 */
suspend fun PlaybackViewModel.jellytipOfferIptvNext(): Boolean {
    val data = runCatching { currentItem.item.data }.getOrNull() ?: return false
    if (data.type != BaseItemKind.EPISODE) return false
    val prefs = preferences.appPreferences.playbackPreferences
    if (prefs.showNextUpWhen == ShowNextUpWhen.NEXT_UP_NEVER && !prefs.autoPlayNext) return false
    val seriesId = data.seriesId ?: return false
    val season = data.parentIndexNumber ?: return false
    val episode = data.indexNumber ?: return false
    val up = NextEpisodeLoader.iptvAfterLibrary(api, serverRepository.currentUser?.id, seriesId, season, episode) ?: return false
    com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
        .log("player", "library end: next episode ${up.ep.code} only via IPTV")
    jellytipIptvNext.value = up
    return true
}

/**
 * The library player's Up next card for an IPTV next (drawn while [PlaybackViewModel.jellytipIptvNext] is set): OK plays
 * it (the IPTV player replaces this one), the countdown runs out = the same, Back cancels and leaves (the episode ended).
 */
@Composable
fun BoxScope.JellytipLibraryIptvUpNext(viewModel: PlaybackViewModel) {
    val up by viewModel.jellytipIptvNext.collectAsState()
    val cur = up ?: return
    val prefs = viewModel.preferences.appPreferences.playbackPreferences
    var left by remember(cur) { mutableStateOf(UpNextTiming.countdownStart(prefs.autoPlayNext, prefs.autoPlayNextDelaySeconds.toLong())) }
    var gone by remember(cur) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun go(how: String) {
        if (gone) return
        gone = true
        NextEpisodeLoader.trace(cur, how)
        viewModel.jellytipIptvNext.value = null
        viewModel.navigationManager.replaceTop(
            Destination.JellytipIptvPlay(title = cur.label, seriesId = cur.iptvSeriesId, episodeId = cur.ep.id, resumeMs = cur.resumeMs),
        )
    }
    LaunchedEffect(cur) {
        focus.tryRequestFocus()
        val start = left ?: return@LaunchedEffect
        var n = start
        while (n > 0) {
            delay(1_000)
            n--
            left = n
        }
        go("auto")
    }
    BackHandler {
        NextEpisodeLoader.traceCancelled(cur)
        gone = true
        viewModel.jellytipIptvNext.value = null
        viewModel.navigationManager.goBack()
    }
    JellytipUpNextCard(
        up = cur,
        secondsLeft = left,
        crossover = true,
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = 32.dp)
                .focusRequester(focus)
                .onKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && e.type == KeyEventType.KeyUp) {
                        go("card ok")
                        true
                    } else {
                        ok
                    }
                }.focusable(),
    )
}
