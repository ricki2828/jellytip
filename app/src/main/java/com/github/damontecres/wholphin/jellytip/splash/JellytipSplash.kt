package com.github.damontecres.wholphin.jellytip.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.jellytip.JellytipOverlayKeys
import com.github.damontecres.wholphin.jellytip.JellytipSession
import com.github.damontecres.wholphin.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Cold-start intro overlay. Drawn on top of the app (which keeps loading underneath), once per process,
 * skipped by any key. Added as a child of the root Surface in MainActivity.
 */
@Composable
fun JellytipSplashOverlay(
    dataStore: DataStore<AppPreferences>,
    coldStart: Boolean,
) {
    var visible by remember { mutableStateOf(coldStart && !JellytipSession.introHandled) }
    if (!visible) return
    val context = LocalContext.current
    var prefs by remember { mutableStateOf<AppPreferences?>(null) }
    LaunchedEffect(Unit) {
        prefs =
            try {
                dataStore.data.first()
            } catch (ex: Exception) {
                Timber.w(ex, "Jellytip: could not read prefs for splash")
                null
            }
        if (prefs == null) {
            JellytipSession.introHandled = true
            visible = false
        }
    }
    val p = prefs
    LaunchedEffect(p) {
        if (p != null && p.jellytipSplashDisabled) {
            JellytipSession.introHandled = true
            visible = false
        }
    }
    DisposableEffect(p) {
        if (p != null && !p.jellytipSplashDisabled) {
            JellytipOverlayKeys.onKey = {
                JellytipSound.stop()
                JellytipSession.introHandled = true
                visible = false
            }
        }
        onDispose { JellytipOverlayKeys.onKey = null }
    }
    // Night background while preferences load, matching the system splash, so the app never flashes through
    Box(Modifier.fillMaxSize().background(JellytipColors.Night)) {
        if (p != null && !p.jellytipSplashDisabled) {
            JellytipIntroTv(
                reduced = JellytipSession.reducedMotion(context),
                soundEnabled = !p.jellytipSplashSoundDisabled,
                onFinished = {
                    JellytipSession.introHandled = true
                    visible = false
                },
            )
        }
    }
}

/**
 * Wraps playback so the Jelly Tip ident plays first. Plays before a film or the first episode of a
 * viewing session, not for autoplay-next (which stays inside the same playback page) and not when
 * playback is re-entered within a few minutes of leaving it.
 */
@Composable
fun JellytipIdentGate(
    prefs: AppPreferences,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(prefs.jellytipIdentDisabled || JellytipSession.inViewingSession()) }
    DisposableEffect(Unit) {
        onDispose { JellytipSession.markPlaybackExit() }
    }
    if (done) {
        content()
    } else {
        DisposableEffect(Unit) {
            JellytipOverlayKeys.onKey = {
                JellytipSound.stop()
                done = true
            }
            onDispose { JellytipOverlayKeys.onKey = null }
        }
        JellytipIdentTv(
            reduced = JellytipSession.reducedMotion(context),
            soundEnabled = !prefs.jellytipSplashSoundDisabled,
            onFinished = { done = true },
        )
    }
}
