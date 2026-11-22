package com.github.damontecres.wholphin.jellytip.askchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Chat search transport (web ChatSender): one in-flight turn at a time. Sending a newer request supersedes the previous one: its
 * caller gets [Sent.Aborted] and must ignore it. A request that does not answer within the timeout (12 s) ends as Error(0)
 * (unreachable + Retry), so a hung connection can never leave the screen waiting: every send resolves to Ok, Error or Aborted
 * (Aborted only when superseded). A 200 for another rev than the body that was sent is an Error too (never a silent drop that
 * would leave the turn pending). Nothing here touches Android: the HTTP call is injected ([ChatPost]).
 */

const val CHAT_SEND_TIMEOUT_MS = 12_000L
const val CHAT_WARM_TIMEOUT_MS = 5_000L

/** POST [body] to a companion path with a call timeout -> (http code, body), or null when unreachable (JellytipApi.postJson). */
fun interface ChatPost {
    suspend fun post(
        path: String,
        body: String,
        timeoutMs: Long,
    ): Pair<Int, String>?
}

sealed interface Sent {
    data class Ok(
        val resp: ChatResponse,
    ) : Sent

    data class Error(
        val status: Int,
    ) : Sent

    /** Superseded by a newer request: ignore. */
    data object Aborted : Sent
}

class ChatSender(
    private val poster: ChatPost,
    private val timeoutMs: Long = CHAT_SEND_TIMEOUT_MS,
) {
    private val lock = Any()
    private var current: Flight? = null

    private class Flight {
        @Volatile
        var superseded = false
    }

    /** Send one turn. A previous in-flight send (if any) is superseded: its caller gets [Sent.Aborted]. */
    suspend fun send(body: ChatBody): Sent {
        val me = Flight()
        synchronized(lock) {
            current?.superseded = true
            current = me
        }
        val out: Sent =
            try {
                // the call carries its own call timeout; this outer one is the guarantee that a send always ends
                val r = withTimeoutOrNull(timeoutMs + OUTER_SLACK_MS) { poster.post("/v1/ask/chat", body.toJson(), timeoutMs) }
                if (r == null) {
                    Sent.Error(0) // unreachable, or past the deadline
                } else if (r.first == 200) {
                    val resp = AskChat.parseResponse(r.second)
                    if (resp != null && resp.rev == body.rev) Sent.Ok(resp) else Sent.Error(500)
                } else {
                    Sent.Error(r.first)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sent.Error(0)
            } finally {
                synchronized(lock) { if (current === me) current = null }
            }
        return if (me.superseded) Sent.Aborted else out
    }

    /** Leaving the screen: whatever is in flight is no longer wanted. */
    fun cancelAll() {
        synchronized(lock) {
            current?.superseded = true
            current = null
        }
    }

    /** Fire and forget: tells the server a chat is starting so it can warm up. Never throws. */
    fun warm(scope: CoroutineScope) {
        scope.launch {
            try {
                withTimeoutOrNull(CHAT_WARM_TIMEOUT_MS + OUTER_SLACK_MS) { poster.post("/v1/ask/chat/warm", "{}", CHAT_WARM_TIMEOUT_MS) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a warm-up is a nicety
            }
        }
    }

    private companion object {
        const val OUTER_SLACK_MS = 500L
    }
}
