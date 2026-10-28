package com.github.damontecres.wholphin.ui.detail.series

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.jellytip.JellytipButton
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBackground
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBlock
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.BannerCardWithTitle
import com.github.damontecres.wholphin.ui.cards.ExtrasRow
import com.github.damontecres.wholphin.ui.cards.PersonRow
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabRow
import com.github.damontecres.wholphin.ui.formatSeasonNumber
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.logTab
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.ui.util.rememberDelayedNestedScroll
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

@Composable
fun SeriesOverviewContent(
    preferences: UserPreferences,
    series: BaseItem,
    seasons: List<BaseItem?>,
    episodes: EpisodeList,
    seasonExtras: List<ExtrasItem>,
    chosenStreams: ChosenStreams?,
    peopleInEpisode: List<Person>,
    position: SeriesOverviewPosition,
    firstItemFocusRequester: FocusRequester,
    episodeRowFocusRequester: FocusRequester,
    castCrewRowFocusRequester: FocusRequester,
    guestStarRowFocusRequester: FocusRequester,
    extrasRowFocusRequester: FocusRequester,
    onChangeSeason: (Int) -> Unit,
    onFocusEpisode: (Int) -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (BaseItem) -> Unit,
    playOnClick: (Duration) -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    overviewOnClick: () -> Unit,
    personOnClick: (Person) -> Unit,
    canDelete: (BaseItem) -> Boolean,
    onConfirmDelete: (BaseItem) -> Unit,
    onClickExtra: (Int, ExtrasItem) -> Unit,
    onChooseVersion: (BaseItem, MediaSourceInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var selectedTabIndex by rememberSaveable(position.seasonTabIndex) { mutableIntStateOf(position.seasonTabIndex) }
    LaunchedEffect(selectedTabIndex) {
        logTab("series_overview", selectedTabIndex)
    }
    val tabRowFocusRequester = remember { FocusRequester() }

    val focusedEpisode =
        (episodes as? EpisodeList.Success)?.episodes?.getOrNull(position.episodeRowIndex)
    var pageHasFocus by remember { mutableStateOf(false) }
    var cardRowHasFocus by remember { mutableStateOf(false) }
    val dimming by animateFloatAsState(if (pageHasFocus && !cardRowHasFocus) .4f else 1f)

    val scrollState = rememberScrollState()
    val scrollConnection = rememberDelayedNestedScroll()
    var requestFocusAfterSeason by remember { mutableStateOf(false) }

    val tabFocusRequesters = remember(seasons) { List(seasons.size) { FocusRequester() } }
    val contentFocusRequesters = remember(seasons) { List(seasons.size) { FocusRequester() } }
    val tabs =
        seasons.mapIndexed { index, season ->
            TabDetails(
                title =
                    StringStringProvider(
                        season?.name
                            ?: season?.data?.indexNumber?.let { formatSeasonNumber(it) }
                            ?: "",
                    ),
                tabFocusRequester = tabFocusRequesters[index],
                contentFocusRequester = contentFocusRequesters[index],
            )
        }

    val currentOnChangeSeason by rememberUpdatedState(onChangeSeason)

    val sizes = rememberJellytipSizes()
    var topFocused by remember { mutableStateOf(true) }
    val headerAlpha by animateFloatAsState(if (topFocused) 1f else 0f, label = "jellytip-overview-header")
    val resume = focusedEpisode?.data?.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO
    val episodeText =
        focusedEpisode?.let { ep ->
            listOfNotNull(
                ep.data.parentIndexNumber?.let { "S$it" },
                ep.data.indexNumber?.let { "E$it" },
            ).joinToString(" ").let { code ->
                listOf(code, ep.name ?: "").filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            }
        }

    JellytipHomeTheme {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            JellytipDetailsBackground(series, emptyList(), false, dimmed = pageHasFocus && !topFocused)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .nestedScroll(scrollConnection)
                        .verticalScroll(scrollState)
                        .onFocusChanged { pageHasFocus = it.hasFocus },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .height(screenHeight)
                            .graphicsLayer { alpha = headerAlpha }
                            .onFocusChanged { topFocused = it.hasFocus }
                            .focusGroup()
                            .bringIntoViewRequester(bringIntoViewRequester),
                ) {
                    val paddingValues =
                        remember(preferences.appPreferences.interfacePreferences.showClock) {
                            if (preferences.appPreferences.interfacePreferences.showClock) {
                                PaddingValues(start = 0.dp, end = 184.dp)
                            } else {
                                PaddingValues(start = 0.dp, end = 16.dp)
                            }
                        }
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        tabs = tabs,
                        onClick =
                            remember {
                                {
                                    selectedTabIndex = it
                                    currentOnChangeSeason(it)
                                    requestFocusAfterSeason = true
                                }
                            },
                        modifier =
                            Modifier
                                .focusRequester(tabRowFocusRequester)
                                .padding(paddingValues)
                                .padding(bottom = 4.dp)
                                .fillMaxWidth(),
                    )
                    JellytipDetailsBlock(
                        item = focusedEpisode ?: series,
                        subtitle = episodeText,
                        buttons =
                            buildList {
                                add(
                                    JellytipButton(
                                        stringResource(if (resume > Duration.ZERO) R.string.resume else R.string.play),
                                        primary = true,
                                        onClick = { playOnClick.invoke(resume) },
                                    ),
                                )
                                add(
                                    JellytipButton(
                                        stringResource(
                                            if (focusedEpisode?.data?.userData?.isFavorite == true) {
                                                R.string.remove_favorite
                                            } else {
                                                R.string.add_favorite
                                            },
                                        ),
                                        favoriteOnClick,
                                    ),
                                )
                                add(JellytipButton(stringResource(R.string.jellytip_more), moreOnClick))
                            },
                        trailers = emptyList(),
                        onTrailer = {},
                        onOverview = overviewOnClick,
                        buttonRowModifier = Modifier,
                        bottomPadding = 24.dp,
                        below = {
                            when (val eps = episodes) {
                                EpisodeList.Loading -> {
                                }

                                is EpisodeList.Error -> {
                                    ErrorMessage(eps.message, eps.exception)
                                }

                                is EpisodeList.Success -> {
                                    if (requestFocusAfterSeason) {
                                        LaunchedEffect(Unit) {
                                            firstItemFocusRequester.tryRequestFocus()
                                            requestFocusAfterSeason = false
                                        }
                                    }
                                    val state = rememberLazyListState(position.episodeRowIndex)
                                    var epPosition by rememberInt(position.episodeRowIndex)
                                    LazyRow(
                                        state = state,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        modifier =
                                            Modifier
                                                .focusRestorer(episodeRowFocusRequester)
                                                .onFocusChanged {
                                                    cardRowHasFocus = it.hasFocus
                                                },
                                    ) {
                                        itemsIndexed(eps.episodes) { episodeIndex, episode ->
                                            val interactionSource = remember { MutableInteractionSource() }
                                            if (interactionSource.collectIsFocusedAsState().value) {
                                                onFocusEpisode.invoke(episodeIndex)
                                            }
                                            BannerCardWithTitle(
                                                title =
                                                    episode?.let {
                                                        listOfNotNull(it.data.indexNumber?.let { n -> "E$n" }, it.name)
                                                        .joinToString(" \u00B7 ")
                                                    },
                                                subtitle = episode?.data?.overview?.replace('\n', ' '),
                                                item = episode,
                                                aspectRatio =
                                                    episode
                                                        ?.aspectRatio
                                                        ?.coerceAtLeast(AspectRatios.FOUR_THREE)
                                                        ?: (AspectRatios.WIDE),
                                                played = episode?.data?.userData?.played ?: false,
                                                playPercent =
                                                    episode?.data?.userData?.playedPercentage
                                                        ?: 0.0,
                                                onClick = {
                                                    epPosition = episodeIndex
                                                    if (episode != null) onClick.invoke(episode)
                                                },
                                                onLongClick = {
                                                    epPosition = episodeIndex
                                                    if (episode != null) onLongClick.invoke(episode)
                                                },
                                                modifier =
                                                    Modifier
                                                        .ifElse(
                                                            episodeIndex == position.episodeRowIndex,
                                                            Modifier
                                                                .focusRequester(firstItemFocusRequester),
                                                        ).ifElse(
                                                            episodeIndex == epPosition,
                                                            Modifier.focusRequester(episodeRowFocusRequester),
                                                        ).onFocusChanged {
                                                            if (it.isFocused) {
                                                                scope.launch {
                                                                    bringIntoViewRequester.bringIntoView()
                                                                }
                                                            }
                                                        }.onKeyEvent {
                                                            if (episode != null && isPlayKeyUp(it)) {
                                                                onClick.invoke(episode)
                                                                return@onKeyEvent true
                                                            }
                                                            return@onKeyEvent false
                                                        },
                                                interactionSource = interactionSource,
                                                cardHeight = sizes.px(150f),
                                                useSeriesForPrimary = false,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                val (guestStars, castAndCrew) =
                    remember(peopleInEpisode) {
                        peopleInEpisode.partition { it.type == PersonKind.GUEST_STAR }
                    }

                AnimatedVisibility(
                    visible = peopleInEpisode.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (castAndCrew.isNotEmpty()) {
                            PersonRow(
                                title = R.string.cast_and_crew,
                                people = castAndCrew,
                                onClick = personOnClick,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(castCrewRowFocusRequester),
                            )
                        }
                        if (guestStars.isNotEmpty()) {
                            PersonRow(
                                title = R.string.guest_stars,
                                people = guestStars,
                                onClick = personOnClick,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(guestStarRowFocusRequester),
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = seasonExtras.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ExtrasRow(
                        extras = seasonExtras,
                        onClickItem = onClickExtra,
                        onLongClickItem = { _, _ -> },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(extrasRowFocusRequester),
                    )
                }
            }
        }
    }
}
