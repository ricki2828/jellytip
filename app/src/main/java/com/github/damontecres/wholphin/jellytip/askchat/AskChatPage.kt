package com.github.damontecres.wholphin.jellytip.askchat

import android.content.Context
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.JellytipSkeleton
import com.github.damontecres.wholphin.jellytip.cards.JellytipLibraryMenuHostOf
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleSources
import com.github.damontecres.wholphin.jellytip.cards.rememberJellytipLibraryMenu
import com.github.damontecres.wholphin.jellytip.ask.AskButton
import com.github.damontecres.wholphin.jellytip.ask.AskChipButton
import com.github.damontecres.wholphin.jellytip.ask.AskParse
import com.github.damontecres.wholphin.jellytip.ask.askItem
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import javax.inject.Inject

/*
 * Chat search (web #/ask/chat), TV: "here with my kids, something funny, not scary" -> the server turns each turn into chips and picks the
 * titles from your library (and TV for a full account). The state machine is AskChatState.kt (a port of the web's, tested); this file
 * renders it. While a turn is pending the cards are hidden (skeleton), the chips are the dimmed local reading and EVERY control is
 * disabled (focus parks on the input). Entry (23-Nov, the Ask page is gone): hold Search (the sidebar item or the remote's key) opens
 * this page already listening; "Chat" on Search opens it quiet.
 */

/** What the page shows: the state machine's state, the typed text, the notice, and the library items fetched for the cards. */
data class AskChatUi(
    val chat: ChatState = ChatState(),
    val text: String = "",
    val notice: Boolean = true,
    /** Library items for the current cards (null while they are being fetched). TV cards need no fetch. */
    val fetched: Fetched? = null,
    /** Press-to-talk: listening / level / last failure. */
    val voice: ChatVoiceUi = ChatVoiceUi(),
)

data class Fetched(
    /** The library ids joined: which cards these items are for. */
    val key: String,
    val items: List<BaseItem>,
    val people: List<BaseItem>,
)

@HiltViewModel
class JellytipAskChatViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val jellytipApi: JellytipApi,
        @ApplicationContext private val context: Context,
        val voiceInputManager: VoiceInputManager,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val prefs by lazy { context.getSharedPreferences("jellytip_askchat", Context.MODE_PRIVATE) }
        private val noticeStore =
            object : NoticeStore {
                override fun get(): Boolean? = if (prefs.contains(NOTICE_KEY)) prefs.getBoolean(NOTICE_KEY, false) else null

                override fun set(v: Boolean) {
                    prefs.edit().putBoolean(NOTICE_KEY, v).apply()
                }
            }
        private val _ui = MutableStateFlow(AskChatUi(notice = !AskChat.noticeSeen(noticeStore)))
        val ui: StateFlow<AskChatUi> = _ui
        private val sender = ChatSender(ChatPost { path, body, timeoutMs -> jellytipApi.postJson(path, body, timeoutMs) })
        private var fetchJob: Job? = null
        val chatVoice = ChatVoice(context, onText = { t -> _ui.update { it.copy(text = t) } }, onFinal = { t -> submit(t) })

        init {
            sender.warm(viewModelScope) // the chat service warms up while the screen is open (fire and forget)
            viewModelScope.launch { chatVoice.ui.collect { v -> _ui.update { it.copy(voice = v) } } }
        }

        fun setText(v: String) {
            chatVoice.clearError()
            _ui.update { it.copy(text = v) }
        }

        /** Press-to-talk: OK down on the mic (not while a turn is pending). */
        fun micDown() {
            if (_ui.value.chat.pending != null) return
            chatVoice.press(_ui.value.text)
        }

        fun micUp() = chatVoice.release()

        fun micTap() {
            if (_ui.value.chat.pending != null) return
            chatVoice.tapOnce(_ui.value.text)
        }

        /** Start over: drop everything (also what is in flight); no server call. Works while a turn is pending. */
        fun startOver() {
            JellytipTrace.log("askchat", "start over")
            chatVoice.cancel()
            sender.cancelAll()
            fetchJob?.cancel()
            lastOpened = null
            _ui.update { it.copy(chat = AskChat.startOver(it.chat), text = "", fetched = null) }
        }

        fun submit(v: String) {
            JellytipTrace.log("askchat", "submit '${v.trim()}'")
            val r = AskChat.submitText(_ui.value.chat, v) ?: return // empty, or a turn is already pending
            _ui.update { it.copy(text = "", chat = r.state) }
            send(r.body)
        }

        fun tap(op: ChatOp) {
            JellytipTrace.log("askchat", "tap $op")
            val r = AskChat.tapChip(_ui.value.chat, op) ?: return
            _ui.update { it.copy(chat = r.state) }
            send(r.body)
        }

        fun retry() {
            val r = AskChat.retry(_ui.value.chat) ?: return
            _ui.update { it.copy(chat = r.state) }
            send(r.body)
        }

        fun micPermissionDenied() = chatVoice.denied()

        fun dismissNotice() {
            AskChat.dismissNotice(noticeStore)
            _ui.update { it.copy(notice = false) }
        }

        private fun send(body: ChatBody) {
            viewModelScope.launch {
                when (val out = sender.send(body)) {
                    Sent.Aborted -> {}

                    is Sent.Ok -> {
                        if (_ui.value.chat.pending == null || out.resp.rev != _ui.value.chat.rev) return@launch // started over meanwhile
                        // the IPTV copies of the library titles shown: the library hold-OK menu offers "Play from IPTV" for them
                        out.resp.cards.filterIsInstance<ChatTitle.Library>().forEach { c -> JellytipTitleSources.put(c.id, c.sources) }
                        _ui.update { it.copy(chat = AskChat.applyResponse(it.chat, out.resp), fetched = null) }
                        fetchFor(_ui.value.chat)
                    }

                    is Sent.Error -> {
                        val e = AskChat.applyError(_ui.value.chat, body.rev, out.status)
                        _ui.update { it.copy(chat = e.state) }
                        e.resend?.let { send(it) } // 400 / 413: once more with the oldest turns trimmed
                    }
                }
            }
        }

        /** The library titles of the cards, fetched from Jellyfin by id; TV cards need nothing. An id the fetch drops is a gap. */
        private fun fetchFor(chat: ChatState) {
            fetchJob?.cancel()
            val ids = AskChat.libraryIds(chat.cards)
            val key = ids.joinToString(",")
            if (ids.isEmpty()) {
                _ui.update { it.copy(fetched = Fetched("", emptyList(), emptyList())) }
                return
            }
            val user = serverRepository.currentUser?.id
            fetchJob =
                viewModelScope.launch {
                    val uuids = ids.mapNotNull { runCatching { it.toUUID() }.getOrNull() }
                    val items =
                        try {
                            api.itemsApi
                                .getItems(
                                    GetItemsRequest(
                                        userId = user,
                                        ids = uuids,
                                        fields = listOf(ItemFields.GENRES, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                                        enableUserData = true,
                                        limit = uuids.size,
                                    ),
                                ).content.items
                                .map { BaseItem(it, false) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Chat library fetch failed")
                            emptyList()
                        }
                    // A different person signed in while this was out: never show them another's picks
                    if (serverRepository.currentUser?.id != user) return@launch
                    _ui.update { if (AskChat.libraryIds(it.chat.cards).joinToString(",") == key) it.copy(fetched = Fetched(key, items, emptyList())) else it }
                    if (items.size < 2) return@launch
                    val people =
                        try {
                            api.itemsApi
                                .getItems(GetItemsRequest(userId = user, ids = items.map { it.id }, fields = listOf(ItemFields.PEOPLE), limit = items.size))
                                .content.items
                                .map { BaseItem(it, false) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Chat people failed") // no people = no people chips
                            return@launch
                        }
                    _ui.update { u ->
                        val f = u.fetched
                        if (f != null && f.key == key) u.copy(fetched = f.copy(people = people)) else u
                    }
                }
        }

        /** The title last opened: Back from its page lands on it again (the grid is rebuilt on return). */
        var lastOpened: String? = null
            private set

        fun openLibrary(item: BaseItem) {
            lastOpened = AskChat.normId(item.id.toString())
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(item.destination(), "chat pick OK")
        }

        fun openTv(card: ChatTitle.Iptv) {
            lastOpened = "iptv:${card.iptvId}"
            // A TV film that is also in the library opens the library page (one page per title); otherwise its own IPTV page
            navigationManager.navigateTo(JellytipTitleOpen.detailsFor(card.toVodItem()), "chat TV pick OK")
        }

        /** Chat is switched off on the server (404): tell the person and go back to where they came from. */
        fun chatOff() {
            viewModelScope.launch { showToast(context, "Chat isn't available right now") }
            navigationManager.goBack()
        }

        override fun onCleared() {
            chatVoice.cancel()
            sender.cancelAll()
        }

        private companion object {
            const val NOTICE_KEY = "notice_seen"
        }
    }

/** What the page calls (the view model's methods; no-ops in the screenshot harness). */
internal class AskChatActions(
    val setText: (String) -> Unit,
    val submit: (String) -> Unit,
    val tap: (ChatOp) -> Unit,
    val retry: () -> Unit,
    val dismissNotice: () -> Unit,
    val openLibrary: (BaseItem) -> Unit,
    val openTv: (ChatTitle.Iptv) -> Unit,
    val voice: VoiceInputManager?,
    val lastOpened: () -> String?,
    val startOver: () -> Unit = {},
    val micAvailable: Boolean = false,
    val micDown: () -> Unit = {},
    val micUp: () -> Unit = {},
    val micTap: () -> Unit = {},
)

@Composable
fun JellytipAskChatPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipAskChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    // A Search hold asked to listen: once mic permission comes back granted, listen (the hold was the ask)
    var listenAfterGrant by remember { mutableStateOf(false) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.micPermissionDenied()
            } else if (listenAfterGrant) {
                viewModel.micTap()
            }
            listenAfterGrant = false
        }
    // Hold Search (sidebar item or remote key) = Chat, listening: on open, and again on every hold while the page is up
    val listenToken by com.github.damontecres.wholphin.jellytip.search.JellytipSearchButton.listen.collectAsState()
    LaunchedEffect(listenToken) {
        if (!com.github.damontecres.wholphin.jellytip.search.JellytipSearchButton.takeListen(listenToken)) return@LaunchedEffect
        when {
            !viewModel.chatVoice.isAvailable -> JellytipTrace.log("askchat", "hold: no recognizer, not listening")
            viewModel.chatVoice.hasPermission -> {
                JellytipTrace.log("askchat", "hold: listening")
                viewModel.micTap()
            }
            else -> {
                listenAfterGrant = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    if (ui.chat.error == ChatError.Off) {
        // The server has chat switched off (404): back to Ask with a short notice (no destinations stacking up on repeated taps)
        LaunchedEffect(Unit) { viewModel.chatOff() }
    } else {
        val actions =
            remember(viewModel, micPermission) {
                AskChatActions(
                    setText = viewModel::setText,
                    submit = viewModel::submit,
                    tap = viewModel::tap,
                    retry = viewModel::retry,
                    dismissNotice = viewModel::dismissNotice,
                    openLibrary = viewModel::openLibrary,
                    openTv = viewModel::openTv,
                    voice = viewModel.voiceInputManager,
                    lastOpened = { viewModel.lastOpened },
                    startOver = viewModel::startOver,
                    micAvailable = viewModel.chatVoice.isAvailable,
                    micDown = { if (viewModel.chatVoice.hasPermission) viewModel.micDown() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    micUp = viewModel::micUp,
                    micTap = { if (viewModel.chatVoice.hasPermission) viewModel.micTap() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        AskChatContent(ui = ui, actions = actions, modifier = modifier)
    }
}

private val dim = JellytipPalette.Vanilla.copy(alpha = JellytipLayout.TEXT_ALPHA)

/** The one-line hint under Chat's title (and the shots' assert). */
internal const val CHAT_HOLD_HINT = "Hold Search to talk anytime"
private val CHAT_W = 326.dp

/** A control that cannot be used while a turn is pending: dimmed and not focusable (focus parks on the input meanwhile). */
private fun Modifier.chatDisabled(off: Boolean): Modifier = if (off) alpha(0.45f).focusProperties { canFocus = false } else this

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskChatContent(
    ui: AskChatUi,
    actions: AskChatActions,
    modifier: Modifier = Modifier,
) {
    val chat = ui.chat
    val pending = chat.pending != null
    val keyboard = LocalSoftwareKeyboardController.current
    // the library tile's hold-OK menu (page-level state, never inside a lazy item); the host is composed once, below
    val menu = rememberJellytipLibraryMenu()
    val boxFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    var typing by remember { mutableStateOf(false) }
    var micFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val turnsScroll = rememberScrollState()
    val libKey = AskChat.libraryIds(chat.cards).joinToString(",")
    val ready = ui.fetched?.key == libKey
    val fetched = ui.fetched
    val shown = remember(ready, fetched, chat.cards) { if (ready && fetched != null) AskChat.alignCards(chat.cards, fetched.items) { it.id.toString() }.distinctBy { AskChat.cardKey(it.card) } else emptyList() }
    val chips = AskChat.displayChips(chat)
    val refine =
        remember(ready, pending, fetched, chat.chips) {
            if (ready && !pending && fetched != null && fetched.items.isNotEmpty()) AskChat.refineChat(fetched.items, fetched.people, chat.chips) { it.data.askItem() } else emptyList()
        }
    val fresh = chat.turns.isEmpty() && !pending && chat.chips.isEmpty() && chat.error == null

    // First composition (also on Back from a title's page): the title that was opened, else the input. Page-level, never inside a lazy item.
    LaunchedEffect(Unit) {
        val last = actions.lastOpened()
        val at = if (last == null) -1 else shown.indexOfFirst { AskChat.cardKey(it.card).let { k -> k == last || AskChat.normId(k) == last } }
        if (at >= 0) {
            gridState.scrollToItem(at)
            withFrameNanos { }
            if (restore.tryRequestFocus()) return@LaunchedEffect
        }
        boxFocus.tryRequestFocus()
    }
    // While a turn is pending every control is disabled: focus parks on the input so it can't fall to the drawer
    LaunchedEffect(pending) {
        if (pending) boxFocus.tryRequestFocus()
    }
    LaunchedEffect(chat.turns.size, pending) {
        withFrameNanos { } // let the new line be measured, then scroll to the NEW bottom
        turnsScroll.animateScrollTo(turnsScroll.maxValue)
    }

    Row(
        modifier =
            modifier
                .fillMaxSize()
                .background(JellytipPalette.Night)
                .padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd, bottom = JellytipLayout.PageBottom)
                .onPreviewKeyEvent { e ->
                    // The remote's Search / mic key (a short press; a hold is JellytipSearchButton's): listen with the chat's own
                    // recognizer, which keeps going until the speaker stops (not while a turn is pending)
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.Search || e.key == Key.VoiceAssist) && actions.micAvailable && !pending) {
                        actions.micTap()
                        true
                    } else {
                        false
                    }
                },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        JellytipLibraryMenuHostOf(menu) // the library tile's hold-OK menu, once on the page (composes nothing until a tile asks)
        // ---- the conversation ----
        Column(Modifier.width(CHAT_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap)) {
            Text("Chat", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = JellytipLayout.PageTitle)
            Text(CHAT_HOLD_HINT, color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.MinText, maxLines = 1, modifier = Modifier.testTag("chat-hold-hint"))
            Column(Modifier.weight(1f).verticalScroll(turnsScroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fresh) Text("Talk to it like you would a friend.", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body, lineHeight = 17.sp)
                chat.turns.forEachIndexed { i, t ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t.user, color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body, lineHeight = 17.sp)
                        Text(t.reply, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = JellytipLayout.Body, lineHeight = 17.sp)
                        if (i == chat.turns.lastIndex && chat.mode == "fallback" && !pending) {
                            Text("basic mode", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.MinText)
                        }
                    }
                }
                val pt = chat.pending?.body
                if (pt != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        pt.text?.let { Text(it, color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body, lineHeight = 17.sp) }
                        Text(if (pt.text != null) "Reading…" else "Updating…", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body)
                    }
                }
            }
            // input, then Send / Start over / Mic (that is the focus order)
            SearchEditTextBox(
                value = ui.text,
                onValueChange = {
                    typing = true
                    actions.setText(it)
                },
                onSearchClick = { actions.submit(ui.text) },
                readOnly = !typing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("chat-input")
                        .focusRequester(boxFocus)
                        .onFocusChanged { if (!it.isFocused) typing = false }
                        .onPreviewKeyEvent { event ->
                            val ok = event.key == Key.DirectionCenter || event.key == Key.Enter
                            if (event.type == KeyEventType.KeyUp && ok && !typing) {
                                typing = true
                                keyboard?.show()
                                true
                            } else {
                                false
                            }
                        },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AskButton("Send", primary = true, modifier = Modifier.chatDisabled(pending || ui.text.isBlank())) { actions.submit(ui.text) }
                // Start over: the one control that stays live while a turn is pending (it drops the turn in flight)
                AskButton(
                    "Start over",
                    primary = false,
                    modifier = Modifier.testTag("chat-start-over"),
                ) {
                    actions.startOver()
                    boxFocus.tryRequestFocus()
                }
                if (actions.micAvailable) {
                    ChatMicButton(ui.voice.listening, actions, Modifier.chatDisabled(pending), onFocus = { micFocused = it })
                }
            }
            val hint =
                when {
                    ui.voice.listening && ui.voice.tap -> "Listening… just talk, it sends when you stop"
                    ui.voice.listening -> "Listening… release to send"
                    ui.voice.errorRes != null -> stringResource(ui.voice.errorRes)
                    actions.micAvailable && !pending -> "Hold OK on the mic to talk"
                    else -> null
                }
            if (hint != null) {
                Text(hint, color = if (ui.voice.listening) JellytipPalette.Vanilla else dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.MinText, modifier = Modifier.testTag("chat-voice-hint"))
            }
            if (ui.voice.listening) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(JellytipPalette.Vanilla.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(ui.voice.level.coerceAtLeast(0.06f)).background(JellytipPalette.Jelly))
                }
            }
            if (ui.notice) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        CHAT_NOTICE_TEXT,
                        color = dim,
                        fontFamily = JellytipFontFamily,
                        fontSize = JellytipLayout.MinText,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    AskChipButton("Got it", on = false, modifier = Modifier.chatDisabled(pending)) { actions.dismissNotice() }
                }
            }
        }

        // ---- the reading and the results ----
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap)) {
            if (chips.isNotEmpty()) {
                // the chips wrap onto further lines inside the pane (a one-line LazyRow ran past the right edge and hid the later chips)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(JellytipLayout.FocusRoom).focusGroup().focusRestorer(),
                ) {
                    chips.distinctBy { it.chip.id }.forEach { s ->
                        key(s.chip.id) {
                            val c = s.chip
                            if (s.provisional || !c.removable) {
                                // the local reading while pending: dimmed, not focusable ("reading…")
                                AskChipButton(c.label, on = true, modifier = Modifier.alpha(0.55f).focusProperties { canFocus = false }) {}
                            } else {
                                AskChipButton(
                                    "${c.label}${if (c.applied == false) " (not applied)" else ""}  \u2715",
                                    on = c.applied != false,
                                    modifier = Modifier.testTag("chat-chip:${c.id}").chatDisabled(pending),
                                ) { actions.tap(ChatOp.Remove(c.id)) }
                            }
                        }
                    }
                }
            }
            if (chat.error == ChatError.Unreachable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't reach chat.", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body)
                    AskButton("Retry", primary = false, modifier = Modifier.chatDisabled(pending)) { actions.retry() }
                }
            }
            if (chat.error == ChatError.TooLong) Text(CHAT_TOO_LONG_TEXT, color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body)
            if (chat.error == ChatError.SignedOut) Text("You're signed out. Sign in again to use chat.", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body)
            if (!pending) {
                chat.notes.forEach { n ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(n.text, color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.Body, lineHeight = 17.sp)
                        n.actions.forEach { a -> AskButton(a.label, primary = false) { actions.tap(a.op) } }
                    }
                }
                val hint = AskChat.scopeHint(chat.sources)
                if (hint.isNotEmpty() && chat.turns.isNotEmpty()) {
                    Text("$hint${if (chat.matched > 0) " · ${chat.matched} fit" else ""}", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.MinText)
                }
            }
            if (fresh) {
                Text("Try", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AskParse.EXAMPLES.forEach { q -> AskChipButton(q, on = false) { actions.submit(q) } }
                }
            }
            if (pending || (libKey.isNotEmpty() && !ready)) {
                JellytipSkeleton(tileW = SpecialTokens.POSTER_W, tileH = SpecialTokens.POSTER_H, rows = 2, perRow = 4, title = false, modifier = Modifier.padding(JellytipLayout.FocusRoom))
            } else if (shown.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.FixedSize(SpecialTokens.POSTER_W),
                    horizontalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap),
                    verticalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap),
                    contentPadding = PaddingValues(JellytipLayout.FocusRoom),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f).focusGroup().focusRestorer(),
                ) {
                    items(shown.size, key = { AskChat.cardKey(shown[it].card) }) { i ->
                        val p = shown[i]
                        val k = AskChat.cardKey(p.card)
                        ChatCard(
                            placed = p,
                            onOpenLibrary = actions.openLibrary,
                            onOpenTv = actions.openTv,
                            onLongClickLibrary = { menu.open(it) },
                            modifier = Modifier.testTag("chat-card").then(if (actions.lastOpened().let { l -> l != null && (l == k || l == AskChat.normId(k)) }) Modifier.focusRequester(restore) else Modifier),
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (refine.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Narrow it down", color = dim, fontFamily = JellytipFontFamily, fontSize = JellytipLayout.MinText)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(JellytipLayout.FocusRoom),
                        modifier = Modifier.focusGroup().focusRestorer(),
                    ) {
                        items(refine, key = { it.id }) { c ->
                            AskChipButton("+ ${c.label}", on = false, modifier = Modifier.chatDisabled(pending)) { actions.tap(ChatOp.Add(c.data)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The press-to-talk mic: hold OK (DPAD_CENTER / ENTER down, repeats ignored) to listen, release to send. Key events are handled in the
 * preview pass and consumed, so the Surface's own click only ever comes from a pointer (a tap).
 */
@Composable
private fun ChatMicButton(
    listening: Boolean,
    actions: AskChatActions,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit,
) {
    val desc = "Hold OK to talk"
    Surface(
        onClick = { actions.micTap() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (listening) JellytipPalette.Jelly else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = if (listening) JellytipPalette.Jelly else com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = if (listening) Color.White else JellytipPalette.Vanilla,
                focusedContentColor = if (listening) Color.White else com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier =
            modifier
                .testTag("chat-mic")
                .semantics { contentDescription = desc }
                .onFocusChanged {
                    onFocus(it.isFocused)
                    if (!it.isFocused) actions.micUp() // focus left while held: never leave the mic open
                }.onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (!ok) {
                        false
                    } else {
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                if (e.nativeKeyEvent.repeatCount == 0) actions.micDown() // auto-repeats while held are swallowed
                                true
                            }

                            KeyEventType.KeyUp -> {
                                actions.micUp()
                                true
                            }

                            else -> false
                        }
                    }
                },
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.fa_microphone), fontFamily = FontAwesome, fontSize = 14.sp)
        }
    }
}
