package com.github.damontecres.wholphin.jellytip.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.preferences.ShowNextUpWhen
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * Library player, split series only (t5n 22-Nov): whenever the series is split and the next episode in the shared split
 * list (splitEpisodes + nextAfter, as the series page) is an IPTV one, offer it through the same Up next card and countdown
 * at the end, instead of the playlist's next (Jellyfin's next up skips IPTV episodes: S2E2 -> E6 over IPTV E3-E5) or
 * leaving. Hooks in upstream code: the item start (prefetch), the top of PlaybackViewModel's STATE_ENDED handling, the
 * outro next-up (held back), and one call in PlaybackPage. Not split / library next: upstream untouched, nothing waits.
 */

/** When an item starts: forget the last offer, and look up this episode's IPTV next in the background (time-boxed). */
fun PlaybackViewModel.jellytipPrefetchIptvNext() {
    jellytipIptvJob?.cancel()
    jellytipIptvPending = null
    jellytipIptvNext.value = null
    val data = runCatching { currentItem.item.data }.getOrNull() ?: return
    if (data.type != BaseItemKind.EPISODE) return
    val seriesId = data.seriesId ?: return
    val season = data.parentIndexNumber ?: return
    val episode = data.indexNumber ?: return
    val itemId = data.id
    jellytipIptvJob =
        viewModelScope.launch {
            val up = NextEpisodeLoader.iptvAfterLibrary(api, serverRepository.currentUser?.id, seriesId, itemId, season, episode)
            // Still the same item (a newer start cancels this job, but be sure)
            if (runCatching { currentItem.item.data.id }.getOrNull() == itemId) jellytipIptvPending = up
        }
}

/**
 * At STATE_ENDED (before the playlist's next): true when the prefetched IPTV next is now offered ([PlaybackViewModel.jellytipIptvNext]);
 * false = carry on exactly as before (leave). Never waits. Respects "never show next up".
 */
fun PlaybackViewModel.jellytipOfferIptvNext(): Boolean {
    val up = jellytipIptvPending ?: return false
    val prefs = preferences.appPreferences.playbackPreferences
    // Same as the library's own next up: "never show" = no card
    if (prefs.showNextUpWhen == ShowNextUpWhen.NEXT_UP_NEVER) return false
    com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
        .log("player", "library end: next episode ${up.ep.code} only via IPTV")
    jellytipIptvPending = null
    jellytipIptvNext.value = up
    return true
}

/**
 * The library player's Up next card for an IPTV next (drawn while [PlaybackViewModel.jellytipIptvNext] is set, i.e. after
 * the episode ENDED): OK plays it (the IPTV player replaces this one), the countdown runs out = the same, Back cancels and
 * leaves (the episode ended). It holds focus and takes every key but Back, so nothing reaches the ended player.
 */
@Composable
fun BoxScope.JellytipLibraryIptvUpNext(viewModel: PlaybackViewModel) {
    val up by viewModel.jellytipIptvNext.collectAsState()
    val cur = up ?: return
    val prefs = viewModel.preferences.appPreferences.playbackPreferences
    var left by remember(cur) { mutableStateOf(UpNextTiming.countdownStart(prefs.autoPlayNext, prefs.autoPlayNextDelaySeconds.toLong())) }
    var gone by remember(cur) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val nav = viewModel.navigationManager

    // THIS player's own destination still on top (not just any Playback page)
    fun onTop() = nav.backStack.lastOrNull() == viewModel.destination

    fun go(how: String) {
        if (gone) return
        gone = true
        viewModel.jellytipIptvNext.value = null
        if (!onTop()) return
        NextEpisodeLoader.trace(cur, how)
        nav.replaceTop(Destination.JellytipIptvPlay(title = cur.label, seriesId = cur.iptvSeriesId, episodeId = cur.ep.id, resumeMs = cur.resumeMs))
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
        if (onTop()) nav.goBack()
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
                    // Back goes on to the BackHandler; everything else stops here (focus can't escape to the player)
                    if (e.key == Key.Back) return@onKeyEvent false
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    // OK acts on its key-down, like the IPTV player's card
                    if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) go("card ok")
                    true
                }.focusable(),
    )
}
