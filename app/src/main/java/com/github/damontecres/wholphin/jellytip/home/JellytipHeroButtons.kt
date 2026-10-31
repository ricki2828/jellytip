package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipFocus
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/**
 * The hero's own buttons (Ricki 31-Oct: the hero item couldn't be selected): Play (resume when started; a series
 * opens its page, where Play picks the next episode) and More info. They sit at the bottom right of the hero, above
 * the rows; Up from the first row lands here. [modifier] carries the page's focus wiring.
 */
@Composable
fun JellytipHeroButtons(
    item: BaseItem,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resume = item.resumeMs > 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        HeroButton(if (!item.type.playable) "▶  Watch" else if (resume) "▶  Resume" else "▶  Play", primary = true, onClick = onPlay)
        HeroButton("More info", primary = false, onClick = onInfo)
    }
}

@Composable
private fun HeroButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val cream = Color(0xFFF2F2F0)
    val ink = Color(0xFF0A0A0A)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
        scale = ButtonDefaults.scale(focusedScale = JellytipFocus.SCALE),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, cream), shape = RoundedCornerShape(4.dp))),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        colors =
            ButtonDefaults.colors(
                containerColor = if (primary) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
                focusedContainerColor = cream,
                focusedContentColor = ink,
            ),
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
