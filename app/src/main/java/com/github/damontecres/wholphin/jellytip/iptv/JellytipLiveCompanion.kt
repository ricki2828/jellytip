package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/*
 * TV companion parity pieces (web Companion/Scorebug.tsx, DelayChip.tsx, channel switcher): the snapshot store the reveal
 * clock releases from, the playhead lag, the scorebug pill, the Adjust delay sheet and the channel chips. The player
 * page owns the state and the keys; these only draw.
 */

/** Seconds of an event stamp (`wallclock` / `at`, ISO or epoch). */
fun stampSec(s: String?): Double? = IptvParse.isoSeconds(s)?.toDouble()

/**
 * /live answers kept in arrival order so the reveal clock can release the newest one it allows (web useLiveFeed). Full
 * answers (panel open) are preferred for the panel; light ones still carry the score for the scorebug.
 */
class LiveSnapshots(
    private val keep: Int = 40,
) {
    private data class Snap(
        val d: MatchLive,
        val full: Boolean,
        /** Epoch s: the server's updatedAt, else when it arrived. */
        val at: Long,
    )

    private val snaps = ArrayList<Snap>()

    fun add(
        d: MatchLive,
        full: Boolean,
        arrivedSec: Long,
    ) {
        snaps += Snap(d, full, d.updatedAt ?: arrivedSec)
        if (snaps.size > keep) snaps.removeAt(0)
    }

    fun clear() = snaps.clear()

    /** Newest released answer (any kind), or null while everything is still held. */
    fun released(reveal: (Double?) -> Boolean): MatchLive? {
        val i = CompanionPolicy.newestRevealedIndex(snaps.map { it.at }, reveal)
        return if (i < 0) null else snaps[i].d
    }

    /**
     * For the panel: the newest released full answer, with the score / clock / moments / market of a newer released light
     * answer laid over it (so it never shows an older score than the scorebug). No full answer yet: the light one.
     */
    fun releasedFull(reveal: (Double?) -> Boolean): MatchLive? {
        val i = CompanionPolicy.newestRevealedIndex(snaps.map { it.at }, reveal)
        if (i < 0) return null
        val newest = snaps[i]
        var full: Snap? = null
        for (j in i downTo 0) if (snaps[j].full) {
            full = snaps[j]
            break
        }
        val f = full ?: return newest.d
        if (f === newest) return f.d
        val l = newest.d
        return f.d.copy(
            status = l.status ?: f.d.status,
            clock = l.clock,
            period = l.period,
            scoreHome = l.scoreHome,
            scoreAway = l.scoreAway,
            market = l.market ?: f.d.market,
            moments = if (l.hasMoments) l.moments else f.d.moments,
            updatedAt = l.updatedAt,
        )
    }

    /** The newest answer's stamp, for "Live info in N s". */
    fun newestAt(): Long? = snaps.lastOrNull()?.at
}

/**
 * How far the viewer sits behind the live edge, in seconds, measured against the offset when this target started
 * playing (so a provider clock skew in EXT-X-PROGRAM-DATE-TIME never fakes a replay; the calibrated delay absorbs the
 * constant part). A stream with no live window (most TS) reads 0.
 */
class PlayerLag {
    private var baselineMs: Long? = null
    private var key: Any? = null
    private var item: Any? = null

    fun reset(k: Any?) {
        if (k != key) {
            key = k
            baselineMs = null
        }
    }

    fun lagSec(p: Player?): Double {
        p ?: return 0.0
        // A new stream (zap, failover, chip) gets its own baseline, sampled only once it plays
        val cur = p.currentMediaItem
        if (cur !== item) {
            item = cur
            baselineMs = null
        }
        if (p.playbackState != Player.STATE_READY && baselineMs == null) return 0.0
        if (!p.isCurrentMediaItemLive) return 0.0
        val off = p.currentLiveOffset
        if (off == C.TIME_UNSET || off < 0) return 0.0
        val b = baselineMs ?: off.also { baselineMs = it }
        // The baseline follows the live edge down (a stream that catches up), never up
        if (off < b) baselineMs = off
        return ((off - (baselineMs ?: off)) / 1000.0).coerceAtLeast(0.0)
    }
}

/** Score pill top-left: "WAR 12 – 6 PAN · 2nd 45:10", or the hold line while the first answer is held back. */
@Composable
fun ScorebugPill(
    home: String,
    away: String,
    scoreHome: String?,
    scoreAway: String?,
    clock: String?,
    period: String?,
    pal: MatchPalette,
    holdText: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min).background(Broadcast.Base.copy(alpha = 0.92f), RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (holdText != null) {
            Cond(holdText, 15.sp, Broadcast.Secondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            return@Row
        }
        Box(Modifier.width(6.dp).fillMaxHeight().background(if (pal.known) pal.home else Broadcast.Gold))
        Cond(home, 18.sp, Color.White, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
        Cond("${scoreHome ?: "–"}  –  ${scoreAway ?: "–"}", 20.sp, Broadcast.Gold)
        Cond(away, 18.sp, Color.White, modifier = Modifier.padding(start = 8.dp, end = 10.dp))
        Box(Modifier.width(6.dp).fillMaxHeight().background(if (pal.known) pal.away else Broadcast.Gold))
        val whenText = listOfNotNull(period, clock).joinToString(" ")
        if (whenText.isNotBlank()) Cond(whenText, 14.sp, Broadcast.Secondary, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

/** Rows of the Adjust delay sheet, in cursor order. */
val DELAY_ROWS = listOf("+5 s", "−5 s", "That just happened", "Reset")

/** Adjust delay: the current value, ±5 s steppers (Left/Right step too), "That just happened" and reset. */
@Composable
fun DelaySheet(
    delaySec: Int,
    defaultSec: Int,
    cursor: Int,
    canCalibrate: Boolean,
    replay: Boolean,
    note: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp).background(Broadcast.Base.copy(alpha = 0.97f), RoundedCornerShape(12.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Spoiler delay", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            if (replay) "Updates are off while you watch a replay" else "Scores and alerts wait $delaySec s",
            color = Broadcast.Gold,
            fontFamily = JellytipFontFamily,
            fontSize = 15.sp,
        )
        DELAY_ROWS.forEachIndexed { i, label ->
            val text =
                when (i) {
                    2 -> if (canCalibrate) label else "$label (after a score)"
                    3 -> "Reset to $defaultSec s"
                    else -> label
                }
            SheetRow(text, focused = cursor == i)
        }
        Text("◀ ▶ step 5 s   ·   Back closes", color = Broadcast.Secondary, fontFamily = JellytipFontFamily, fontSize = 12.sp)
        note?.let { Text(it, color = Color.White, fontFamily = JellytipFontFamily, fontSize = 13.sp) }
    }
}

@Composable
private fun SheetRow(
    label: String,
    focused: Boolean,
) {
    Text(
        label,
        color = if (focused) Color.Black else Color.White,
        fontFamily = JellytipFontFamily,
        fontSize = 16.sp,
        modifier =
            Modifier
                .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** The match's channels as chips along the bottom; [cursor] has the white focus ring, [currentId] the ✓. */
@Composable
fun ChannelChips(
    channels: List<IptvChannel>,
    currentId: String,
    cursor: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Cond("Other channels for this game", 14.sp, Broadcast.Gold, tracking = 0.06f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            channels.forEachIndexed { i, ch ->
                val name = ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
                val focused = i == cursor
                Text(
                    (if (ch.id == currentId) "✓ " else "") + name,
                    color = if (focused) Color.Black else Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .background(if (focused) Color.White else Broadcast.Base.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                            .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.25f)), RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Text("◀ ▶ choose · OK switch · Back closes", color = Broadcast.Secondary, fontFamily = JellytipFontFamily, fontSize = 12.sp)
    }
}
