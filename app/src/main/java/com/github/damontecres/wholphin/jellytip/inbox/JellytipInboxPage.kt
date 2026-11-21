package com.github.damontecres.wholphin.jellytip.inbox

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import javax.inject.Inject

@HiltViewModel
class JellytipInboxViewModel
    @Inject
    constructor(
        val inbox: JellytipInboxService,
        private val api: ApiClient,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        var refreshing by mutableStateOf(true)
            private set

        init {
            viewModelScope.launch {
                inbox.current()
                inbox.refresh(force = true)
                refreshing = false
            }
        }

        fun open(e: Inbox.Event) {
            when {
                e.link.startsWith("lib:") ->
                    viewModelScope.launch {
                        val id = runCatching { java.util.UUID.fromString(e.link.removePrefix("lib:")) }.getOrNull() ?: return@launch
                        val item = runCatching { BaseItem(api.userLibraryApi.getItem(itemId = id).content) }.getOrNull() ?: return@launch
                        navigationManager.navigateTo(item.destination(), "inbox")
                    }
                e.link.startsWith("iptv:") -> navigationManager.navigateTo(Destination.JellytipIptvDetails(e.link.removePrefix("iptv:"), e.title), "inbox IPTV")
                else -> navigationManager.goToHome()
            }
        }

        override fun onCleared() {
            // Seen once the page closes (the unread marks were shown while it was open)
            inbox.markRead()
        }
    }

/** The inbox: newest first, unread marked; OK opens the title or episode. */
@Composable
fun JellytipInboxPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipInboxViewModel = hiltViewModel(),
) {
    val store by viewModel.inbox.store.collectAsState()
    val now = remember { System.currentTimeMillis() }
    val events = store?.events.orEmpty().filter { now - it.at <= Inbox.EXPIRY_MS }
    val first = remember { FocusRequester() }
    LaunchedEffect(events.isNotEmpty()) {
        if (events.isNotEmpty()) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
        Text("Inbox", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        when {
            events.isEmpty() && viewModel.refreshing -> Text("Looking for anything new…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            events.isEmpty() ->
                Text(
                    "Nothing new yet. New episodes of your favourite and in-progress shows, and titles you asked for, show up here.",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                    fontFamily = JellytipFontFamily,
                )
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.focusGroup()) {
                    itemsIndexed(events, key = { _, e -> e.id }) { i, e ->
                        Surface(
                            onClick = { viewModel.open(e) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                            colors =
                                ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = if (e.read) 0.04f else 0.09f),
                                    focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                                    contentColor = JellytipPalette.Vanilla,
                                    focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                                ),
                            modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text((if (!e.read) "● " else "") + Inbox.text(e), fontFamily = JellytipFontFamily, fontSize = 16.sp)
                                Text(ago(e.at, now), fontFamily = JellytipFontFamily, fontSize = 12.sp)
                            }
                        }
                    }
                }
        }
    }
}

private fun ago(
    at: Long,
    now: Long,
): String {
    val m = (now - at) / 60_000
    return when {
        m < 60 -> "${maxOf(1, m)} min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}
