package com.github.damontecres.wholphin.jellytip.search

import android.os.SystemClock
import android.view.KeyEvent
import com.github.damontecres.wholphin.jellytip.JellytipInputGuard
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One button for Search and Chat (23-Nov, replaces the separate "Ask" page): press = Search, hold = Chat with the mic already
 * listening. Two places feed it:
 * - the sidebar's Search item (OK press / hold), which is the path on Ricki's Chromecast: its remote has no Search key that reaches
 *   the app (no KEYCODE_SEARCH / VOICE_ASSIST in any saved trace; the Assistant button is taken by the system);
 * - the remote's Search key, if a remote sends one ([onRemoteKey], from MainActivity).
 * The press/hold decision is [SearchPressHold] (pure, tested). Main thread only.
 */
object JellytipSearchButton {
    /** A hold asked Chat to start listening: the uptime it asked at, 0 = nothing asked. The chat page takes it once ([takeListen]). */
    private val _listen = MutableStateFlow(0L)
    val listen: StateFlow<Long> = _listen.asStateFlow()

    /** A listen request older than this is stale (the navigation never happened): the next time Chat opens it stays quiet. */
    private const val LISTEN_FRESH_MS = 5_000L

    private val remote = SearchPressHold()

    /**
     * The open Search / Chat page's own "listen now" (registered while it is on screen): a short Search-key press there calls it.
     * The key itself is consumed here, because Compose drops a key-up whose key-down it never saw.
     */
    var pageVoice: (() -> Unit)? = null

    /** The chat page: true once per hold, if [token] is the current, fresh request. */
    fun takeListen(token: Long): Boolean {
        if (token == 0L || _listen.value != token) return false
        _listen.value = 0L
        return SystemClock.uptimeMillis() - token < LISTEN_FRESH_MS
    }

    /** Short press: Search. From the sidebar the stack is reset to Home first (every sidebar item does that). */
    fun short(
        nav: NavigationManager,
        source: String,
        fromDrawer: Boolean,
    ) {
        JellytipTrace.log("nav", "search short via=$source")
        if (fromDrawer) nav.navigateToFromDrawer(Destination.Search()) else nav.navigateTo(Destination.Search(), "search key")
    }

    /**
     * Hold: Chat, listening. Already on Chat: just listen. Otherwise open it now, while OK is still held, so the chat page can listen
     * push-to-talk until the key-up; the rest of the press (repeats, key-up) is swallowed (JellytipInputGuard), so it never lands on
     * the chat input and opens the keyboard. OK not held (a hold seen only at key-up, the Search key): open now, the page taps.
     */
    fun hold(
        nav: NavigationManager,
        source: String,
        fromDrawer: Boolean,
    ) {
        JellytipTrace.log("nav", "search hold -> one box listening via=$source")
        _listen.value = SystemClock.uptimeMillis().coerceAtLeast(1L)
        JellytipInputGuard.swallowRestOfPress()
        if (nav.backStack.lastOrNull().isChat()) return
        // One box (Ricki 23-Nov): Search and Chat are one destination; the page listens (push-to-talk) and routes the words
        if (fromDrawer) nav.navigateToFromDrawer(Destination.Search()) else nav.navigateTo(Destination.Search(), "search hold")
    }

    /**
     * The remote's Search key (MainActivity, every key event). True = consumed. On the Search and Chat pages a short press listens
     * there ([pageVoice]). Players are left alone (the key did nothing there before).
     */
    fun onRemoteKey(
        event: KeyEvent,
        nav: NavigationManager,
    ): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_SEARCH) return false
        if (nav.backStack.lastOrNull().isPlayer()) {
            remote.reset()
            return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (remote.down(event.eventTime, event.repeatCount) == SearchPressHold.Press.LONG) hold(nav, "remote", fromDrawer = false)
                remote.inPress
            }

            KeyEvent.ACTION_UP -> {
                val ours = remote.inPress
                when (remote.up(event.eventTime)) {
                    SearchPressHold.Press.SHORT -> {
                        val top = nav.backStack.lastOrNull()
                        val voice = pageVoice
                        if ((top is Destination.Search || top.isChat()) && voice != null) {
                            JellytipTrace.log("nav", "search short via=remote (page listens)")
                            voice()
                        } else if (top !is Destination.Search) {
                            short(nav, "remote", fromDrawer = false)
                        }
                    }

                    SearchPressHold.Press.LONG -> hold(nav, "remote", fromDrawer = false)

                    SearchPressHold.Press.NONE -> Unit
                }
                ours
            }

            else -> false
        }
    }

    /** The one box (Search) or an old Chat / Ask entry, which shows the same page. */
    private fun Destination?.isChat() = this is Destination.Search || this is Destination.JellytipAskChat || this is Destination.JellytipAsk

    private fun Destination?.isPlayer() =
        this is Destination.Playback || this is Destination.PlaybackList || this is Destination.JellytipLiveTvPlayer || this is Destination.JellytipIptvPlay
}
