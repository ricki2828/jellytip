package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup

/**
 * "Recording is using your stream" pop-up (plan "Stop recording + recording-is-using-your-stream pop-up"): Play came
 * back 409 because the one connection is held. A recording offers **Stop recording & watch** (cancel, ~3 s, play
 * again) or **Keep recording**; another viewer gets the same pop-up without the stop button. An old server sends no
 * holders, so nothing pops and the on-screen message stays as before.
 */
@Composable
fun StreamBusyPopup(
    state: IptvPlayState,
    playback: JellytipIptvPlayback,
) {
    val full = state as? IptvPlayState.Full ?: return
    var dismissed by remember(full) { mutableStateOf(false) }
    if (dismissed) return
    val holder = full.holders.firstOrNull { it.isRecording && it.recordingId != null } ?: full.holders.firstOrNull() ?: return
    val until = holder.endsAt?.let { " until ${hhmm(it)}" }.orEmpty()
    val recordingId = holder.recordingId?.takeIf { holder.isRecording }
    val title =
        if (holder.isRecording) {
            "Recording “${holder.title ?: holder.label ?: "a programme"}” is using your stream$until."
        } else {
            val who = holder.userName ?: "someone"
            val what = (holder.title ?: holder.label)?.let { " (watching $it)" }.orEmpty()
            "Viewer $who$what is using your stream$until."
        }
    val items =
        buildList {
            if (recordingId != null) {
                add(DialogItem("Stop recording & watch", dismissOnClick = true) { playback.stopRecordingAndPlay(full.target, recordingId) })
                add(DialogItem("Keep recording", dismissOnClick = true) { dismissed = true })
            } else {
                add(DialogItem("OK", dismissOnClick = true) { dismissed = true })
            }
        }
    DialogPopup(showDialog = true, title = title, dialogItems = items, onDismissRequest = { dismissed = true })
}

/** REC sheet title: a running recording shows its padded end ("recording until 21:42"), anything else [default]. */
fun recordingSheetTitle(
    r: IptvRecording,
    default: String,
): String = if (r.state == "recording") "${r.title}  ·  recording until ${hhmm(r.stop)}" else default

/**
 * REC sheet actions (plan "Stop recording"): a running one gets Watch from the start (when the growing file plays),
 * **Stop recording now (keeps what's recorded)** and **Keep recording**; a scheduled one gets Cancel recording.
 */
fun recordingSheetItems(
    r: IptvRecording,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
): List<DialogItem> =
    when (r.state) {
        "recording" ->
            listOfNotNull(
                if (r.playable) DialogItem("Watch from the start", dismissOnClick = true, onClick = onPlay) else null,
                DialogItem("Stop recording now (keeps what's recorded)", dismissOnClick = true, onClick = onCancel),
                DialogItem("Keep recording", dismissOnClick = true) {},
            )

        "scheduled" -> listOf(DialogItem("Cancel recording", dismissOnClick = true, onClick = onCancel))

        else -> emptyList()
    }
