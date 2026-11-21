package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
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
                ?.takeIf { it.isNotBlank() && it != "und" && it != C.LANGUAGE_UNDETERMINED }
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
                Option(type, gi, ti, label(f.label, f.language, f.channelCount, n), g.isTrackSelected(ti))
            }
        }
    }

    fun pick(
        player: Player,
        o: Option,
    ) {
        val group = player.currentTracks.groups.getOrNull(o.groupIndex) ?: return
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
    fun applyPrefs(
        player: Player,
        audioLangs: List<String>,
        subtitleLangs: List<String>,
    ): androidx.media3.common.TrackSelectionParameters {
        val before = player.trackSelectionParameters
        player.trackSelectionParameters =
            before
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredAudioLanguages(*audioLangs.toTypedArray())
                .setPreferredTextLanguages(*subtitleLangs.toTypedArray())
                .build()
        return before
    }
}

/** The menu: audio tracks, then Off and the subtitle tracks; ✓ marks what plays now. */
@Composable
fun IptvTrackMenu(
    player: Player,
    onDismiss: () -> Unit,
) {
    val tracks = remember(player) { player.currentTracks }
    val audio = remember(tracks) { IptvTracks.options(tracks, C.TRACK_TYPE_AUDIO) }
    val subs = remember(tracks) { IptvTracks.options(tracks, C.TRACK_TYPE_TEXT) }
    var shown by remember { mutableStateOf(true) }
    val subsOff = subs.none { it.selected }
    val items =
        buildList {
            if (audio.isEmpty()) add(DialogItem("Audio: this stream has one track", dismissOnClick = true) {})
            audio.forEach { o -> add(DialogItem("Audio: ${o.label}" + if (o.selected) "  ✓" else "", dismissOnClick = true) { IptvTracks.pick(player, o) }) }
            add(DialogItem("Subtitles: Off" + if (subsOff) "  ✓" else "", dismissOnClick = true) { IptvTracks.subtitlesOff(player) })
            if (subs.isEmpty()) add(DialogItem("No subtitles in this stream", dismissOnClick = true) {})
            subs.forEach { o -> add(DialogItem("Subtitles: ${o.label}" + if (o.selected) "  ✓" else "", dismissOnClick = true) { IptvTracks.pick(player, o) }) }
        }
    if (shown) {
        DialogPopup(
            showDialog = true,
            title = "Audio & subtitles",
            dialogItems = items,
            onDismissRequest = {
                shown = false
                onDismiss()
            },
            dismissOnClick = true,
        )
    }
}
