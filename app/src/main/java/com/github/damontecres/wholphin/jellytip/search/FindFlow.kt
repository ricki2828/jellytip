package com.github.damontecres.wholphin.jellytip.search

import org.json.JSONObject

/*
 * One box (Ricki 23-Nov; plan ~/ai/plans/jellytip-one-box.md, contract ~/ai/state/fleet/jellytip-find-v1-spec.md): Search and Chat are
 * one destination. Typing still searches as you go (/v1/search); a SUBMIT (OK / Enter / a final voice result) asks the server
 * (/v1/find) whether the words are a search, a chat ask, or both. The server decides without an LLM; the chat answer itself comes from
 * /v1/ask/chat, which this page sends right after a chat / both decision. Follow-ups in the same window continue the chat; Start over
 * clears it. Pure logic here (unit tested); JellytipFindPage renders it.
 */

enum class FindMode { SEARCH, CHAT, BOTH }

data class FindDecision(
    val id: String,
    val mode: FindMode,
    val p: Double,
    val why: String,
    /** The /v1/search body (null for CHAT, or when the server's search failed). */
    val search: SearchAnswer?,
    /** The text to send to /v1/ask/chat (null for SEARCH). */
    val askText: String?,
)

object FindParse {
    /** A /v1/find body -> the decision, or null when it isn't one (unknown mode, no id). */
    fun decision(
        json: String,
        searchParse: (String) -> SearchAnswer?,
    ): FindDecision? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val mode =
            when (o.optString("mode")) {
                "search" -> FindMode.SEARCH
                "chat" -> FindMode.CHAT
                "both" -> FindMode.BOTH
                else -> return null
            }
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val search = o.optJSONObject("search")?.let { searchParse(it.toString()) }
        val ask = o.optJSONObject("ask")?.optString("text")?.takeIf { it.isNotBlank() }
        return FindDecision(id, mode, o.optDouble("p", 0.0), o.optString("why"), search, if (mode == FindMode.SEARCH) null else ask)
    }
}

/** Feedback the page owes the server for a decision (once per decision id). */
enum class FindSwitch(
    val wire: String,
) {
    ASK_CHIP("ask_chip"),
    RETYPED("retyped"),
    SEARCH_CHIP("search_chip"),
    PLAYED("played"),
}

/**
 * What the box is showing. [view] = which body is on screen: plain search (as you type), the chat, or search with the chat
 * answer below ([FindMode.BOTH]). [deciding] = a submit is out at /v1/find.
 */
data class FindUi(
    val view: FindMode = FindMode.SEARCH,
    val deciding: Boolean = false,
    /** The decision on screen, for feedback. */
    val decisionId: String? = null,
    val decidedQ: String? = null,
    val decidedAtMs: Long = 0,
    val feedbackSent: Boolean = false,
    /** The submit counter: an answer for an older submit is dropped. */
    val seq: Int = 0,
    /** The chat turn for this decision went out (BOTH waits, see [FindFlow.BOTH_CHAT_DELAY_MS]). */
    val chatSent: Boolean = false,
    /** A result was opened for this decision: BOTH's deferred chat turn is then never spent. */
    val picked: Boolean = false,
    /** The text the server said to send to the chat (`ask.text`), for BOTH's deferred turn. */
    val askText: String? = null,
)

/** What a step asks the page to do besides showing [FindUi]. */
data class FindEffects(
    /** Call /v1/find for this text (seq = [FindUi.seq]). */
    val find: String? = null,
    /** Send this text to the chat (a first turn after a decision, or a follow-up). */
    val chat: String? = null,
    /** Clear the chat (Start over, or a new search that isn't a follow-up). */
    val resetChat: Boolean = false,
    /** Plain search for this text (what a 404 / failed find falls back to). */
    val search: String? = null,
    /** Feedback for a decision id. */
    val feedback: Pair<String, FindSwitch>? = null,
    /** BOTH: call [FindFlow.bothTimer] after this many ms (the chat turn waits for a pick first). */
    val chatAfterMs: Long? = null,
)

object FindFlow {
    const val RETYPE_WINDOW_MS = 60_000L

    /**
     * BOTH (t3x 23-Nov): the chat turn costs an LLM call, so it is only spent when the person doesn't pick a search hit within this
     * long, or moves to the chat chip first.
     */
    const val BOTH_CHAT_DELAY_MS = 3_000L

    /**
     * A submit from the box. In the chat (view CHAT, or BOTH once the person moved into its answer) it is a follow-up and goes
     * straight to the chat. Otherwise it is a new question for /v1/find; a different question within [RETYPE_WINDOW_MS] of the last
     * decision tells the server that decision was "retyped".
     */
    fun submit(
        s: FindUi,
        q: String,
        nowMs: Long,
        inChat: Boolean = s.view == FindMode.CHAT,
    ): Pair<FindUi, FindEffects> {
        val t = q.trim()
        if (t.isEmpty()) return s to FindEffects()
        if (inChat) return s to FindEffects(chat = t)
        val retyped =
            s.decisionId != null && !s.feedbackSent && s.decidedQ != null && !s.decidedQ.equals(t, ignoreCase = true) &&
                nowMs - s.decidedAtMs in 0..RETYPE_WINDOW_MS
        val next = s.copy(deciding = true, seq = s.seq + 1, feedbackSent = s.feedbackSent || retyped)
        return next to FindEffects(find = t, resetChat = true, feedback = if (retyped) s.decisionId!! to FindSwitch.RETYPED else null)
    }

    /** The /v1/find answer for submit [seq]: null = failed or no route (the page searches plainly). */
    fun decided(
        s: FindUi,
        seq: Int,
        q: String,
        d: FindDecision?,
        nowMs: Long,
    ): Pair<FindUi, FindEffects> {
        if (seq != s.seq) return s to FindEffects() // a newer submit took over
        if (d == null) return s.copy(deciding = false, view = FindMode.SEARCH, decisionId = null) to FindEffects(search = q.trim())
        val text = d.askText ?: q.trim()
        val next = FindUi(view = d.mode, deciding = false, decisionId = d.id, decidedQ = q.trim(), decidedAtMs = nowMs, feedbackSent = false, seq = s.seq,
                          chatSent = d.mode == FindMode.CHAT, askText = if (d.mode == FindMode.SEARCH) null else text)
        return when (d.mode) {
            FindMode.CHAT -> next to FindEffects(chat = text)
            FindMode.BOTH -> next to FindEffects(chatAfterMs = BOTH_CHAT_DELAY_MS)
            FindMode.SEARCH -> next to FindEffects()
        }
    }

    /** BOTH's wait is over (for submit [seq]): send the chat turn unless a pick came first, a newer submit took over, or it went already. */
    fun bothTimer(
        s: FindUi,
        seq: Int,
    ): Pair<FindUi, FindEffects> {
        if (seq != s.seq || s.view != FindMode.BOTH || s.chatSent || s.picked) return s to FindEffects()
        return s.copy(chatSent = true) to FindEffects(chat = s.askText ?: s.decidedQ)
    }

    /** BOTH: focus reached the chat chip (the person is heading for the chat answer): send it now. */
    fun chatFocused(s: FindUi): Pair<FindUi, FindEffects> {
        if (s.view != FindMode.BOTH || s.chatSent) return s to FindEffects()
        return s.copy(chatSent = true) to FindEffects(chat = s.askText ?: s.decidedQ)
    }

    /** "Ask Chat about this" on a search answer (no chat sent yet) or on BOTH (the answer is already there): into the chat. */
    fun askChip(
        s: FindUi,
        q: String,
    ): Pair<FindUi, FindEffects> {
        if (s.deciding) return s to FindEffects() // a decision is on its way: the chip is not an answer to anything yet
        val fb = s.decisionId?.takeIf { !s.feedbackSent }?.let { it to FindSwitch.ASK_CHIP }
        val sendChat = s.view == FindMode.SEARCH || !s.chatSent
        val text = (if (s.view == FindMode.BOTH) s.askText ?: s.decidedQ else null) ?: q.trim()
        return s.copy(view = FindMode.CHAT, feedbackSent = s.feedbackSent || fb != null, chatSent = true, seq = s.seq + 1) to
            FindEffects(chat = if (sendChat) text.ifEmpty { null } else null, feedback = fb)
    }

    /**
     * Hold Search: the chat view with its mic. From the results (SEARCH / BOTH) it is a new question: the old chat is cleared and an
     * answer still on its way is dropped, so the spoken words go through /v1/find. Already in the chat: the mic is a follow-up.
     */
    fun holdToTalk(s: FindUi): Pair<FindUi, FindEffects> {
        if (s.view == FindMode.CHAT && !s.deciding) return s to FindEffects()
        return FindUi(view = FindMode.CHAT, seq = s.seq + 1) to FindEffects(resetChat = true)
    }

    /** The chat is off on this server: back to the results, dropping anything on its way. */
    fun chatOff(s: FindUi): Pair<FindUi, FindEffects> = s.copy(view = FindMode.SEARCH, deciding = false, seq = s.seq + 1) to FindEffects()

    /** "Just search for this" under a chat answer: back to the plain results for the same words. */
    fun searchChip(
        s: FindUi,
        q: String,
        /** Back, not the chip: a plain "go back" is not a correction of the decision, so no feedback. */
        viaBack: Boolean = false,
    ): Pair<FindUi, FindEffects> {
        val fb = s.decisionId?.takeIf { !s.feedbackSent && !viaBack }?.let { it to FindSwitch.SEARCH_CHIP }
        return s.copy(view = FindMode.SEARCH, deciding = false, seq = s.seq + 1, feedbackSent = s.feedbackSent || fb != null) to
            FindEffects(search = q.trim(), resetChat = true, feedback = fb)
    }

    /** A result or chat pick was opened / played: a confirmation of the decision (once). */
    fun played(s: FindUi): Pair<FindUi, FindEffects> {
        val fb = s.decisionId?.takeIf { !s.feedbackSent }?.let { it to FindSwitch.PLAYED } ?: return s.copy(picked = true) to FindEffects()
        return s.copy(feedbackSent = true, picked = true) to FindEffects(feedback = fb)
    }

    /** Start over: an empty box, plain search, no chat. */
    fun startOver(s: FindUi): Pair<FindUi, FindEffects> = FindUi(seq = s.seq + 1) to FindEffects(resetChat = true)
}

/** The one box's words (unit tested). */
object FindText {
    const val DECIDING = "Working out what you mean…"

    /** Over the chat: back to plain results for the decided words. */
    fun justSearch(q: String): String = "Just search for “${q.take(40)}”  ›"

    /** BOTH's chip under the box: what the chat is doing, then what it said. */
    fun bothChip(
        deciding: Boolean,
        chatPending: Boolean,
        reply: String?,
        picks: Int,
    ): String =
        when {
            deciding -> DECIDING
            chatPending -> "Asking Chat about this…"
            reply != null && picks > 0 -> "Chat: $reply · $picks ${if (picks == 1) "pick" else "picks"}  ›"
            else -> "Ask Chat about this  ›"
        }
}
