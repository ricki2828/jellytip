package com.github.damontecres.wholphin.jellytip.splash

import android.util.Log
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.jellytip.JellytipOverlayKeys
import com.github.damontecres.wholphin.jellytip.JellytipSession
import com.github.damontecres.wholphin.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import timber.log.Timber
import java.util.UUID

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
    val focusManager = LocalFocusManager.current
    if (!visible) {
        // Intro gone: hand focus to the screen underneath (its default target), once
        LaunchedEffect(Unit) {
            if (coldStart) {
                withFrameNanos { }
                focusManager.moveFocus(FocusDirection.Enter)
                JellytipSession.introFocusPending = true
                JellytipSession.introDoneTick.value++
            }
        }
        return
    }
    val context = LocalContext.current
    var prefs by remember { mutableStateOf<AppPreferences?>(null) }
    LaunchedEffect(Unit) {
        // Don't let a slow datastore hold the intro back: after a short wait play with defaults (sound on)
        prefs =
            try {
                withTimeoutOrNull(400) { dataStore.data.first() } ?: AppPreferences.getDefaultInstance()
            } catch (ex: Exception) {
                Timber.w(ex, "Jellytip: could not read prefs for splash")
                AppPreferences.getDefaultInstance()
            }
        Log.i("Jellytip", "splash prefs ready, disabled=${prefs?.jellytipSplashDisabled}")
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
                JellytipOverlayKeys.onKey = null
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
                    JellytipOverlayKeys.onKey = null
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
    itemId: UUID,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    var done by remember { mutableStateOf(prefs.jellytipIdentDisabled || JellytipSession.inViewingSession()) }
    // Only a film or episode gets the ident; trailers, extras, theme videos etc. never do.
    if (!done) {
        LaunchedEffect(itemId) {
            if (!JellytipIdentEligibility.eligible(context, itemId)) done = true
        }
    }
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
            // Back during the ident cancels (leaves the page) instead of starting the film
            JellytipOverlayKeys.onBack = {
                JellytipSound.stop()
                JellytipOverlayKeys.onKey = null
                JellytipOverlayKeys.onBack = null
                backDispatcher?.onBackPressedDispatcher?.onBackPressed()
            }
            onDispose {
                JellytipOverlayKeys.onKey = null
                JellytipOverlayKeys.onBack = null
            }
        }
        JellytipIdentTv(
            reduced = JellytipSession.reducedMotion(context),
            soundEnabled = !prefs.jellytipSplashSoundDisabled,
            onFinished = { done = true },
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface JellytipIdentEntryPoint {
    fun apiClient(): org.jellyfin.sdk.api.client.ApiClient
}

object JellytipIdentEligibility {
    private val kinds =
        setOf(
            org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
            org.jellyfin.sdk.model.api.BaseItemKind.EPISODE,
            org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
            org.jellyfin.sdk.model.api.BaseItemKind.SEASON,
        )

    /** Fails closed: if the item type can't be resolved, no ident. */
    suspend fun eligible(
        context: android.content.Context,
        itemId: UUID,
    ): Boolean =
        try {
            val api =
                dagger.hilt.android.EntryPointAccessors
                    .fromApplication(context.applicationContext, JellytipIdentEntryPoint::class.java)
                    .apiClient()
            val item = kotlinx.coroutines.withTimeoutOrNull(2_000) { api.userLibraryApi.getItem(itemId = itemId).content }
            item != null && item.type in kinds
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ident eligibility lookup failed")
            false
        }
}
