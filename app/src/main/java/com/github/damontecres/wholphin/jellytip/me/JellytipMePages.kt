package com.github.damontecres.wholphin.jellytip.me

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class JellytipMeViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val appPreferences: DataStore<AppPreferences>,
        val rows: JellytipRowsService,
    ) : ViewModel() {
        val message = mutableStateOf<String?>(null)

        fun patch(
            body: JSONObject,
            done: String? = null,
        ) {
            viewModelScope.launch {
                val (code, err) = JellytipMe.patch(body)
                message.value = if (code == 200) done else "Couldn't save (${err ?: code})."
            }
        }

        /** Intro / trailers live in this device's settings too: keep them in step with the person's prefs. */
        fun setPlaybackToggle(
            key: String,
            value: Boolean,
        ) {
            patch(JSONObject().put("playback", JSONObject().put(key, value)))
            viewModelScope.launch {
                appPreferences.updateData { current ->
                    current.update {
                        when (key) {
                            "intro" -> jellytipSplashDisabled = !value
                            "trailerAutoplay" -> jellytipDisableTrailerAutoplay = !value
                            "trailerSound" -> jellytipTrailerSound = value
                        }
                    }
                }
            }
        }

        /**
         * Audio / subtitle language: saved in the person's prefs and applied to Jellyfin's own track choice (the user's
         * audio/subtitle language preference), which is what picks tracks at playback.
         */
        fun setLanguage(
            audio: Boolean,
            code: String?,
        ) {
            val key = if (audio) "audioLangs" else "subtitleLangs"
            patch(JSONObject().put("playback", JSONObject().put(key, JSONArray(listOfNotNull(code)))), "Saved. New playback uses it.")
            viewModelScope.launch {
                try {
                    val user by api.userApi.getCurrentUser()
                    val cfg = user.configuration ?: return@launch
                    val updated =
                        if (audio) {
                            cfg.copy(audioLanguagePreference = code?.let(::iso3) ?: "")
                        } else {
                            cfg.copy(
                                subtitleLanguagePreference = code?.let(::iso3) ?: "",
                                subtitleMode = if (code == null) SubtitlePlaybackMode.NONE else SubtitlePlaybackMode.DEFAULT,
                            )
                        }
                    api.userApi.updateUserConfiguration(userId = user.id, data = updated)
                    serverRepository.updateUserDto()
                    JellytipTrace.log("me", "track prefs ${if (audio) "audio" else "subs"}=$code")
                } catch (e: Exception) {
                    JellytipTrace.log("me", "track prefs failed: ${e.message}")
                }
            }
        }

        fun saveLetterboxd(user: String) {
            patch(JSONObject().put("letterboxd", JSONObject().put("username", user.trim().ifBlank { JSONObject.NULL })), "Saved. Linking can take a few minutes.")
        }

        companion object {
            /** Jellyfin wants ISO 639-2 ("eng"). */
            fun iso3(code: String): String = runCatching { java.util.Locale(code).isO3Language }.getOrDefault(code)
        }
    }

private val LANGUAGES = listOf("en" to "English", "fr" to "French", "es" to "Spanish", "de" to "German", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "mi" to "Māori")

private fun langName(code: String?) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code ?: "Off"

/** "Personalise": your taste, Home edit, follows, and the settings that follow you across devices. */
@Composable
fun JellytipMePage(
    modifier: Modifier = Modifier,
    viewModel: JellytipMeViewModel = hiltViewModel(),
) {
    val available by JellytipMe.available.collectAsState()
    val prefs by JellytipMe.prefs.collectAsState()
    val message by viewModel.message
    var dialog by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        JellytipMe.loadFollows()
        first.tryRequestFocus()
    }
    val p = prefs
    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Personalise", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        Text(
            message ?: "These follow you on every device.",
            color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (!available || p == null) {
            Text("Personalisation isn't available on this server.", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Button(onClick = { viewModel.navigationManager.navigateTo(Destination.JellytipTaste, "personalise: taste") }, modifier = Modifier.focusRequester(first)) {
                        Text("Your taste")
                    }
                    Button(onClick = { viewModel.navigationManager.navigateTo(Destination.JellytipHomeEdit, "personalise: edit home") }) { Text("Edit Home") }
                }
            }
                item { SettingRow("Hide watched (rows update overnight)", if (p.hideWatched) "On" else "Off") { viewModel.patch(JSONObject().put("hideWatched", !p.hideWatched)) } }
                item { SettingRow("Intro", if (p.intro) "On" else "Off") { viewModel.setPlaybackToggle("intro", !p.intro) } }
                item { SettingRow("Trailers play by themselves", if (p.trailerAutoplay) "On" else "Off") { viewModel.setPlaybackToggle("trailerAutoplay", !p.trailerAutoplay) } }
                item { SettingRow("Trailer sound", if (p.trailerSound) "On" else "Off") { viewModel.setPlaybackToggle("trailerSound", !p.trailerSound) } }
                item { SettingRow("Audio language", langName(p.audioLangs.firstOrNull())) { dialog = "audio" } }
                item { SettingRow("Subtitles", langName(p.subtitleLangs.firstOrNull())) { dialog = "subs" } }
                item {
                    val lb = p.letterboxdUser?.let { "$it · ${p.letterboxdStatus}" } ?: "Not linked"
                    SettingRow("Your Letterboxd", lb) { dialog = "letterboxd" }
                }
            if (JellytipMe.follows.isNotEmpty()) {
                item { Text("Following", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                items(JellytipMe.follows.values.toList(), key = { it.personId.toString() }) { f ->
                    SettingRow(
                        f.name.ifBlank { "Person" },
                        "OK to open · hold OK to unfollow",
                        onLongClick = { JellytipMe.follow(f.personId, f.name, false) },
                    ) { viewModel.navigationManager.navigateTo(Destination.MediaItem(f.personId, BaseItemKind.PERSON), "personalise: followed person") }
                }
            }
        }
    }
    when (dialog) {
        "audio", "subs" -> {
            val audio = dialog == "audio"
            val choices = (if (audio) emptyList() else listOf<Pair<String?, String>>(null to "Off")) + LANGUAGES.map { it.first to it.second }
            DialogPopup(
                showDialog = true,
                title = if (audio) "Audio language" else "Subtitles",
                dialogItems = choices.map { (code, name) -> DialogItem(name, dismissOnClick = true) { viewModel.setLanguage(audio, code) } },
                onDismissRequest = { dialog = null },
                waitToLoad = false,
            )
        }

        "letterboxd" -> LetterboxdDialog(prefs?.letterboxdUser.orEmpty(), onSave = { viewModel.saveLetterboxd(it) }, onDismiss = { dialog = null })

        else -> {}
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(label, fontFamily = JellytipFontFamily, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LetterboxdDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    com.github.damontecres.wholphin.ui.components.BasicDialog(onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp).width(420.dp)) {
            Text("Your Letterboxd username", fontFamily = JellytipFontFamily, fontSize = 16.sp)
            EditTextBox(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    onSave(text)
                    onDismiss()
                }) { Text("Save") }
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

/** "Your taste": what the person watches, by genre, director, decade and country; their top rated. */
@Composable
fun JellytipTastePage(
    modifier: Modifier = Modifier,
    viewModel: JellytipMeViewModel = hiltViewModel(),
) {
    val taste by produceState<MeTaste?>(null) { value = JellytipMe.taste() }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8_000)
        if (taste == null) failed = true
    }
    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Your taste", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        val t = taste
        if (t == null) {
            Text(if (failed) "Not available right now." else "Loading…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            return@Column
        }
        Text(
            "${t.watchedCount} watched · ${t.hours.toInt()} hours",
            color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
            fontFamily = JellytipFontFamily,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    ShareBars("Genres", t.genres, Modifier.weight(1f))
                    ShareBars("Directors", t.directors, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    ShareBars("Decades", t.decades, Modifier.weight(1f))
                    ShareBars("Countries", t.countries, Modifier.weight(1f))
                }
            }
            if (t.topRated.isNotEmpty()) {
                item {
                    val row =
                        remember(t.topRated) {
                            JellytipServerRow(
                                id = "taste:top",
                                source = "me",
                                family = "me",
                                title = "Your top rated",
                                layout = "standard",
                                mediaType = "movie",
                                itemIds = t.topRated,
                                collection = null,
                            )
                        }
                    JellytipServerRowView(row = row, onFocusItem = { _, _ -> }, onLongClickItem = null, focusModifier = Modifier.focusGroup())
                }
            }
        }
    }
}

@Composable
private fun ShareBars(
    title: String,
    shares: List<MeShare>,
    modifier: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Text(title, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
        val max = shares.maxOfOrNull { it.share }?.takeIf { it > 0 } ?: 1.0
        shares.take(6).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.name, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
                Box(Modifier.weight(1f).height(8.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((s.share / max).toFloat().coerceIn(0.02f, 1f)).background(JellytipPalette.Jelly, RoundedCornerShape(4.dp)))
                }
                Text("${(s.share * 100).toInt()}%", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

/** Edit Home: today's rows with pin / hide / move; hidden rows can come back. Saved to the person's prefs. */
@Composable
fun JellytipHomeEditPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipMeViewModel = hiltViewModel(),
) {
    val prefs by JellytipMe.prefs.collectAsState()
    val p = prefs
    val answer = remember { viewModel.rows.peek("home") }
    val today = answer?.rows.orEmpty().distinctBy { it.id }
    // Working order: saved order first, then today's rows not in it
    val order =
        remember(p?.order, today) {
            mutableStateListOf<String>().apply {
                val ids = today.map { it.id }
                addAll(p?.order.orEmpty().filter { it in ids })
                addAll(ids.filter { it !in this })
            }
        }
    val titles = remember(today) { today.associate { it.id to it.title } }
    val scope = rememberCoroutineScope()
    val message by viewModel.message
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    fun save(
        pinned: List<String> = p?.pinned.orEmpty(),
        hidden: List<String> = p?.hidden.orEmpty(),
    ) {
        viewModel.patch(
            JSONObject().put(
                "home",
                JSONObject().put("pinned", JSONArray(pinned)).put("hidden", JSONArray(hidden)).put("order", JSONArray(order.toList())),
            ),
            "Saved. Home updates the next time it loads.",
        )
    }
    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Edit Home", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        Text(
            message ?: "Pin keeps a row every day; hide drops it; ↑ ↓ set the order.",
            color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (p == null) {
            Text("Personalisation isn't available on this server.", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(order, key = { _, id -> id }) { i, id ->
                val pinned = id in p.pinned
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                    Text(
                        (if (pinned) "📌 " else "") + (titles[id] ?: id),
                        color = JellytipPalette.Vanilla,
                        fontFamily = JellytipFontFamily,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton(if (pinned) "Unpin" else "Pin", if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        save(pinned = if (pinned) p.pinned - id else p.pinned + id)
                    }
                    SmallButton("Hide") {
                        order.remove(id)
                        save(hidden = p.hidden + id, pinned = p.pinned - id)
                    }
                    SmallButton("↑") {
                        if (i > 0) {
                            order.removeAt(i)
                            order.add(i - 1, id)
                            save()
                        }
                    }
                    SmallButton("↓") {
                        if (i < order.size - 1) {
                            order.removeAt(i)
                            order.add(i + 1, id)
                            save()
                        }
                    }
                }
            }
            if (p.hidden.isNotEmpty()) {
                item { Text("Hidden", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                items(p.hidden, key = { "h:$it" }) { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(titles[id] ?: id, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        SmallButton("Show again") { save(hidden = p.hidden - id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
