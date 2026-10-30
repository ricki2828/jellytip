package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay

/**
 * The reminder nudge (plan "Remind me"): a non-blocking banner at the top for 15 s when a reminder turns `soon` and
 * again at kick-off. Focus stays where it is; while it shows, OK = Watch and Back dismisses ([JellytipReminders.onKey],
 * wired at the app root).
 */
@Composable
fun JellytipReminderBanner(
    reminders: JellytipReminders,
    modifier: Modifier = Modifier,
) {
    val current by reminders.nudge.collectAsState()
    val nudge = current ?: return
    LaunchedEffect(nudge) {
        delay(BANNER_MS)
        reminders.dismissNudge(nudge)
    }
    Box(modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (nudge.live) KindPill("live") else SoonPill()
            Text(
                nudge.text(nowSec()),
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "OK  Watch",
                color = Color.Black,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private const val BANNER_MS = 15_000L
