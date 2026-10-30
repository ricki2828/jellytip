package com.github.damontecres.wholphin.jellytip.iptv

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.github.damontecres.wholphin.jellytip.JellytipUndo
import com.github.damontecres.wholphin.jellytip.me.JellytipMe
import com.github.damontecres.wholphin.jellytip.me.SportMuted
import com.github.damontecres.wholphin.ui.components.DialogItem

/*
 * "Not interested" on sport tiles (~/ai/plans/jellytip-live-sport.md, "Learn sport taste"): the tile menu offers
 * "Not interested in ‹league›" / "‹sport›", saved to prefs sport.muted, with an undo notice. The server drops muted
 * events from live sport, upcoming and reminders; the rows here hide them at once.
 */

/** Menu items for a sport tile; none when prefs are unavailable or the server named neither league nor sport. */
@Composable
fun rememberSportMuteItems(
    league: String?,
    sport: String?,
): List<DialogItem> {
    val available by JellytipMe.available.collectAsState()
    val context = LocalContext.current
    if (!available) return emptyList()
    fun item(
        kind: String,
        value: String,
    ) = DialogItem("Not interested in $value", dismissOnClick = true) {
        JellytipMe.setSportMuted(kind, value, true) { ok ->
            if (ok) {
                JellytipUndo.show("Not interested in $value") { JellytipMe.setSportMuted(kind, value, false) }
            } else {
                Toast.makeText(context, "Couldn't save that. Try again later.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return listOfNotNull(
        league?.takeIf { it.isNotBlank() }?.let { item("leagues", it) },
        sport?.takeIf { it.isNotBlank() && !it.equals(league, ignoreCase = true) }?.let { item("sports", it) },
    )
}

/** The person's muted sports and leagues (empty until prefs load). */
@Composable
fun rememberSportMuted(): SportMuted {
    val prefs by JellytipMe.prefs.collectAsState()
    return prefs?.sportMuted ?: SportMuted()
}
