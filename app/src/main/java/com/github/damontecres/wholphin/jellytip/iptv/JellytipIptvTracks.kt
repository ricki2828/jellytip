package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.debug.JellytipNavGate
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.tryRequestFocus
import java.util.Locale

/*
 * Audio and subtitles on IPTV films and episodes (web 51a17247 88346993 78801548 e91e1402, TrackMenu): the stream's own
 * tracks, picked through the player's track selection (player UI only: the stream, recovery and the player build are
 * untouched). On open the person's language preferences (ui audio / subtitle languages) are asked for.
 */
object IptvTracks {
    data class Option(
        val type: Int,
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val selected: Boolean,
        /** The group as it was when the menu opened: a pick checks it still sits at [groupIndex]. */
        val group: androidx.media3.common.TrackGroup? = null,
    )

    /** "English · 5.1", "Commentary (English)", "Track 2": the track's own label first, then its language. */
    fun label(
        label: String?,
        language: String?,
        channels: Int,
        n: Int,
    ): String {
        val lang =
            language
                ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
        val ch =
            when (channels) {
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            }
        val main =
            when {
                !label.isNullOrBlank() && lang != null && !label.contains(lang, ignoreCase = true) -> "$label ($lang)"
                !label.isNullOrBlank() -> label
                lang != null -> lang
                else -> "Track $n"
            }
        return listOfNotNull(main, ch).joinToString(" · ")
    }

    fun options(
        tracks: Tracks,
        type: Int,
    ): List<Option> {
        var n = 0
        return tracks.groups.flatMapIndexed { gi, g ->
            if (g.type != type) return@flatMapIndexed emptyList()
            (0 until g.length).mapNotNull { ti ->
                if (!g.isTrackSupported(ti)) return@mapNotNull null
                val f = g.getTrackFormat(ti)
                n++
                Option(type, gi, ti, label(f.label, f.language, f.channelCount, n), g.isTrackSelected(ti), g.mediaTrackGroup)
            }
        }
    }

    fun pick(
        player: Player,
        o: Option,
    ) {
        // The tracks may have been republished since the menu opened: find the same group again (or do nothing)
        val group =
            player.currentTracks.groups.getOrNull(o.groupIndex)?.takeIf { it.type == o.type && (o.group == null || it.mediaTrackGroup == o.group) }
                ?: player.currentTracks.groups.firstOrNull { it.type == o.type && o.group != null && it.mediaTrackGroup == o.group }
                ?: return
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(o.type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, o.trackIndex))
                .build()
        JellytipTrace.player("iptv", "track pick type=${o.type} ${o.label}")
    }

    fun subtitlesOff(player: Player) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        JellytipTrace.player("iptv", "subtitles off")
    }

    /**
     * On open: the person's preferred audio / subtitle languages, no picks left over from another title. Returns the
     * parameters before, for the page to put back when it goes (the player is shared with Live TV).
     */
    /** Which page's [applyPrefs] is in force (a page puts the parameters back only while it is its own). */
    @Volatile
    var appliedBy: String? = null

    /** The parameters before the first IPTV page of a chain (copy swaps) changed them. */
    @Volatile
    private var original: androidx.media3.common.TrackSelectionParameters? = null

    /**
     * On open: the person's preferred audio languages, no picks left over from another title. Subtitles are not turned on
     * by language (an English film with English subtitle prefs would always get them): forced tracks still show, and the
     * menu turns them on. Returns the parameters before, for [owner] to put back when its page goes.
     */
    fun applyPrefs(
        player: Player,
        audioLangs: List<String>,
        owner: String,
    ): androidx.media3.common.TrackSelectionParameters {
        // Another IPTV page's settings are in force (copy swap): what goes back later is still the original (Live TV's)
        val before = if (appliedBy != null) original ?: player.trackSelectionParameters else player.trackSelectionParameters
        original = before
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredAudioLanguages(*audioLangs.toTypedArray())
                .build()
        appliedBy = owner
        return before
    }
}

/** The menu's two columns (Audio | Subtitles): plain data, so the layout rules are testable. */
object IptvTrackColumns {
    data class Entry(
        val label: String,
        val selected: Boolean,
        /** The track to pick; null = subtitles Off (when [off]) or a note that can't be picked. */
        val option: IptvTracks.Option? = null,
        val off: Boolean = false,
    ) {
        val pickable get() = option != null || off
    }

    fun audio(audio: List<IptvTracks.Option>): List<Entry> =
        if (audio.isEmpty()) listOf(Entry("This stream has one track", selected = true)) else audio.map { Entry(it.label, it.selected, it) }

    /** Off first, then the stream's subtitle tracks (a note when it has none). */
    fun subtitles(subs: List<IptvTracks.Option>): List<Entry> =
        listOf(Entry("Off", selected = subs.none { it.selected }, off = true)) +
            if (subs.isEmpty()) listOf(Entry("No subtitles in this stream", selected = false)) else subs.map { Entry(it.label, it.selected, it) }

    /** Where focus starts in a column: the current pick, else the first pickable row. */
    fun focusIndex(column: List<Entry>): Int = column.indexOfFirst { it.selected && it.pickable }.takeIf { it >= 0 } ?: column.indexOfFirst { it.pickable }.coerceAtLeast(0)
}

/** The menu: Audio | Subtitles side by side (Left / Right between them), ✓ marks what plays now, Off tops Subtitles. */
@Composable
fun IptvTrackMenu(
    player: Player,
    onDismiss: () -> Unit,
) {
    val tracks = remember(player) { player.currentTracks }
    val audio = remember(tracks) { IptvTrackColumns.audio(IptvTracks.options(tracks, C.TRACK_TYPE_AUDIO)) }
    val subs = remember(tracks) { IptvTrackColumns.subtitles(IptvTracks.options(tracks, C.TRACK_TYPE_TEXT)) }
    var shown by remember { mutableStateOf(true) }
    if (!shown) return
    val dismiss = {
        shown = false
        onDismiss()
    }
    val start = remember { FocusRequester() }
    // One audio track (nothing to pick): start on Subtitles
    val audioFirst = audio.any { it.pickable }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        // Inside the dialog's own window, once its content is laid out (outside, the requester is not attached yet)
        LaunchedEffect(Unit) {
            withFrameNanos { }
            start.tryRequestFocus()
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .width(720.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF151515))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            TrackColumn("Audio", audio, Modifier.weight(1f), focus = start.takeIf { audioFirst }) { e ->
                e.option?.let { IptvTracks.pick(player, it) }
                dismiss()
            }
            TrackColumn("Subtitles", subs, Modifier.weight(1f), focus = start.takeUnless { audioFirst }) { e ->
                if (e.off) IptvTracks.subtitlesOff(player) else e.option?.let { IptvTracks.pick(player, it) }
                dismiss()
            }
        }
    }
}

@Composable
private fun TrackColumn(
    title: String,
    entries: List<IptvTrackColumns.Entry>,
    modifier: Modifier,
    focus: FocusRequester?,
    onPick: (IptvTrackColumns.Entry) -> Unit,
) {
    val first = remember(entries) { IptvTrackColumns.focusIndex(entries) }
    Column(modifier.focusGroup().focusRestorer(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        // Opens scrolled to the current pick, so its row is composed when focus is asked for
        LazyColumn(Modifier.heightIn(max = 360.dp), state = rememberLazyListState(initialFirstVisibleItemIndex = first)) {
            itemsIndexed(entries) { i, e ->
                ListItem(
                    selected = e.selected,
                    enabled = e.pickable,
                    onClick = {
                        JellytipNavGate.markOk()
                        onPick(e)
                    },
                    headlineContent = { Text(e.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (e.selected) Text("✓") },
                    // Notes ("one track", "no subtitles") are not stops for the D-pad
                    modifier = Modifier.focusProperties { canFocus = e.pickable }.then(if (i == first && focus != null) Modifier.focusRequester(focus) else Modifier),
                )
            }
        }
    }
}
