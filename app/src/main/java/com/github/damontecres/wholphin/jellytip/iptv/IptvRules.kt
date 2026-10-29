package com.github.damontecres.wholphin.jellytip.iptv

/** Pure IPTV rules (unit tested): link failures, event channels, guide window maths. */
object IptvRules {
    enum class LinkFailure { RENEW, FULL, FATAL }

    /**
     * A stream link (or /play) answered [status] with [code] (X-Iptv-Error or the JSON code): 410 stale and 403 expired
     * mean "call /play again and retry once"; 409 means every connection is taken; anything else is final.
     */
    fun linkFailure(
        status: Int,
        code: String?,
    ): LinkFailure =
        when {
            status == 410 || (status == 403 && code == "expired") -> LinkFailure.RENEW
            status == 409 || code == "max_connections" -> LinkFailure.FULL
            else -> LinkFailure.FATAL
        }

    fun failureText(
        code: String?,
        fallback: String,
    ): String =
        when (code) {
            "provider_auth" -> "The provider refused the login. Check it in Settings › Live TV on the web."
            "provider_unreachable" -> "The provider isn't answering right now."
            "provider_refused", "provider_error" -> "The provider couldn't start this stream."
            "no_catchup" -> "This channel has no catch-up for that time."
            "disabled" -> "Live TV isn't switched on for you."
            "no_user", "signed_out" -> "Sign in again to use Live TV."
            "expired", "stale", "bad_token" -> "The stream link ran out. Try again."
            else -> fallback
        }

    private val eventGroup = Regex("""\b(PPV|EVENTS?)\b""", RegexOption.IGNORE_CASE)
    private val eventChannel = Regex("""\bPPV\b""", RegexOption.IGNORE_CASE)

    /**
     * Event feeds ("live when on": PPV / event groups), which are dark between games. Only the group decides, or PPV in
     * the channel name: "Sky Sports Main Event" is a normal channel.
     */
    fun isEventChannel(
        channelName: String,
        groupName: String,
    ): Boolean = eventGroup.containsMatchIn(groupName) || eventChannel.containsMatchIn(channelName)

    /**
     * An event channel is dark when the guide has nothing on now or next for it and its name names no fixture
     * (providers often put the event in the name: "NRL 01: Storm v Broncos", "Kayo 3 | 19:30 …").
     */
    fun isDark(
        channelName: String,
        groupName: String,
        nowNext: IptvNowNext?,
        /** The server's `event` flag when it sends one (wins over the name guess). */
        event: Boolean? = null,
    ): Boolean {
        if (!(event ?: isEventChannel(channelName, groupName))) return false
        if (nowNext?.now != null || nowNext?.next != null) return false
        return !namesAFixture(channelName)
    }

    private val fixture = Regex("""(:\s*\S)|(\|\s*\S.*\S)|(\bv(s)?\.?\s)|(\s@\s)|(\d{1,2}[:.]\d{2})""", RegexOption.IGNORE_CASE)

    fun namesAFixture(name: String): Boolean {
        // Strip a leading "|AU|"-style country tag first; it is not a fixture
        val body = name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
        return fixture.containsMatchIn(body)
    }

    /** Sorted, zero-length dropped, overlaps trimmed (a programme ends where the next one starts). */
    fun tidy(progs: List<IptvProg>): List<IptvProg> {
        val sorted = progs.filter { it.stop > it.start }.sortedBy { it.start }
        val out = ArrayList<IptvProg>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && p.start < last.stop) {
                if (p.stop <= last.stop) continue // wholly inside the previous one
                val trimmed = last.copy(stop = p.start)
                if (trimmed.stop > trimmed.start) out[out.size - 1] = trimmed else out.removeAt(out.size - 1)
            }
            out += p
        }
        return out
    }

    private val sportWords =
        Regex(
            """\b(sports?|football|soccer|rugby|cricket|nrl|afl|nfl|nba|nhl|mlb|ncaa[fb]?|f1|formula|golf|tennis|boxing|ufc|racing|motogp|super ?rugby|premier league|kayo|espn|bein|tnt sports|fox sports|sky sport)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Same guess as the web client while the server's `sport` flag is missing. */
    fun looksLikeSport(hint: String): Boolean = sportWords.containsMatchIn(hint)

    const val HALF_HOUR = 30 * 60L
    const val WINDOW_SECS = 3 * 60 * 60L

    /** The guide window start for [now]: the half hour that has begun. */
    fun windowStart(now: Long): Long = now - Math.floorMod(now, HALF_HOUR)

    /** Programme to land on in a row when moving up/down from [focusTime] (the one on then, else the nearest). */
    fun progAt(
        progs: List<IptvProg>,
        focusTime: Long,
    ): IptvProg? = progs.firstOrNull { it.isOn(focusTime) } ?: progs.minByOrNull { minOf(kotlin.math.abs(it.start - focusTime), kotlin.math.abs(it.stop - focusTime)) }
}
