package com.github.damontecres.wholphin.jellytip

import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsHero
import com.github.damontecres.wholphin.jellytip.details.heroOf
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.ui.components.TrailerDialog
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind

/** One small text button in the details button row. */
data class JellytipButton(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
)

/**
 * The bottom-left text block of the details page for a library item: loads the companion extras (and, for admins, the
 * audience) through [JellytipDetailsSource], maps everything to a [JellytipDetailsHero] and draws it with
 * [JellytipDetailsBlockContent].
 *
 * @param buttonRowModifier applied to the buttons row (focus requester, focus tracking); focus group is added here
 * @param onOverview opens the full synopsis; the synopsis line is focusable (one press up from the buttons)
 */
@Composable
fun JellytipDetailsBlock(
    item: BaseItem,
    buttons: List<JellytipButton>,
    trailers: List<Trailer>,
    onTrailer: (Trailer) -> Unit,
    onOverview: () -> Unit,
    buttonRowModifier: Modifier,
    modifier: Modifier = Modifier,
    onFocusWithin: (Boolean) -> Unit = {},
    subtitle: String? = null,
    /** One quiet line under the title / subtitle ("3 seasons  ·  Next: S2 E4"); null (film and episode pages) draws nothing. */
    statusLine: String? = null,
    /** A small pill at the end of the info line ("Library + IPTV" on a split series); null draws none. */
    sourceBadge: String? = null,
    bottomPadding: Dp? = null,
    below: @Composable () -> Unit = {},
    viewModel: JellytipDetailsSource = LocalJellytipDetailsSource.current ?: hiltViewModel<JellytipDetailsViewModel>(),
) {
    val extras by produceState<JellytipExtras?>(null, item.id) {
        value = viewModel.getExtras(item)
    }
    val logoUrl = rememberLogoUrl(item)
    // Premium formats (4K · Dolby Vision · Dolby Atmos) come from the companion's /backdrops answer.
    val premium =
        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.fullNames(
            com.github.damontecres.wholphin.jellytip.cards.JellytipTech
                .of(item.id, item.data.seriesId),
        )
    // Admin only: who requested it and who has watched it, to help decide whether it can go
    val isAdmin by rememberIsJellytipAdmin()
    val audienceLine =
        if (isAdmin) {
            val audience by produceState<JellytipAudience?>(null, item.id) { value = viewModel.getAudience(item) }
            audience?.summary()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    LocalConfiguration.current // a language change re-resolves the "Directed by" string below
    val resources = LocalContext.current.resources
    val hero =
        heroOf(
            item = item,
            extras = extras,
            premium = premium,
            directedBy = { resources.getString(R.string.directed_by, it) },
            logoUrl = logoUrl,
            subtitle = subtitle,
            statusLine = statusLine,
            audienceLine = audienceLine,
            sourceBadge = sourceBadge,
        )
    JellytipDetailsBlockContent(
        hero = hero,
        buttons = buttons,
        trailers = trailers,
        onTrailer = onTrailer,
        onOverview = onOverview,
        buttonRowModifier = buttonRowModifier,
        modifier = modifier,
        onFocusWithin = onFocusWithin,
        bottomPadding = bottomPadding,
        below = below,
    )
}

/**
 * The details text block drawn from a source-neutral [JellytipDetailsHero] only (no view model, no BaseItem): studio
 * logo (or typeset title), subtitle, status line, a quiet info line with file-detail badges, a dim director/country
 * line, a one-line synopsis, a quiet scores line and small text buttons. Sizes come from [JellytipHomeTokens] so home
 * and details stay consistent.
 */
@Composable
fun JellytipDetailsBlockContent(
    hero: JellytipDetailsHero,
    buttons: List<JellytipButton>,
    trailers: List<Trailer>,
    onTrailer: (Trailer) -> Unit,
    onOverview: () -> Unit,
    buttonRowModifier: Modifier,
    modifier: Modifier = Modifier,
    onFocusWithin: (Boolean) -> Unit = {},
    bottomPadding: Dp? = null,
    below: @Composable () -> Unit = {},
) {
    val sizes = rememberJellytipSizes()
    val body =
        MaterialTheme.typography.bodyMedium.copy(
            fontSize = sizes.sp(com.github.damontecres.wholphin.jellytip.JellytipLayout.MIN_TEXT_PX),
            lineHeight = sizes.sp(com.github.damontecres.wholphin.jellytip.JellytipLayout.MIN_TEXT_PX * 1.35f),
            shadow = TEXT_SHADOW,
        )
    val quiet = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {
        Column(
            verticalArrangement = Arrangement.spacedBy(sizes.px(JellytipHomeTokens.DETAILS_GAP_PX)),
            modifier =
                Modifier
                    .onFocusChanged { onFocusWithin(it.hasFocus) }
                    .padding(
                        start = (sizes.px(JellytipHomeTokens.DETAILS_LEFT_PX) - 72.dp).coerceAtLeast(0.dp) + 16.dp,
                        bottom = bottomPadding ?: sizes.px(JellytipHomeTokens.DETAILS_BOTTOM_PX),
                    ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(sizes.px(JellytipHomeTokens.DETAILS_GAP_PX)),
                modifier = Modifier.width(sizes.px(JellytipHomeTokens.DETAILS_WIDTH_PX)),
            ) {
                DetailsTitle(hero, sizes)
                hero.subtitle?.let {
                    Text(
                        text = it,
                        style = body,
                        fontWeight = FontWeight.SemiBold,
                        color = quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                hero.statusLine?.let {
                    Text(
                        text = it,
                        style = body,
                        color = quiet.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Info line: year · rating · runtime · premium formats · genres, then quiet file-detail badges.
                val info = hero.infoText
                val badges = hero.badges
                if (info.isNotEmpty() || badges.isNotEmpty() || hero.sourceBadge != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sizes.px(8f)),
                    ) {
                        if (info.isNotEmpty()) {
                            Text(
                                text = info,
                                style = body,
                                color = quiet.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        badges.forEach { Badge(it, sizes) }
                        hero.sourceBadge?.let { Badge(it, sizes) }
                    }
                }

                // Second dim line: director and country
                hero.dimLine?.let { dim ->
                    Text(
                        text = dim,
                        style = body,
                        color = quiet.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                hero.audienceLine?.let {
                    Text(
                        text = it,
                        style = body,
                        color = quiet.copy(alpha = com.github.damontecres.wholphin.jellytip.JellytipLayout.TEXT_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                hero.overview?.let { overview ->
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    Text(
                        text = overview.replace('\n', ' '),
                        style = body,
                        color = quiet.copy(alpha = if (focused) 1f else 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                    RoundedCornerShape(sizes.px(4f)),
                                ).clickable(interactionSource = source, indication = null, onClick = onOverview),
                    )
                }

                hero.scores?.let {
                    MarkText(
                        text = it,
                        style = body,
                        color = quiet.copy(alpha = 0.85f),
                    )
                }

                ButtonsRow(buttons, trailers, onTrailer, buttonRowModifier, sizes)
            }
            below()
        }
    }
}

@Composable
private fun DetailsTitle(
    hero: JellytipDetailsHero,
    sizes: JellytipSizes,
) {
    val logoUrl = hero.logoUrl
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !logoFailed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = hero.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            colorFilter = JellytipLogoTint.filterForIds(logoUrl, hero.logoStyleIds),
            onError = { logoFailed = true },
            // Tall/narrow logos render tiny and unreadable; typeset the title instead
            onSuccess = { state ->
                val image = state.result.image
                JellytipLogoTint.inspect(logoUrl, image)
                if (image.height > image.width * 0.6f) logoFailed = true
            },
            modifier =
                Modifier
                    .width(sizes.px(JellytipHomeTokens.LOGO_MAX_W_PX))
                    .heightIn(max = sizes.px(JellytipHomeTokens.LOGO_MAX_H_PX)),
        )
    } else {
        Text(
            text = hero.title ?: "",
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = sizes.sp(JellytipHomeTokens.DETAILS_TITLE_PX),
            lineHeight = sizes.sp(JellytipHomeTokens.DETAILS_TITLE_PX * 1.05f),
            style = MaterialTheme.typography.headlineMedium.copy(shadow = TEXT_SHADOW),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Badge(
    text: String,
    sizes: JellytipSizes,
) {
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(sizes.px(6f)))
            .padding(horizontal = sizes.px(8f)),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontSize = sizes.sp(com.github.damontecres.wholphin.jellytip.JellytipLayout.MIN_TEXT_PX),
            maxLines = 1,
        )
    }
}

@Composable
private fun ButtonsRow(
    buttons: List<JellytipButton>,
    trailers: List<Trailer>,
    onTrailer: (Trailer) -> Unit,
    rowModifier: Modifier,
    sizes: JellytipSizes,
) {
    val first = remember { FocusRequester() }
    val firstIndex = remember(buttons) { buttons.indexOfFirst { it.primary }.coerceAtLeast(0) }
    // Trailer plays directly: the local one if there is one, else the first remote one. No chooser.
    val trailer = remember(trailers) { trailers.filterIsInstance<LocalTrailer>().firstOrNull() ?: trailers.firstOrNull() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(sizes.px(16f)),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            rowModifier
                .focusGroup()
                .focusRestorer(first)
                .padding(top = sizes.px(6f)),
    ) {
        buttons.forEachIndexed { index, b ->
            // Trailer sits before the last button (More)
            if (index == buttons.lastIndex && trailer != null) {
                TextButton(
                    button =
                        JellytipButton(
                            stringResource(R.string.jellytip_trailer),
                            onClick = { onTrailer(trailer) },
                        ),
                    sizes = sizes,
                )
            }
            TextButton(
                button = b,
                sizes = sizes,
                modifier = if (index == firstIndex) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

@Composable
private fun TextButton(
    button: JellytipButton,
    sizes: JellytipSizes,
    modifier: Modifier = Modifier,
) {
    val cream = Color(0xFFF2F2F0)
    val ink = Color(0xFF0A0A0A)
    Button(
        onClick = button.onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(sizes.px(4f))),
        scale = ButtonDefaults.scale(focusedScale = JellytipFocus.SCALE),
        border =
            ButtonDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, cream), shape = RoundedCornerShape(sizes.px(4f))),
            ),
        glow = ButtonDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.45f), 12.dp)),
        contentPadding = PaddingValues(horizontal = sizes.px(22f), vertical = sizes.px(8f)),
        colors =
            if (button.primary) {
                ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White,
                    focusedContainerColor = cream,
                    focusedContentColor = ink,
                )
            } else {
                ButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = cream.copy(alpha = 0.85f),
                    focusedContainerColor = cream,
                    focusedContentColor = ink,
                )
            },
    ) {
        Text(
            text = button.label,
            fontFamily = JellytipFontFamily,
            fontSize = sizes.sp(com.github.damontecres.wholphin.jellytip.JellytipLayout.MIN_TEXT_PX),
            fontWeight = if (button.primary) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Soft shadow under every hero/details text line (about 0 1dp 6dp black at 60% at 1080p on a 2x TV). */
private val TEXT_SHADOW = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, 2f), 12f)
