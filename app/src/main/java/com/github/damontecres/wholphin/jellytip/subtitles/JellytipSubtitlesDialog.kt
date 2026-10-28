package com.github.damontecres.wholphin.jellytip.subtitles

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.playback.SubtitleInfo
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import timber.log.Timber
import javax.inject.Inject

sealed interface JellytipSubtitlesState {
    data object Searching : JellytipSubtitlesState

    data class Results(
        val options: List<RemoteSubtitleInfo>,
        val best: RemoteSubtitleInfo?,
    ) : JellytipSubtitlesState

    data object Downloading : JellytipSubtitlesState

    data class Message(
        val text: String,
    ) : JellytipSubtitlesState
}

@HiltViewModel
class JellytipSubtitlesViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow<JellytipSubtitlesState>(JellytipSubtitlesState.Searching)
        val state: StateFlow<JellytipSubtitlesState> = _state

        fun search(item: BaseItem) {
            _state.value = JellytipSubtitlesState.Searching
            val language =
                serverRepository.currentUserDto
                    ?.configuration
                    ?.subtitleLanguagePreference
                    ?.takeIf { it.isNotBlank() } ?: "eng"
            viewModelScope.launchIO {
                try {
                    val results = JellytipSubtitleSearch.search(context, item.id, language)
                    if (results.isEmpty()) {
                        _state.value = JellytipSubtitlesState.Message(JellytipSubtitleSearch.NONE_FOUND_MESSAGE)
                    } else {
                        val path =
                            item.data.mediaSources
                                ?.firstOrNull()
                                ?.path
                        val sorted =
                            results.sortedWith(
                                compareByDescending<RemoteSubtitleInfo> { it.isHashMatch == true }
                                    .thenByDescending { it.downloadCount ?: 0 },
                            )
                        _state.value =
                            JellytipSubtitlesState.Results(sorted, JellytipSubtitleRanker.pick(results, path))
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Subtitle search failed")
                    _state.value = JellytipSubtitlesState.Message(JellytipSubtitleSearch.messageFor(ex))
                }
            }
        }

        fun download(
            item: BaseItem,
            subtitle: RemoteSubtitleInfo,
            onDone: () -> Unit,
        ) {
            val id = subtitle.id ?: return
            _state.value = JellytipSubtitlesState.Downloading
            viewModelScope.launchIO {
                try {
                    api.subtitleApi.downloadRemoteSubtitles(itemId = item.id, subtitleId = id)
                    showToast(context, "Subtitles added. Pick them in the player's subtitle menu.", Toast.LENGTH_LONG)
                    onDone()
                } catch (ex: Exception) {
                    Timber.e(ex, "Subtitle download failed")
                    _state.update { JellytipSubtitlesState.Message("Subtitle download failed: ${ex.localizedMessage}") }
                }
            }
        }
    }

@Composable
fun JellytipBusy(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(16.dp),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.border,
            modifier = Modifier.size(32.dp),
        )
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * "Subtitles" from the More menu on a movie or episode: searches the server's providers for the preferred
 * language, offers "Get best match" first, then every result.
 */
@Composable
fun JellytipSubtitlesDialog(
    item: BaseItem,
    onDismissRequest: () -> Unit,
    viewModel: JellytipSubtitlesViewModel = hiltViewModel(),
) {
    LaunchedEffect(item.id) { viewModel.search(item) }
    val state by viewModel.state.collectAsState()
    BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp).widthIn(min = 480.dp, max = 720.dp),
        ) {
            Text(
                text = stringResource(R.string.jellytip_subtitles_menu),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (val s = state) {
                JellytipSubtitlesState.Searching ->
                    JellytipBusy(stringResource(R.string.jellytip_subtitles_searching_long))

                JellytipSubtitlesState.Downloading ->
                    JellytipBusy(stringResource(R.string.downloading))

                is JellytipSubtitlesState.Message ->
                    Text(
                        text = s.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                is JellytipSubtitlesState.Results -> {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) { first.tryRequestFocus() }
                    LazyColumn(modifier = Modifier.widthIn(max = 720.dp)) {
                        if (s.best != null) {
                            item {
                                ListItem(
                                    selected = false,
                                    onClick = { viewModel.download(item, s.best, onDismissRequest) },
                                    headlineContent = { Text(stringResource(R.string.jellytip_best_match)) },
                                    supportingContent = { Text(s.best.name ?: s.best.providerName ?: "") },
                                    modifier = Modifier.focusRequester(first),
                                )
                            }
                        }
                        items(s.options) { option ->
                            SubtitleInfo(
                                subtitle = option,
                                onClick = { viewModel.download(item, it, onDismissRequest) },
                            )
                        }
                    }
                }
            }
        }
    }
}
