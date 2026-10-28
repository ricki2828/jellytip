package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the signed-in Jellyfin user is an administrator (Policy.IsAdministrator). Media-management
 * features (delete, change still, size sort/badges, season subtitles download, Jellytip settings, audience
 * line) check this and are hidden, not disabled, for everyone else. Defaults to false until the user is known.
 */
object JellytipAdmin {
    private val state = MutableStateFlow(false)
    val isAdminFlow: StateFlow<Boolean> get() = state

    /** Non-composable check, e.g. in view models and services. */
    val isAdmin: Boolean get() = state.value

    fun set(admin: Boolean) {
        state.value = admin
    }
}

/** Composable check; recomposes if the signed-in user changes. */
@Composable
fun rememberIsJellytipAdmin(): State<Boolean> = JellytipAdmin.isAdminFlow.collectAsState()
