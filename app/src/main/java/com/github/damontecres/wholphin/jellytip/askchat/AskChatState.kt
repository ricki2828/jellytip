package com.github.damontecres.wholphin.jellytip.askchat

import com.github.damontecres.wholphin.jellytip.ask.AskChip
import com.github.damontecres.wholphin.jellytip.ask.AskItem
import com.github.damontecres.wholphin.jellytip.ask.AskParse
import com.github.damontecres.wholphin.jellytip.ask.ChipData
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import org.json.JSONArray
import org.json.JSONObject

/*
 * Chat search (TV): the turn-state machine, a port of web/src/lib/askChat.ts (jellytip-web-askchat-iptv f3db77c2, reviewed CLEAR x4).
 * Pure Kotlin + org.json, no Android UI: the page only renders it. Plan: ~/ai/plans/jellytip-chat-search.md sections 3, 3a, 6, 7.
 *
 * The server (POST /v1/ask/chat on the companion) turns each turn into chips and picks the titles; the client never ranks cards.
 * Rules, as on the web:
 *  - a response is applied only when its rev is the latest issued, and only while a request is pending;
 *  - while a turn is pending the previous cards are HIDDEN (skeleton) and the chips are the dimmed local reading; every control is
 *    disabled, and submitText / tapChip REFUSE (null) so the rule holds even if the UI wiring misses it;
 *  - errors: 404 = chat is off (classic Ask), 401 / 403 = signed out, 400 / 413 = trim the oldest turns and re-send once, then a
 *    plain message, anything else (0, 5xx, 429...) = unreachable + Retry of the same body. Unreachable and too-long clear the cards,
 *    notes and matched count (the superseded reading's cards never reappear).
 */

const val CHAT_MAX_TURNS = 6
const val CHAT_MAX_TEXT = 300
const val CHAT_MAX_REMOVED = 40
const val CHAT_NOTICE_TEXT = "What you type is sent to Claude (Anthropic) to understand it."
const val CHAT_TOO_LONG_TEXT = "Couldn't send that — try a shorter message"

/** The `t` values of the native ChipData the client can build a chip for (the server's own types are opaque to it). */
val CHAT_NATIVE_T = setOf("genre", "notGenre", "kind", "maxRuntime", "minRuntime", "years", "unplayed", "played", "kids", "minRating", "maxSeasons", "person")

// ---------- wire ----------

/**
 * A chip as the server returns it. [raw] is the exact object received: it is echoed back verbatim, a server chip (mood / like /
 * source / noscary) round-trips byte-identical. [native] is the parsed t5k chip for native types (null for server chips).
 */
data class ChatChip(
    val id: String,
    val label: String,
    val server: Boolean,
    /** `data.t` */
    val type: String,
    val removable: Boolean,
    val isProtected: Boolean,
    /** false = shown dimmed ("not applied"); null = no statement. */
    val applied: Boolean?,
    val raw: JSONObject,
    val native: AskChip?,
)

sealed interface ChatOp {
    data class Add(
        val data: ChipData,
    ) : ChatOp

    data class Remove(
        val id: String,
    ) : ChatOp
}

data class ChatTurn(
    val user: String,
    val reply: String,
)

sealed interface ChatTitle {
    val kind: String

    /** A library title: fetched from Jellyfin by [id]. */
    data class Library(
        override val kind: String,
        val id: String,
        val title: String?,
        val year: Int?,
        val certification: String?,
        val runtime: Int?,
        /** Where else this title can be played (`sources`, library first); the IPTV copy of a library title feeds the library menu's "Play from IPTV". */
        val sources: List<IptvSource> = emptyList(),
    ) : ChatTitle

    /** A TV (IPTV) title: drawn straight from these fields, never fetched from Jellyfin. */
    data class Iptv(
        override val kind: String,
        val iptvId: String,
        val title: String?,
        val year: Int?,
        val tmdbId: Int?,
        val rating: Double?,
        val poster: String?,
        val backdrop: String?,
        val quality: List<String>,
        val tech: List<String>,
        val catalog: Boolean,
        val certification: String?,
        val runtime: Int?,
        val audio: String?,
        /** Every place the title can be played from (`sources`, library first; a library entry is present only when the caller can open a library copy). */
        val sources: List<IptvSource> = emptyList(),
        /** The library copy's id (the library entry of [sources]); null = none. OK then opens the library page. */
        val libraryId: String? = null,
    ) : ChatTitle
}

data class NoteAction(
    val label: String,
    val op: ChatOp,
)

data class ChatNote(
    val text: String,
    val code: String,
    val actions: List<NoteAction> = emptyList(),
)

data class ChatBody(
    val rev: Int,
    val text: String? = null,
    val op: ChatOp? = null,
    val turns: List<ChatTurn> = emptyList(),
    val chips: List<ChatChip> = emptyList(),
    val removed: List<String> = emptyList(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("rev", rev)
        text?.let { o.put("text", it) }
        op?.let { o.put("op", AskChat.opToJson(it)) }
        o.put("turns", JSONArray(turns.map { JSONObject().put("user", it.user).put("reply", it.reply) }))
        o.put("chips", JSONArray(chips.map { JSONObject(it.raw.toString()) }))
        o.put("removed", JSONArray(removed))
        return o.toString()
    }
}

data class ChatResponse(
    val rev: Int,
    /** "llm" | "fallback" */
    val mode: String,
    val reply: String,
    val chips: List<ChatChip>,
    val cards: List<ChatTitle>,
    val notes: List<ChatNote>,
    /** The sources actually searched this turn (strings): ["library"], ["library","iptv"], ["iptv"]. */
    val sources: List<String>,
    val matched: Int,
    val partial: Boolean,
)

// ---------- state ----------

enum class ChatError { Off, SignedOut, Unreachable, TooLong }

data class ChatPending(
    val body: ChatBody,
    /** already re-sent once with fewer turns (400 / 413) */
    val trimmed: Boolean = false,
)

data class ChatState(
    val turns: List<ChatTurn> = emptyList(),
    /** The server's chips, exactly as received (echoed back verbatim). */
    val chips: List<ChatChip> = emptyList(),
    val removed: List<String> = emptyList(),
    /** The latest revision issued; only a response with this rev is applied. */
    val rev: Int = 0,
    val pending: ChatPending? = null,
    /** The server's one-line reading of the current state. */
    val reply: String = "",
    val cards: List<ChatTitle> = emptyList(),
    val notes: List<ChatNote> = emptyList(),
    val mode: String? = null,
    val sources: List<String> = emptyList(),
    val matched: Int = 0,
    val error: ChatError? = null,
    /** The body that could not be delivered (error Unreachable): Retry re-sends it. */
    val failed: ChatBody? = null,
)

/** A request to send, and the state it leaves behind (pending). */
data class Issued(
    val state: ChatState,
    val body: ChatBody,
)

data class ErrorResult(
    val state: ChatState,
    /** Non-null: send this body again (400 / 413 with the oldest turns trimmed). */
    val resend: ChatBody?,
)

/** A chip shown while a turn is pending: the local reading, dimmed. */
data class ShownChip(
    val chip: ChatChip,
    val provisional: Boolean,
)

object AskChat {
    // ---------- turns ----------

    private val WS = Regex("\\s+")

    private fun bodyFor(
        s: ChatState,
        rev: Int,
        text: String? = null,
        op: ChatOp? = null,
    ) = ChatBody(
        rev = rev,
        text = text,
        op = op,
        turns = s.turns.takeLast(CHAT_MAX_TURNS).map { ChatTurn(it.user.take(CHAT_MAX_TEXT), it.reply) },
        chips = s.chips, // verbatim
        removed = s.removed.takeLast(CHAT_MAX_REMOVED),
    )

    /**
     * A typed or spoken turn. null for empty text, and null while another turn is pending (a second send would be built from the
     * old server state and silently drop the pending one). The new state is pending: skeleton, no cards, local chips shown dimmed.
     */
    fun submitText(
        s: ChatState,
        text: String,
    ): Issued? {
        if (s.pending != null) return null
        val t = text.replace(WS, " ").trim().take(CHAT_MAX_TEXT)
        if (t.isEmpty()) return null
        val body = bodyFor(s, s.rev + 1, text = t)
        return Issued(s.copy(rev = body.rev, pending = ChatPending(body), error = null, failed = null), body)
    }

    /**
     * A chip tap: add (a refine chip) or remove (a chip's x, a note action). Pending too: no cards until it answers. null while
     * another turn is pending, or for an add that is not a known native chip.
     */
    fun tapChip(
        s: ChatState,
        op: ChatOp,
    ): Issued? {
        if (s.pending != null) return null
        if (op is ChatOp.Add && safeChipFor(op.data) == null) return null
        val body = bodyFor(s, s.rev + 1, op = op)
        return Issued(s.copy(rev = body.rev, pending = ChatPending(body), error = null, failed = null), body)
    }

    /**
     * The server's answer: applied only when it is for the latest revision issued and a request is pending; anything older is
     * dropped. Everything is replaced wholesale from the server (chips, cards, notes, mode, sources).
     */
    fun applyResponse(
        s: ChatState,
        r: ChatResponse,
    ): ChatState {
        val p = s.pending ?: return s
        if (r.rev != s.rev) return s
        val b = p.body
        var turns = s.turns
        if (b.text != null) {
            turns = (turns + ChatTurn(b.text, r.reply)).takeLast(CHAT_MAX_TURNS)
        } else if (turns.isNotEmpty()) {
            turns = turns.dropLast(1) + turns.last().copy(reply = r.reply) // an op restates the current reading
        }
        var removed = s.removed
        val op = b.op
        if (op is ChatOp.Remove && op.id !in removed) removed = removed + op.id
        if (op is ChatOp.Add) {
            val id = safeChipFor(op.data)?.id
            removed = removed.filter { it != id }
        }
        val have = r.chips.map { it.id }.toSet()
        removed = removed.filter { it !in have } // a chip the server holds is not "removed"
        return ChatState(
            turns = turns, chips = r.chips, removed = removed, rev = s.rev, pending = null, reply = r.reply, cards = r.cards,
            notes = r.notes, mode = r.mode, sources = r.sources, matched = r.matched, error = null, failed = null,
        )
    }

    /**
     * A failed request for revision [rev] (ignored when a newer one has been issued).
     * 404 = the feature is off (classic Ask); 401 / 403 = signed out; 400 / 413 = too big: the oldest turns are trimmed and the
     * request is re-sent once ([ErrorResult.resend]), then a plain "shorter message"; anything else (0, 5xx, 429...) = unreachable:
     * Retry re-sends the same body, and the cards of the superseded reading stay hidden until a newer response is applied.
     */
    fun applyError(
        s: ChatState,
        rev: Int,
        status: Int,
    ): ErrorResult {
        val p = s.pending
        if (p == null || rev != s.rev) return ErrorResult(s, null)
        return when (status) {
            404 -> ErrorResult(s.copy(pending = null, error = ChatError.Off), null)

            401, 403 -> ErrorResult(s.copy(pending = null, error = ChatError.SignedOut), null)

            400, 413 -> {
                val b = p.body
                if (!p.trimmed && b.turns.isNotEmpty()) {
                    val body = b.copy(turns = b.turns.takeLast(b.turns.size / 2))
                    ErrorResult(s.copy(pending = ChatPending(body, trimmed = true)), body)
                } else {
                    ErrorResult(s.copy(pending = null, error = ChatError.TooLong, cards = emptyList(), notes = emptyList(), matched = 0), null)
                }
            }

            else ->
                ErrorResult(
                    s.copy(pending = null, error = ChatError.Unreachable, failed = p.body, cards = emptyList(), notes = emptyList(), matched = 0),
                    null,
                )
        }
    }

    /**
     * "Start over": clears turns, chips, removed, cards, notes and the error. The rev is bumped past anything issued, so a response (or
     * error) still in flight for the old conversation finds no pending turn / a stale rev and is dropped. Allowed while pending (the
     * one exception to the pending rule). No server call: the next text starts a fresh conversation (the body carries no history).
     */
    fun startOver(s: ChatState): ChatState = ChatState(rev = s.rev + 1)

    /** Retry after "unreachable": the same body again (same rev: nothing newer was issued). */
    fun retry(s: ChatState): Issued? {
        val f = s.failed ?: return null
        if (s.error != ChatError.Unreachable) return null
        return Issued(s.copy(pending = ChatPending(f), error = null, failed = null), f)
    }

    /** The chips to draw: the server's, and while a turn is pending the local reading on top (provisional: dimmed, "reading..."). */
    fun displayChips(
        s: ChatState,
        year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    ): List<ShownChip> {
        val cur = s.chips.map { ShownChip(it, false) }
        val b = s.pending?.body ?: return cur
        if (b.text != null) {
            val read = AskParse.parse(b.text, year).chips.filter { r -> s.chips.none { it.id == r.id } }
            return cur + read.map { ShownChip(localChip(it), true) }
        }
        val op = b.op
        if (op is ChatOp.Add) {
            val c = safeChipFor(op.data) ?: return cur
            return if (s.chips.any { it.id == c.id }) cur else cur + ShownChip(localChip(c), true)
        }
        if (op is ChatOp.Remove) return cur.filter { it.chip.id != op.id }
        return cur
    }

    private fun localChip(c: AskChip) =
        ChatChip(c.id, c.label, false, c.data.typeName(), removable = false, isProtected = false, applied = null, raw = JSONObject(), native = c)

    // ---------- chips ----------

    /** chipFor that never throws: null for anything that is not a known native ChipData. */
    fun safeChipFor(d: ChipData?): AskChip? =
        try {
            if (d == null) null else AskParse.chipFor(d)
        } catch (e: Exception) {
            null
        }

    /** A wire chip object -> ChatChip, or null when it lacks an id / label / data.t. */
    fun parseChip(o: JSONObject): ChatChip? {
        val id = o.optString("id", "").ifEmpty { return null }
        val label = o.optString("label", "").ifEmpty { return null }
        val data = o.optJSONObject("data") ?: return null
        val t = data.optString("t", "").ifEmpty { return null }
        val server = o.optBoolean("server", false)
        val native = if (!server && t in CHAT_NATIVE_T) parseChipData(data)?.let { safeChipFor(it) } else null
        return ChatChip(
            id = id,
            label = label,
            server = server,
            type = t,
            removable = o.optBoolean("removable", true),
            isProtected = o.optBoolean("protected", false),
            applied = if (o.has("applied") && !o.isNull("applied")) o.optBoolean("applied") else null,
            raw = JSONObject(o.toString()),
            native = native,
        )
    }

    /** `data` -> native ChipData (validated: ranges and shapes), or null. */
    fun parseChipData(o: JSONObject): ChipData? {
        fun strings(k: String): List<String>? {
            val a = o.optJSONArray(k) ?: return null
            val out = (0 until a.length()).map { a.opt(it) as? String ?: return null }
            return out
        }
        fun int(k: String): Int? = if (o.has(k) && o.get(k) is Number) (o.get(k) as Number).let { n -> if (n.toDouble() == n.toInt().toDouble()) n.toInt() else null } else null
        return when (o.optString("t")) {
            "genre" -> strings("genres")?.takeIf { it.isNotEmpty() }?.let { ChipData.Genre(it) }
            "notGenre" -> strings("genres")?.takeIf { it.isNotEmpty() }?.let { ChipData.NotGenre(it) }
            "kind" -> o.optString("kind").takeIf { it == "Movie" || it == "Series" }?.let { ChipData.Kind(it) }
            "maxRuntime" -> int("min")?.takeIf { it in 1..1000 }?.let { ChipData.MaxRuntime(it) }
            "minRuntime" -> int("min")?.takeIf { it in 1..1000 }?.let { ChipData.MinRuntime(it) }
            "years" -> {
                val f = int("from")
                val t = int("to")
                if (f != null && t != null && f in 1800..2200 && t in 1800..2200 && f <= t) ChipData.Years(f, t) else null
            }
            "unplayed" -> ChipData.Unplayed
            "played" -> ChipData.Played
            "kids" -> ChipData.Kids(strings("ratings") ?: AskParse.KIDS_RATINGS, strings("genres") ?: AskParse.KIDS_GENRES)
            "minRating" -> (o.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..10.0 }?.let { ChipData.MinRating(it) }
            "maxSeasons" -> int("n")?.takeIf { it in 1..100 }?.let { ChipData.MaxSeasons(it) }
            "person" -> {
                val id = o.optString("id")
                val name = o.optString("name")
                val role = o.optString("role")
                if (id.isNotEmpty() && name.isNotEmpty() && (role == "Director" || role == "Actor")) ChipData.Person(id, name, role) else null
            }
            else -> null
        }
    }

    fun chipDataToJson(d: ChipData): JSONObject =
        when (d) {
            is ChipData.Genre -> JSONObject().put("t", "genre").put("genres", JSONArray(d.genres))
            is ChipData.NotGenre -> JSONObject().put("t", "notGenre").put("genres", JSONArray(d.genres))
            is ChipData.Kind -> JSONObject().put("t", "kind").put("kind", d.kind)
            is ChipData.MaxRuntime -> JSONObject().put("t", "maxRuntime").put("min", d.min)
            is ChipData.MinRuntime -> JSONObject().put("t", "minRuntime").put("min", d.min)
            is ChipData.Years -> JSONObject().put("t", "years").put("from", d.from).put("to", d.to)
            ChipData.Unplayed -> JSONObject().put("t", "unplayed")
            ChipData.Played -> JSONObject().put("t", "played")
            is ChipData.Kids -> JSONObject().put("t", "kids").put("ratings", JSONArray(d.ratings)).put("genres", JSONArray(d.genres))
            is ChipData.MinRating -> JSONObject().put("t", "minRating").put("value", d.value)
            is ChipData.MaxSeasons -> JSONObject().put("t", "maxSeasons").put("n", d.n)
            is ChipData.Person -> JSONObject().put("t", "person").put("id", d.id).put("name", d.name).put("role", d.role)
        }

    fun opToJson(op: ChatOp): JSONObject =
        when (op) {
            is ChatOp.Add -> JSONObject().put("add", chipDataToJson(op.data))
            is ChatOp.Remove -> JSONObject().put("remove", op.id)
        }

    private fun parseOp(o: JSONObject?): ChatOp? {
        if (o == null) return null
        val rem = o.optString("remove", "")
        if (rem.isNotEmpty()) return ChatOp.Remove(rem)
        val add = o.optJSONObject("add") ?: return null
        val d = parseChipData(add) ?: return null
        return if (safeChipFor(d) != null) ChatOp.Add(d) else null
    }

    // ---------- response parsing ----------

    private fun optStr(
        o: JSONObject,
        k: String,
    ): String? = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotEmpty() } else null

    private fun optInt(
        o: JSONObject,
        k: String,
    ): Int? = if (o.has(k) && !o.isNull(k) && o.get(k) is Number) (o.get(k) as Number).toInt() else null

    private fun optDouble(
        o: JSONObject,
        k: String,
    ): Double? = if (o.has(k) && !o.isNull(k) && o.get(k) is Number) (o.get(k) as Number).toDouble() else null

    private fun strList(a: JSONArray?): List<String> = a?.let { (0 until it.length()).mapNotNull { i -> it.opt(i) as? String } } ?: emptyList()

    private fun parseCard(o: JSONObject): ChatTitle? {
        val kind = o.optString("kind")
        if (kind != "film" && kind != "series") return null
        return when (o.optString("source")) {
            "library" -> {
                val id = o.optString("id", "").ifEmpty { return null }
                ChatTitle.Library(kind, id, optStr(o, "title"), optInt(o, "year"), optStr(o, "certification"), optInt(o, "runtime"), IptvVodParse.sources(o.optJSONArray("sources")))
            }

            "iptv" -> {
                val id = o.optString("iptvId", "").ifEmpty { return null }
                val sources = IptvVodParse.sources(o.optJSONArray("sources"))
                ChatTitle.Iptv(
                    kind, id, optStr(o, "title"), optInt(o, "year"), optInt(o, "tmdbId"), optDouble(o, "rating"), optStr(o, "poster"), optStr(o, "backdrop"),
                    strList(o.optJSONArray("quality")), strList(o.optJSONArray("tech")), o.optBoolean("catalog", false), optStr(o, "certification"),
                    optInt(o, "runtime"), optStr(o, "audio"),
                    sources, sources.firstOrNull { it.type == "library" }?.id,
                )
            }

            else -> null
        }
    }

    /** Wire JSON -> [ChatResponse], or null when it is not a chat answer. Malformed chips / cards / notes are dropped, not fatal. */
    fun parseResponse(text: String?): ChatResponse? {
        val d =
            try {
                JSONObject(text ?: return null)
            } catch (e: Exception) {
                return null
            }
        if (d.optInt("v", -1) != 1 || !d.has("rev") || d.get("rev") !is Number) return null
        val mode = d.optString("mode")
        if (mode != "llm" && mode != "fallback") return null
        if (!d.has("reply") || d.get("reply") !is String) return null
        val chips = d.optJSONArray("chips")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> parseChip(o) } } } ?: emptyList()
        val cards = d.optJSONArray("cards")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> parseCard(o) } } } ?: emptyList()
        val notes =
            d.optJSONArray("notes")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val n = a.optJSONObject(i) ?: return@mapNotNull null
                    if (n.opt("text") !is String || n.opt("code") !is String) return@mapNotNull null
                    val actions =
                        n.optJSONArray("actions")?.let { aa ->
                            (0 until aa.length()).mapNotNull { j ->
                                val ao = aa.optJSONObject(j) ?: return@mapNotNull null
                                val label = ao.opt("label") as? String ?: return@mapNotNull null
                                val op = parseOp(ao.optJSONObject("op")) ?: return@mapNotNull null
                                NoteAction(label, op)
                            }.take(3)
                        } ?: emptyList()
                    ChatNote(n.getString("text"), n.getString("code"), actions)
                }
            } ?: emptyList()
        return ChatResponse(
            rev = (d.get("rev") as Number).toInt(),
            mode = mode,
            reply = d.getString("reply"),
            chips = chips,
            cards = cards,
            notes = notes,
            sources = strList(d.optJSONArray("sources")),
            matched = optInt(d, "matched") ?: cards.size,
            partial = d.optBoolean("partial", false),
        )
    }

    // ---------- cards ----------

    fun normId(id: String) = id.lowercase().replace("-", "")

    fun cardKey(c: ChatTitle): String =
        when (c) {
            is ChatTitle.Iptv -> "iptv:${c.iptvId}"
            is ChatTitle.Library -> c.id
        }

    /** Library ids only: those are the ones fetched from Jellyfin. */
    fun libraryIds(cards: List<ChatTitle>): List<String> = cards.filterIsInstance<ChatTitle.Library>().map { it.id }

    /** A card in the server's order with its fetched item (library) or none (TV). */
    data class Placed<T>(
        val card: ChatTitle,
        val item: T?,
    )

    /**
     * The server's cards in the server's order. A library card carries its fetched item; an id the fetch dropped is a gap (no
     * tile), never a substitute. A TV card has no item: it is drawn from its own fields and is never dropped by the fetch.
     */
    fun <T> alignCards(
        cards: List<ChatTitle>,
        items: List<T>,
        idOf: (T) -> String,
    ): List<Placed<T>> {
        val by = items.associateBy { normId(idOf(it)) }
        val out = mutableListOf<Placed<T>>()
        for (c in cards) {
            when (c) {
                is ChatTitle.Iptv -> out.add(Placed(c, null))
                is ChatTitle.Library -> by[normId(c.id)]?.let { out.add(Placed(c, it)) }
            }
        }
        return out
    }

    /** The scope hint under the reading: "Library", "Library + TV" or "TV", from the sources the server actually searched. */
    fun scopeHint(sources: List<String>): String {
        val lib = "library" in sources
        val tv = "iptv" in sources
        return if (lib && tv) "Library + TV" else if (tv) "TV" else if (lib) "Library" else ""
    }

    /** "Narrow it down": t5k's refine chips over the fetched titles and their people. Native chips only. */
    fun <T> refineChat(
        items: List<T>,
        people: List<T>,
        chips: List<ChatChip>,
        view: (T) -> AskItem,
    ): List<AskChip> = AskParse.refineOptions(items, people, chips.mapNotNull { it.native }, view)

    // ---------- the Claude notice (once per device) ----------

    fun noticeSeen(store: NoticeStore): Boolean =
        try {
            store.get() == true
        } catch (e: Exception) {
            false
        }

    fun dismissNotice(store: NoticeStore) {
        try {
            store.set(true)
        } catch (e: Exception) {
            // storage blocked: the notice shows again next time
        }
    }
}

/** Where "the notice was seen" lives (SharedPreferences in the app, a map in tests). */
interface NoticeStore {
    fun get(): Boolean?

    fun set(v: Boolean)
}

private fun ChipData.typeName(): String =
    when (this) {
        is ChipData.Genre -> "genre"
        is ChipData.NotGenre -> "notGenre"
        is ChipData.Kind -> "kind"
        is ChipData.MaxRuntime -> "maxRuntime"
        is ChipData.MinRuntime -> "minRuntime"
        is ChipData.Years -> "years"
        ChipData.Unplayed -> "unplayed"
        ChipData.Played -> "played"
        is ChipData.Kids -> "kids"
        is ChipData.MinRating -> "minRating"
        is ChipData.MaxSeasons -> "maxSeasons"
        is ChipData.Person -> "person"
    }
