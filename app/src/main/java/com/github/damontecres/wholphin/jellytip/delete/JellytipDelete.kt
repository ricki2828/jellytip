package com.github.damontecres.wholphin.jellytip.delete

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.io.File
import java.time.Instant

/** Human label for the delete dialogs: "Title (year)", or "Show - S1:E3 - Episode" for episodes. */
fun BaseItem.jellytipDeleteLabel(): String {
    val t = title ?: name ?: "this item"
    return when (type) {
        BaseItemKind.EPISODE -> {
            val se = data.seasonEpisodeLabel()
            listOfNotNull(t, se, name?.takeIf { it != t }).filter { it.isNotBlank() }.joinToString(" - ")
        }

        else -> data.productionYear?.let { "$t ($it)" } ?: t
    }
}

private fun org.jellyfin.sdk.model.api.BaseItemDto.seasonEpisodeLabel(): String? {
    val s = parentIndexNumber
    val e = indexNumber
    return if (s != null && e != null) "S$s:E$e" else null
}

/** Records every delete (title, id, time) to the app log and to filesDir/jellytip-deletes.log. */
object JellytipDeleteLog {
    fun record(item: BaseItem) {
        val line = "title=\"${item.jellytipDeleteLabel()}\" id=${item.id} at=${Instant.now()}"
        Timber.w("JELLYTIP_DELETE %s", line)
        try {
            File(WholphinApplication.instance.filesDir, "jellytip-deletes.log").appendText("$line\n")
        } catch (ex: Exception) {
            Timber.w(ex, "Jellytip: could not write delete log")
        }
    }
}

/**
 * Two-step permanent-delete confirmation. Focus starts on Cancel in both dialogs so a stray OK press
 * can never delete anything.
 */
@Composable
fun JellytipDeleteConfirm(
    itemLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    elevation: Dp = 3.dp,
    item: BaseItem? = null,
) {
    var step by remember { mutableStateOf(1) }
    // Admin-only context for the decision: who requested it, who has watched it
    val audience by produceState<String?>(null, item?.id) {
        value = item?.let { JellytipApi.shared?.getAudience(it)?.summary() }
    }
    BasicDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = elevation,
    ) {
        val cancelFocus = remember(step) { FocusRequester() }
        LaunchedEffect(step) { cancelFocus.tryRequestFocus() }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp),
        ) {
            if (step == 1) {
                Text(
                    text = stringResource(R.string.jellytip_delete_first, itemLabel),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.jellytip_delete_second_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Red.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.jellytip_delete_second, itemLabel),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            audience?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
                Button(
                    onClick = { if (step == 1) step = 2 else onConfirm() },
                    colors =
                        ButtonDefaults.colors(
                            containerColor = Color.Red.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Red.copy(alpha = 0.8f),
                        ),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (step == 1) R.string.jellytip_delete_continue else R.string.jellytip_delete_forever,
                            ),
                    )
                }
            }
        }
    }
}
