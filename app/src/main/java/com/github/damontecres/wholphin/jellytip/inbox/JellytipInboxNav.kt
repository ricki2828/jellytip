package com.github.damontecres.wholphin.jellytip.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.nav.jellytipSelectedBar
import com.github.damontecres.wholphin.ui.nav.navItemColor

/** Badge text: nothing at 0, the count up to 9, then "9+" (the web's nav badge, sized for a rail icon). */
fun inboxBadge(unread: Int): String? =
    when {
        unread <= 0 -> null
        unread > 9 -> "9+"
        else -> unread.toString()
    }

/**
 * Inbox in the nav rail (web Nav.tsx de17f4d4): a bell with the unread count, visible with the drawer shut too.
 * The count follows [JellytipInboxService.store], so reading the Inbox (markRead) clears it straight away.
 */
@Composable
fun NavigationDrawerScope.JellytipInboxNavItem(
    service: JellytipInboxService,
    /** The signed-in person: the badge only counts their store, so a profile switch never shows the last person's count. */
    userId: String,
    selected: Boolean,
    drawerOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    LaunchedEffect(service, userId) { service.current() } // load this person's saved store before the first look
    val store by service.store.collectAsState()
    val unread = store?.takeIf { it.userId == userId }?.let { Inbox.unread(it, System.currentTimeMillis()) } ?: 0
    val badge = inboxBadge(unread)
    val focused by interactionSource.collectIsFocusedAsState()
    val color = navItemColor(selected, focused, drawerOpen)
    val sizes = rememberJellytipSizes()
    NavigationDrawerItem(
        modifier =
            modifier
                .jellytipSelectedBar(selected)
                .semantics { contentDescription = if (unread > 0) "Inbox, $unread unread" else "Inbox" },
        selected = false,
        onClick = onClick,
        leadingContent = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.fa_bell),
                    textAlign = TextAlign.Center,
                    fontSize = sizes.sp(28),
                    fontFamily = FontAwesome,
                    color = color,
                )
                if (badge != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                .background(InboxBadgeColor, CircleShape)
                                .padding(horizontal = 3.dp),
                    ) {
                        Text(
                            text = badge,
                            color = Color(0xFF14121A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        supportingContent =
            if (unread > 0) {
                { Text(text = "$unread new", maxLines = 1) }
            } else {
                null
            },
        interactionSource = interactionSource,
    ) {
        Text(text = "Inbox", color = color, maxLines = 1)
    }
}

/** Pastel accent (dark canvas, one pastel hero). */
private val InboxBadgeColor = Color(0xFFF5B8C8)
