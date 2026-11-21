package com.github.damontecres.wholphin.jellytip.movienight

import org.json.JSONArray
import org.json.JSONObject

/*
 * Shared movie night (web parity, round 3 item 6): pure logic ported from web lib/movieNight.ts (tally, winner and
 * tie-break, finished, the server's session view). The server (/v1/me/movienight*) stores ids and votes only; the TV is
 * the host screen (code + posters + live tally), everyone votes on their phone in the web app.
 */

enum class MnVote(
    val wire: String,
) {
    YES("yes"),
    MAYBE("maybe"),
    NO("no"),
    ;

    companion object {
        fun of(s: String?): MnVote? = entries.firstOrNull { it.wire == s }
    }
}

data class MnCounts(
    val yes: Int = 0,
    val maybe: Int = 0,
    val no: Int = 0,
) {
    val votes get() = yes + maybe + no
}

data class MnMember(
    val name: String,
    val voted: Int,
    val me: Boolean,
    val host: Boolean,
)

data class MnSession(
    val code: String,
    val open: Boolean,
    val host: Boolean,
    /** Dashless 32-hex library film ids, in the host's order. */
    val items: List<String>,
    /** Unix seconds. */
    val expiresAt: Long,
    val mine: Map<String, MnVote>,
    val counts: Map<String, MnCounts>,
    val members: List<MnMember>,
)

data class MnRow(
    val id: String,
    val counts: MnCounts,
    val score: Int,
    val order: Int,
)

object MovieNight {
    const val MIN_FILMS = 2
    const val MAX_FILMS = 12

    /** Films offered on the TV host screen (the mock-up's five; the host can untick down to [MIN_FILMS]). */
    const val TV_SHORTLIST = 5
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val ITEM_RE = Regex("^[0-9a-f]{32}$")

    /** OK on a poster cycles your own vote: none → Yes → Maybe → No → Yes. */
    fun nextVote(cur: MnVote?): MnVote =
        when (cur) {
            null, MnVote.NO -> MnVote.YES
            MnVote.YES -> MnVote.MAYBE
            MnVote.MAYBE -> MnVote.NO
        }

    private fun MnCounts.add(
        v: MnVote?,
        by: Int,
    ): MnCounts =
        when (v) {
            MnVote.YES -> copy(yes = (yes + by).coerceAtLeast(0))
            MnVote.MAYBE -> copy(maybe = (maybe + by).coerceAtLeast(0))
            MnVote.NO -> copy(no = (no + by).coerceAtLeast(0))
            null -> this
        }

    /** Optimistic local view of your vote: [MnSession.mine] updated and the counts moved from the old vote to the new one. */
    fun withVote(
        s: MnSession,
        item: String,
        vote: MnVote,
    ): MnSession {
        val old = s.mine[item]
        if (old == vote) return s
        val c = s.counts[item] ?: MnCounts()
        return s.copy(mine = s.mine + (item to vote), counts = s.counts + (item to c.add(old, -1).add(vote, 1)))
    }

    fun scoreOf(c: MnCounts): Int = c.yes * 2 + c.maybe * 1 + c.no * -2

    /** Every film with its score, best first. Ties: fewer "no", then more "yes", then the host's order. */
    fun tally(
        items: List<String>,
        counts: Map<String, MnCounts>,
    ): List<MnRow> =
        items
            .mapIndexed { order, id ->
                val c = counts[id] ?: MnCounts()
                MnRow(id, c, scoreOf(c), order)
            }.sortedWith(
                compareByDescending<MnRow> { it.score }
                    .thenBy { it.counts.no }
                    .thenByDescending { it.counts.yes }
                    .thenBy { it.order },
            )

    /** The top match, or null when nobody said yes or maybe to anything. */
    fun winner(rows: List<MnRow>): MnRow? = rows.firstOrNull()?.takeIf { it.counts.yes + it.counts.maybe > 0 }

    fun allVoted(s: MnSession): Boolean = s.members.size >= 2 && s.members.all { it.voted >= s.items.size }

    fun finished(s: MnSession): Boolean = !s.open || allVoted(s)

    fun minutesLeft(
        s: MnSession,
        nowS: Long,
    ): Long {
        val left = s.expiresAt - nowS
        return if (left <= 0) 0L else (left + 59) / 60
    }

    /** "ABC DEF" for reading aloud. */
    fun showCode(code: String): String = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    /** Vote dots under a poster: how many people have voted on this film. */
    fun votesLabel(c: MnCounts): String = if (c.votes == 1) "1 vote" else "${c.votes} votes"

    /** A library id as the server wants it (dashless lower hex), or null. */
    fun itemId(raw: String): String? = raw.replace("-", "").lowercase().takeIf { ITEM_RE.matches(it) }

    /** The server's session view, or null when it isn't one (an error body, an old server). */
    fun parse(json: String?): MnSession? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val code = o.opt("code") as? String ?: return null
        val status = o.opt("status") as? String
        if (status != "open" && status != "closed") return null
        val rawItems = o.opt("items") as? JSONArray ?: return null
        val items = ArrayList<String>()
        for (i in 0 until rawItems.length()) items += (rawItems.opt(i) as? String ?: return null)
        val counts = HashMap<String, MnCounts>()
        o.optJSONObject("counts")?.let { rc ->
            for (i in items) {
                val c = rc.optJSONObject(i) ?: continue
                val y = c.intOrNull("yes")
                val m = c.intOrNull("maybe")
                val n = c.intOrNull("no")
                if (y != null && m != null && n != null && y >= 0 && m >= 0 && n >= 0) counts[i] = MnCounts(y, m, n)
            }
        }
        val mine = HashMap<String, MnVote>()
        o.optJSONObject("mine")?.let { rm -> for (i in items) MnVote.of(rm.opt(i) as? String)?.let { mine[i] = it } }
        val members = ArrayList<MnMember>()
        o.optJSONArray("members")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.opt(i) as? JSONObject ?: continue
                members +=
                    MnMember(
                        name = m.opt("name") as? String ?: "",
                        voted = m.intOrNull("voted") ?: 0,
                        me = m.opt("me") == true,
                        host = m.opt("host") == true,
                    )
            }
        }
        val exp = (o.opt("expiresAt") as? Number)?.toLong() ?: 0L
        return MnSession(code, status == "open", o.opt("host") == true, items, exp, mine, counts, members)
    }

    /** A server error body's message, else a plain fallback for the status. */
    fun errorText(
        status: Int,
        body: String?,
    ): String {
        val e = runCatching { JSONObject(body ?: "").optString("error") }.getOrNull()
        if (!e.isNullOrBlank()) return e.replaceFirstChar { it.uppercase() }
        return when (status) {
            0 -> "Can't reach the server."
            404 -> "That movie night has ended or expired."
            else -> "Something went wrong."
        }
    }

    private fun JSONObject.intOrNull(k: String): Int? =
        (opt(k) as? Number)?.let { n -> n.toInt().takeIf { it.toDouble() == n.toDouble() } }
}
