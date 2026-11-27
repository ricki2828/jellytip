@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.jellytipEnter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.SubtitleView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JellytipLiveTvPlayerViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: com.github.damontecres.wholphin.services.NavigationManager,
        val reminders: JellytipReminders,
        val specials: com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialsService,
        val kickoff: JellytipKickoff,
        /** Next episode in the IPTV player (t5n 22-Nov): the series' seasons, the library's copy, the library's countdown. */
        val vod: JellytipIptvVodService,
        val jellyfin: org.jellyfin.sdk.api.client.ApiClient,
        val serverRepository: com.github.damontecres.wholphin.data.ServerRepository,
        val appPreferences: androidx.datastore.core.DataStore<com.github.damontecres.wholphin.preferences.AppPreferences>,
    ) : ViewModel()

private const val ZAP_SETTLE_MS = 700L
private const val BANNER_MS = 5_000L
private const val STALL_MS = 10_000L
private const val FLIP_POLL_MS = 30_000L
private const val LIVE_OPEN_POLL_MS = 15_000L
private const val FANTASY_POLL_MS = 60_000L
private const val FLIP_TOAST_MS = 6_000L
private const val TOAST_GAP_MS = 250L
private const val MAX_QUEUED_TOASTS = 3
private const val PRE_CARD_MS = 10_000L
private const val IDLE_RETUNE_MS = 600L
private val TOAST_MINE = Color(0xFF22C55E)
private val TOAST_OPP = Color(0xFFE5534B)
private val IMAGE_SUBTITLE_MIMES = setOf("application/pgs", "application/dvbsubs", "application/vobsub")

private data class IptvTrackChoice(val group: Tracks.Group, val index: Int) {
    val format get() = group.getTrackFormat(index)
    val selected get() = group.isTrackSelected(index)
}

private fun Tracks.choices(type: Int): List<IptvTrackChoice> =
    groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            val format = group.getTrackFormat(index)
            val mime = format.sampleMimeType
            val imageSubtitle = mime?.startsWith("image/") == true || mime in IMAGE_SUBTITLE_MIMES
            if (group.isTrackSupported(index) && !imageSubtitle) IptvTrackChoice(group, index) else null
        }
    }

private fun IptvTrackChoice.label(): String =
    listOfNotNull(
        format.label?.takeIf { it.isNotBlank() },
        format.language?.takeUnless { it == "und" }?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { it } },
        format.codecs?.takeIf { it.isNotBlank() } ?: format.sampleMimeType?.substringAfter('/'),
        format.channelCount.takeIf { it > 0 }?.let { "$it ch" },
        format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" },
    ).distinct().joinToString(" · ").ifBlank { "Track ${index + 1}" }

/**
 * A top-right player toast, a Broadcast lower third (plan "In-match visual direction"): a colour edge (the team for a
 * flip or moment, green for your player, red for your opponent's), an icon block, then a kicker, the main line in
 * condensed caps and a sub line.
 */
data class PlayerToast(
    val main: String,
    val kicker: String? = null,
    val sub: String? = null,
    val edge: Color = Broadcast.Gold,
    val kickerColor: Color = Broadcast.Gold,
    val icon: androidx.compose.ui.graphics.vector.ImageVector = BroadcastIcons.Odds,
)

/**
 * An IPTV channel full screen. Up/Down zap through the guide tab it was opened from: the banner follows at once, the
 * stream is only asked for once the choice settles (each stream is the provider's one connection). OK shows the
 * now/next banner. Back returns to the guide, which lands on (and keeps playing) this channel.
 */
@Composable
fun JellytipLiveTvPlayerPage(
    channelId: String,
    /** Game Centre live launch: the match this channel is showing (companion + scorebug without a guide fixture). */
    matchId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: JellytipLiveTvPlayerViewModel = hiltViewModel(),
) {
    val playback = viewModel.playback
    val playState by playback.state.collectAsState()
    val lineup =
        remember(channelId) {
            playback.lineup.takeIf { l -> l.any { it.id == channelId } }
                ?: listOf(IptvChannel(channelId, channelId.substringBefore(':'), null, channelId, null, "", null, 0, false))
        }
    val startIndex = remember(channelId) { lineup.indexOfFirst { it.id == channelId }.coerceAtLeast(0) }
    var index by remember { mutableIntStateOf(startIndex) }
    // What the guide asked for (live, or a catch-up programme of this channel); zapping away switches to live
    val opened = remember(channelId) { playback.state.value.target?.takeIf { it.channelId == channelId } }
    var current by remember { mutableStateOf(opened ?: IptvTarget(lineup[startIndex].id, lineup[startIndex].name)) }
    var bannerTick by remember { mutableIntStateOf(1) }
    var bannerVisible by remember { mutableStateOf(true) }
    var bannerArmed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Same-event alternate in use after the lineup channel's stream failed (plan §4); null = the lineup channel
    var fallback by remember { mutableStateOf<IptvChannel?>(null) }
    val tried = remember { mutableSetOf<String>() }
    // Epoch s when this page opened: a catch-up ending after it was a programme still on (kick-off)
    val startedAt = remember { nowSec() - 60 }
    val channel = fallback ?: lineup[index]

    // One owner per page: a page swapped in (closer game) must not lose the stream when the old one detaches
    val playerOwner = remember { "full#" + System.nanoTime() }
    DisposableEffect(Unit) {
        playback.attach(playerOwner)
        playback.setVolume(1f)
        onDispose {
            playback.lastFullScreenChannel = (fallback ?: lineup[index]).id
            playback.detach(playerOwner)
        }
    }
    // App hidden = JellytipIptvPlayback's activity callback stops the stream; no nav-entry ON_STOP stop here (it also
    // fires on Back, while the guide is meant to keep this channel playing)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.play(current, "app shown (full screen)") }
    // Never sit on "Tuning" with nothing asked for: if the stream is stopped while this page is showing (by anything),
    // ask for the current channel again
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playState is IptvPlayState.Idle) {
        if (playState !is IptvPlayState.Idle) return@LaunchedEffect
        delay(IDLE_RETUNE_MS)
        if (playback.state.value !is IptvPlayState.Idle || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        JellytipTrace.player("iptv", "full screen idle while shown: retune ${current.channelId}")
        playback.play(current, "full screen retune after stop")
    }

    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    // Zap: open the stream once the choice has settled
    LaunchedEffect(index) {
        val ch = lineup[index]
        fallback = null
        tried.clear()
        tried += ch.id
        val target = if (index == startIndex && opened != null) opened else IptvTarget(ch.id, ch.name)
        current = target
        bannerArmed = false
        if (playback.state.value.target != target) delay(ZAP_SETTLE_MS)
        playback.play(target, "full screen settle")
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    /** Same event on its next-best channel (each alternate once per zap), with a toast; false = none left. */
    fun failover(why: String): Boolean {
        val base = lineup[index]
        val next = playback.alternates[base.id].orEmpty().firstOrNull { it.id !in tried } ?: return false
        tried += next.id
        JellytipTrace.player("iptv", "full screen ${current.channelId} $why: alternate ${next.id} ${next.name}")
        fallback = next
        val target = IptvTarget(next.id, next.name)
        current = target
        bannerTick++
        playback.play(target, "alternate after $why")
        val name = next.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
        android.widget.Toast.makeText(context, "Switched to $name", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }
    // The stream failed: try the same event on its next-best channel
    LaunchedEffect(playState) {
        val failed = playState as? IptvPlayState.Failed ?: return@LaunchedEffect
        if (failed.target != current || !current.isLive) return@LaunchedEffect
        failover("failed")
    }
    // From kick-off: the catch-up ends where the button was pressed (server: stop = press time); then back to live.
    // A programme opened while it was still on (tile "From kick-off") counts as kick-off too.
    LaunchedEffect(current) {
        val t = current
        if (t.isLive || t.start == null || (t.stop ?: 0L) < startedAt) return@LaunchedEffect
        // Only a window that played: one that ends before ever playing is the catch-up give-up effect's (honest toast)
        var played = false
        while (true) {
            delay(1_000)
            val p = playback.player ?: continue
            if (playback.state.value.let { it is IptvPlayState.Playing && it.target == t }) played = true
            if (played && playback.state.value.target == t && playback.holds(t) && p.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                JellytipTrace.player("iptv", "kick-off caught up ${t.channelId}: back to live")
                current = IptvTarget(t.channelId, channel.name)
                playback.play(current, "kick-off ended: back to live")
                bannerTick++
                android.widget.Toast.makeText(context, "Back to live", android.widget.Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
        }
    }
    // A live stream that stalls (buffering while meant to play) for 10 s is treated like a failure (plan "8h Block 3")
    LaunchedEffect(current) {
        if (!current.isLive) return@LaunchedEffect
        var stalledSince = 0L
        while (true) {
            delay(1_000)
            val p = playback.player ?: continue
            val playing = playback.state.value is IptvPlayState.Playing && playback.state.value.target == current
            if (playing && p.playWhenReady && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - stalledSince >= STALL_MS) {
                    if (failover("stalled 10 s")) return@LaunchedEffect
                    stalledSince = System.currentTimeMillis() // nothing to switch to: keep waiting
                }
            } else {
                stalledSince = 0L
            }
        }
    }
    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
        bannerArmed = false
    }
    val nowNext by produceState<IptvNowNext?>(null, channel.id) {
        value = null
        value = viewModel.service.epgNowFor(listOf(channel.id))[channel.id]
    }
    val now by produceState(nowSec(), channel.id) {
        while (true) {
            value = nowSec()
            delay(15_000)
        }
    }

    // Pre-game (plan "Pre-game state"): the channel is on but the event hasn't started. A card bottom-left says when;
    // it hides after 10 s, any key brings it back, OK on it offers Remind me / other channels; "Started" at startsAt.
    val preProg = nowNext?.now?.takeIf { it.phase == "pre" && (it.startsAt ?: it.start) > now }
    val preStartsAt = preProg?.let { it.startsAt ?: it.start }
    var preCardTick by remember { mutableIntStateOf(0) }
    var preCardVisible by remember { mutableStateOf(false) }
    var preMenu by remember { mutableStateOf(false) }
    var matchModal by remember { mutableStateOf<IptvMatch?>(null) }
    // In-event panel (plan "In-event panel"): Right on a live key match opens it over the right of the video
    var panelOpen by remember { mutableStateOf(false) }
    // Adjust delay sheet and channel chips (declared here: openTrackSheet reads them)
    var delaySheetOpen by remember { mutableStateOf(false) }
    var delayCursor by remember { mutableIntStateOf(0) }
    var delayNote by remember { mutableStateOf<String?>(null) }
    var chipsOpen by remember { mutableStateOf(false) }
    // Cricket full scorecard overlay (opened by a short OK on the Stats tab; its keys: Left/Right innings, Up/Down scroll, Back/OK close)
    var fullOpen by remember { mutableStateOf(false) }
    var fullTab by remember { mutableIntStateOf(0) }
    val fullScroll = rememberScrollState()
    // The release of the OK / Back press that closed the sheet is swallowed too (it must not reach the player)
    val fullSwallowUp = remember { BooleanArray(1) }
    val fullScope = androidx.compose.runtime.rememberCoroutineScope()
    var chipCursor by remember { mutableIntStateOf(0) }
    val delaySheetAt = remember { longArrayOf(0L) }
    // Round 3 (wow brief): break mode 4A, the full-time card (3) and catch me up (8)
    val sportPrefs = remember { context.getSharedPreferences("jellytip_sport", android.content.Context.MODE_PRIVATE) }
    var breakModeOn by remember { mutableStateOf(sportPrefs.getBoolean("breakMode", true)) }
    var openedBy by remember { mutableStateOf(BreakModePolicy.Reason.USER) }
    val openedAt = remember { longArrayOf(0L) }
    var panelTouched by remember { mutableStateOf(false) }
    var closedBreak by remember { mutableStateOf<String?>(null) }
    var userClosedAt by remember { mutableStateOf<Long?>(null) }
    val lastKindRef = remember { arrayOfNulls<GameKind>(1) }
    val lastBreakRef = remember { arrayOfNulls<String>(1) }
    var ftCard by remember { mutableStateOf<Pair<IptvMatch, FullTimeNext?>?>(null) }
    var ftScore by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    val ftShown = remember { HashSet<String>() }
    var catchOpen by remember { mutableStateOf(false) }
    // From the page opening until the catch-up offer is decided: nothing live shows (the card promises a hidden score)
    var catchPending by remember { mutableStateOf(true) }
    var catchRequest by remember { mutableIntStateOf(0) }
    var catchCursor by remember { mutableIntStateOf(2) }
    var catchLeft by remember { mutableStateOf(CatchUpPolicy.CHOICE_TIMEOUT_MS / 1000) }
    var catchCards by remember { mutableStateOf<List<CatchUpPolicy.Card>?>(null) }
    // Decided before the card opens (spec C): the recap it promises and the options it may show
    var catchReady by remember { mutableStateOf<List<CatchUpPolicy.Card>>(emptyList()) }
    var catchOptions by remember { mutableStateOf(listOf(2)) }
    var catchDeciding by remember { mutableStateOf(false) }
    var catchIndex by remember { mutableIntStateOf(0) }
    val catchOffered = remember { HashSet<String>() }
    /** The viewer closed the panel: a break stays closed for the rest of that break, moments stay quiet 2 min. */
    fun closePanelByUser(how: String) {
        panelOpen = false
        userClosedAt = System.currentTimeMillis()
        if (lastKindRef[0] == GameKind.BREAK) closedBreak = lastBreakRef[0]
        openedBy = BreakModePolicy.Reason.USER
        JellytipTrace.player("iptv", "companion closed ($how)")
    }
    var panelTab by remember { mutableIntStateOf(0) }
    var shownPanelMatch by remember { mutableStateOf<IptvMatch?>(null) }
    // Long-press OK while the panel is open: its menu (Docked / Floating, Match info)
    var panelMenu by remember { mutableStateOf(false) }
    var lastToast by remember { mutableStateOf<PlayerToast?>(null) }
    var trackSheetOpen by remember { mutableStateOf(false) }
    var trackCursor by remember { mutableIntStateOf(0) }
    var trackSnapshot by remember { mutableStateOf(Tracks.EMPTY) }
    var trackMessage by remember { mutableStateOf<String?>(null) }
    var subtitleCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    val trackPlayer = playback.player
    val initialTrackSelectionParameters = remember(trackPlayer) { trackPlayer?.trackSelectionParameters }
    val trackTarget = playState.target
    var trackItem by remember { mutableStateOf<MediaItem?>(null) }
    DisposableEffect(trackPlayer, current) {
        trackSheetOpen = false
        trackCursor = 0
        trackMessage = null
        subtitleCues = emptyList()
        trackItem = trackPlayer?.currentMediaItem
        trackSnapshot = if (playback.state.value.target == current && trackItem != null) trackPlayer?.currentTracks ?: Tracks.EMPTY else Tracks.EMPTY
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                trackItem = mediaItem
                trackSheetOpen = false
                trackCursor = 0
                trackMessage = null
                trackSnapshot = Tracks.EMPTY
                subtitleCues = emptyList()
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (trackPlayer?.currentMediaItem == trackItem) {
                    if (playback.state.value.target == current) trackSnapshot = tracks
                    trackCursor = trackCursor.coerceIn(0, tracks.choices(C.TRACK_TYPE_AUDIO).size + tracks.choices(C.TRACK_TYPE_TEXT).size)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                if (playback.state.value.target == current && trackPlayer?.currentMediaItem == trackItem) subtitleCues = cueGroup.cues
            }
        }
        trackPlayer?.addListener(listener)
        onDispose {
            trackPlayer?.removeListener(listener)
            if (trackPlayer != null && initialTrackSelectionParameters != null)
                trackPlayer.trackSelectionParameters = initialTrackSelectionParameters
        }
    }
    LaunchedEffect(trackPlayer, trackTarget, trackItem, current) {
        if (trackPlayer != null && trackTarget == current && trackPlayer.currentMediaItem == trackItem) {
            trackSnapshot = trackPlayer.currentTracks
            subtitleCues = trackPlayer.currentCues.cues
        }
    }
    val audioTracks = remember(trackSnapshot, trackTarget, current, trackItem) {
        if (trackTarget == current && trackItem != null) trackSnapshot.choices(C.TRACK_TYPE_AUDIO) else emptyList()
    }
    val subtitleTracks = remember(trackSnapshot, trackTarget, current, trackItem) {
        if (trackTarget == current && trackItem != null) trackSnapshot.choices(C.TRACK_TYPE_TEXT) else emptyList()
    }
    val trackRows = audioTracks.size + 1 + subtitleTracks.size
    LaunchedEffect(trackMessage) {
        if (trackMessage != null) {
            delay(3_000)
            trackMessage = null
        }
    }
    LaunchedEffect(trackSheetOpen, matchModal, preMenu, panelMenu) {
        if (trackSheetOpen && (matchModal != null || preMenu || panelMenu)) trackSheetOpen = false
    }
    fun openTrackSheet() {
        if (matchModal != null || preMenu || panelOpen || panelMenu || delaySheetOpen || chipsOpen) return
        trackCursor = audioTracks.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: audioTracks.size
        trackSheetOpen = true
        bannerVisible = true
        bannerTick++
    }
    fun selectTrack() {
        val player = trackPlayer ?: return
        if (playback.state.value.target != current || player.currentMediaItem != trackItem) return
        val choice = when {
            trackCursor < audioTracks.size -> audioTracks[trackCursor]
            trackCursor > audioTracks.size -> subtitleTracks.getOrNull(trackCursor - audioTracks.size - 1)
            else -> null
        }
        val type = if (trackCursor < audioTracks.size) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && choice == null)
            .apply { if (choice != null) addOverride(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index)) }
            .build()
        trackMessage = when {
            choice == null -> "Subtitles off"
            type == C.TRACK_TYPE_AUDIO -> "Audio: ${choice.label()}"
            else -> "Subtitles: ${choice.label()}"
        }
        trackSheetOpen = false
        bannerTick++
        focus.tryRequestFocus()
    }
    // A replayed game (matchInfo.replay) gets no live panel, chip, fantasy or alerts
    // Where the live match comes from (web parity 21-Nov): a Game Centre launch names it (74acc58b); else the guide's
    // fixture; else, for a channel opened directly, the live fixture listing this channel (web channelMatch; a guessed
    // "maybe" link stays out)
    val launchMatch by produceState<IptvMatch?>(null, matchId) {
        value = matchId?.let { id -> fetchMatch(id)?.second?.match }
        if (matchId != null) JellytipTrace.player("iptv", "launch match $matchId -> ${value?.id ?: "none"}")
    }
    val resolvedMatch by produceState<IptvMatch?>(null, channel.id, nowNext != null, nowNext?.liveMatch?.id, current.isLive, index == startIndex) {
        value = null
        if ((matchId != null && index == startIndex) || nowNext == null || nowNext?.liveMatch != null || !current.isLive) return@produceState
        val fx = viewModel.service.fixtures(maxAgeMs = 60_000)?.second ?: return@produceState
        val cm = SportFixtures.resolveChannelMatch(fx.items, channel.id, nowSec()) ?: return@produceState
        if (cm.maybe) return@produceState
        value = fetchMatch(cm.matchId)?.second?.match
        JellytipTrace.player("iptv", "channel match ${channel.id} -> ${cm.matchId} ${value?.id ?: "(no detail)"}")
    }
    val sourceMatch = launchMatch?.takeIf { index == startIndex } ?: nowNext?.liveMatch ?: resolvedMatch
    // The viewer's side (team-aware alerts): a followed team id from sport.teamRefs, else a followed team name
    val mePrefs by com.github.damontecres.wholphin.jellytip.me.JellytipMe.prefs.collectAsState()
    val favSide =
        remember(sourceMatch?.id, mePrefs?.sportTeams, mePrefs?.sportTeamRefs) {
            val m = sourceMatch
            if (m == null || m.isEvent) {
                null
            } else {
                TeamAware.favouriteSide(
                    m.home?.let { TeamAware.Side(it.name, it.short, it.teamId) },
                    m.away?.let { TeamAware.Side(it.name, it.short, it.teamId) },
                    m.leagueKey,
                    mePrefs?.sportTeams.orEmpty(),
                    mePrefs?.sportTeamRefs.orEmpty(),
                )
            }
        }
    val favNow by androidx.compose.runtime.rememberUpdatedState(favSide)
    LaunchedEffect(sourceMatch?.id, favSide) { if (sourceMatch != null) JellytipTrace.player("iptv", "fav side ${sourceMatch.id} = $favSide") }
    val panelMatch = sourceMatch?.takeIf { current.isLive && !it.replay && !LivePanelRoute.missing && !MatchHidden.hides(it.id) }
    LaunchedEffect(panelMatch?.id) { if (panelMatch == null) panelOpen = false }
    val me by com.github.damontecres.wholphin.jellytip.me.JellytipMe.prefs.collectAsState()
    val spoilerNotice = (nowNext?.now?.kind == "replay" || nowNext?.now?.kind == "highlights" || sourceMatch?.replay == true) && me?.sportSpoilers != true
    androidx.activity.compose.BackHandler(enabled = trackSheetOpen) {
        trackSheetOpen = false
        focus.tryRequestFocus()
    }
    // Spoiler delay (web lib/delay.ts, plan "Delay model"): everything live waits delaySec (+ how far the viewer sits
    // behind the live edge); a catch-up is a replay and reveals nothing. Per-channel delay from the server.
    val lagMeter = remember { PlayerLag() }
    LaunchedEffect(current) { lagMeter.reset(current) }
    val revealClock = remember { RevealClock(lag = { lagMeter.lagSec(playback.player) }, forcedReplay = { !current.isLive }) }
    var delayShown by remember { mutableIntStateOf(DelayMath.DEFAULT_DELAY_SEC) }
    var replayShown by remember { mutableStateOf(false) }
    val delayChannel = remember { arrayOf<String?>(null) }
    LaunchedEffect(channel.id, me?.sportDelaySec) {
        // The pref arriving late must not undo a delay the viewer just set on this channel
        if (delayChannel[0] == channel.id && revealClock.touched) return@LaunchedEffect
        delayChannel[0] = channel.id
        revealClock.reset(me?.sportDelaySec ?: DelayMath.DEFAULT_DELAY_SEC)
        delayShown = revealClock.delaySec
        val saved = viewModel.service.channelDelay(channel.id)
        if (saved != null) {
            revealClock.serverDelay(saved)
            delayShown = revealClock.delaySec
        }
        JellytipTrace.player("iptv", "delay ${channel.id} = ${revealClock.delaySec} s (saved $saved)")
    }
    // Adjust delay sheet (web DelayChip popover) and the channel chips (web: Down opens the match's other channels)
    var pendingDelaySave by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(pendingDelaySave) {
        val (ch, sec) = pendingDelaySave ?: return@LaunchedEffect
        delay(600) // stepper bursts send one trailing save
        viewModel.service.putChannelDelay(ch, sec)
        pendingDelaySave = null
    }
    // Leaving within the 0.6 s debounce must not lose the change (web a61f25ec): flush it outside the page's scope
    val pendingDelayNow by androidx.compose.runtime.rememberUpdatedState(pendingDelaySave)
    DisposableEffect(Unit) {
        onDispose {
            pendingDelayNow?.let { (ch, sec) ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { viewModel.service.putChannelDelay(ch, sec) }
            }
        }
    }
    fun applyDelay(sec: Int) {
        val d = revealClock.setDelay(sec)
        delayShown = d
        pendingDelaySave = channel.id to d
        JellytipTrace.player("iptv", "delay set ${channel.id} = $d s")
    }
    val heldFantasy = remember { ArrayList<Pair<Double, PlayerToast>>() }
    // Fantasy totals for the panel tab and banner chip wait for the delay like everything live (by arrival time)
    val fantasySnaps = remember { ArrayList<Pair<Double, FantasyLive>>() }
    var fantasyShown by remember { mutableStateOf<FantasyLive?>(null) }
    val chipChannels = (listOf(lineup[index]) + playback.alternates[lineup[index].id].orEmpty()).distinctBy { it.id }
    LaunchedEffect(delaySheetOpen, panelMenu) { if (delaySheetOpen && !panelMenu) focus.tryRequestFocus() }
    LaunchedEffect(delayNote) {
        if (delayNote != null) {
            delay(3_000)
            delayNote = null
        }
    }
    androidx.activity.compose.BackHandler(enabled = panelOpen) { closePanelByUser("Back") }
    // Registered after the panel's, so Back closes the sheet / chips first
    androidx.activity.compose.BackHandler(enabled = delaySheetOpen) {
        delaySheetOpen = false
        focus.tryRequestFocus()
    }
    androidx.activity.compose.BackHandler(enabled = chipsOpen) {
        chipsOpen = false
        focus.tryRequestFocus()
    }
    androidx.activity.compose.BackHandler(enabled = fullOpen) {
        fullOpen = false
        focus.tryRequestFocus()
    }
    // Round 3 cards: Back closes the card (and stays on this channel), never the player
    androidx.activity.compose.BackHandler(enabled = ftCard != null) { ftCard = null }
    androidx.activity.compose.BackHandler(enabled = catchOpen || catchCards != null) {
        catchOpen = false
        catchCards = null
    }

    // Top-right toasts shared by market flips and fantasy scoring (plan "fantasy alerts"): one at a time, 6 s each, at
    // most 3 waiting (the oldest waiting one is dropped)
    var flipToast by remember { mutableStateOf<PlayerToast?>(null) }
    val toastQueue = remember { androidx.compose.runtime.mutableStateListOf<PlayerToast>() }
    fun enqueueToast(t: PlayerToast) {
        if (toastQueue.size >= MAX_QUEUED_TOASTS) toastQueue.removeAt(0)
        toastQueue.add(t)
    }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.snapshotFlow { toastQueue.isNotEmpty() }.first { it }
            val next = toastQueue.removeAt(0)
            flipToast = next
            delay(FLIP_TOAST_MS)
            flipToast = null
            delay(TOAST_GAP_MS) // let it slide out before the next one
        }
    }
    // Fantasy (plan "Fantasy live scoring for NFL"): while an NFL match plays (not a replay), /v1/fantasy/live every
    // 60 s feeds the panel's Fantasy tab, the banner chip and scoring toasts; nothing without a linked account
    val fantasy by JellytipFantasy.live.collectAsState()
    val nflMatch = sourceMatch?.takeIf { current.isLive && !it.replay && isNfl(it) && !spoilerNotice }
    LaunchedEffect(nflMatch?.id) {
        if (nflMatch == null) return@LaunchedEffect
        while (true) {
            JellytipFantasy.refresh(FANTASY_POLL_MS - 5_000)
            delay(FANTASY_POLL_MS)
        }
    }
    // Chat only once a /live answer carries a `chat` block (no Reddit app: none at all)
    var liveHasChat by remember(panelMatch?.id) { mutableStateOf(false) }
    val liveTabs = if (liveHasChat) LIVE_TABS else LIVE_TABS - "Chat"
    val panelTabs = if (nflMatch != null && fantasyShown != null) liveTabs + "Fantasy" else liveTabs
    LaunchedEffect(panelTabs.size) { if (panelTab >= panelTabs.size) panelTab = 0 }
    // RedZone (plan "RedZone / whip-around mode"): the Fantasy tab is the default
    val fantasyIndex = panelTabs.indexOf("Fantasy")
    LaunchedEffect(panelMatch?.id, fantasyIndex) { if (panelMatch?.isMulti == true && fantasyIndex >= 0) panelTab = fantasyIndex }
    // Scoring events: toasts like the flip one (fantasy.alerts); events already there when watching starts are skipped
    val seenEvents = remember { HashSet<String>() }
    val eventsPrimed = remember { booleanArrayOf(false) }
    LaunchedEffect(fantasy, nflMatch?.id) {
        val f = fantasy ?: return@LaunchedEffect
        fantasySnaps += (System.currentTimeMillis() / 1000.0) to f
        if (fantasySnaps.size > 20) fantasySnaps.removeAt(0)
        val fresh = f.events.filter { seenEvents.add("${it.at}|${it.league}|${it.name}") }
        if (!eventsPrimed[0]) {
            eventsPrimed[0] = true
            return@LaunchedEffect
        }
        if (nflMatch == null || me?.fantasyAlerts == false) return@LaunchedEffect
        // Both sides (green accent for yours, red for the opponent's), with the play when the server has it:
        // "🏈 +6.4 Gibbs · TD catch, 34 yds (CCP) · you lead 93.8 – 79.2"
        fresh.forEach { e ->
            val league = f.leagues.firstOrNull { it.name == e.league || it.id == e.league }
            val sign = if (e.delta >= 0) "+" else ""
            val opp = e.side == "opp"
            val lead =
                league?.let { l ->
                    val mine = l.mePoints ?: 0.0
                    val theirs = l.oppPoints ?: 0.0
                    (if (mine >= theirs) "You lead " else "You trail ") + l.scoreLine
                }
            val leagueName = league?.name ?: e.league
            val color = if (opp) TOAST_OPP else TOAST_MINE
            // The toast's "you lead" line uses totals that arrived now: it waits for whichever is later
            val fAt = maxOf(stampSec(e.at) ?: 0.0, System.currentTimeMillis() / 1000.0)
            if (!AlertPolicy.isKeyEvent(AlertPolicy.Candidate("f", "fantasy", fAt, swing = kotlin.math.abs(e.delta), payload = Unit), AlertPolicy.parseMode(me?.sportAlerts))) return@forEach
            heldFantasy += fAt to (
                PlayerToast(
                    main = "$sign${pts(e.delta)} ${e.name}" + (e.reason?.let { " · $it" } ?: ""),
                    kicker = (if (opp) "Opponent's player" else "Your player") + (leagueName?.let { " · $it" } ?: ""),
                    sub = lead,
                    edge = color,
                    kickerColor = color,
                    icon = BroadcastIcons.Ball,
                )
            )
        }
    }
    // One /live poll for the panel, flips and moments (plan "Live panel: pop, context, performance"): 15 s full while
    // the panel is open, 30 s light (`?fields=market,moments,score`) while it is closed; parsed off the main thread,
    // unchanged rows keep their instances. A new market.flip.at -> a flip toast (sport.flipAlerts); a new moment -> the
    // strip flashes while the panel is open, a toast while it is closed.
    val liveId = panelMatch?.id?.takeIf { !spoilerNotice }
    val panelIsOpen = panelOpen && panelMatch != null
    var liveData by remember(liveId) { mutableStateOf<MatchLive?>(null) }
    // The one gated value for the full scorecard (never while a replay or a spoiler notice is up)
    val fullCardNow = liveData.takeUnless { replayShown || spoilerNotice }?.fullCard
    LaunchedEffect(fullOpen, panelOpen, fullCardNow == null) {
        if (fullOpen && (!panelOpen || fullCardNow == null)) fullOpen = false
    }
    var liveFlash by remember(liveId) { mutableStateOf<LiveFlash?>(null) }
    val liveSeen = remember(liveId) { LiveSeen() }
    // Answers wait in [snaps] until the reveal clock releases them; alerts wait in [pendingAlerts] (web alertPolicy:
    // key events only, one per 30 s, never before the delay); the scorebug shows the newest released score
    val snaps = remember(liveId) { LiveSnapshots() }
    val pendingAlerts = remember(liveId) { ArrayList<AlertPolicy.Candidate<() -> Unit>>() }
    val lastAlertAt = remember(liveId) { doubleArrayOf(Double.NaN) }
    var bugData by remember(liveId) { mutableStateOf<MatchLive?>(null) }
    var bugChangedAt by remember(liveId) { mutableStateOf<Long?>(null) }
    var bugClock by remember(liveId) { mutableStateOf<String?>(null) }
    var holdSecs by remember(liveId) { mutableStateOf<Int?>(null) }
    /** Something else has the remote: the panel must not open underneath it. */
    fun overlaysUp() = delaySheetOpen || chipsOpen || fullOpen || trackSheetOpen || matchModal != null || preMenu || panelMenu
    fun openAuto(r: BreakModePolicy.Reason) {
        openedBy = r
        openedAt[0] = System.currentTimeMillis()
        panelTouched = false
        panelTab = panelTabs.indexOf("Stats").coerceAtLeast(0)
        panelOpen = true
        JellytipTrace.player("iptv", "companion opened by itself ($r) ${panelMatch?.id}")
    }
    /** Break mode on a released key moment: open for 6 s with the moment's wipe; false = not now (toast instead). */
    fun tryMomentOpen(mo: LiveMoment): Boolean {
        if (panelMatch == null || catchPending || catchOpen || catchCards != null || ftCard != null || overlaysUp()) return false
        val r = BreakModePolicy.autoOpen(breakModeOn, replayShown, panelOpen, lastKindRef[0], lastBreakRef[0], closedBreak, userClosedAt, System.currentTimeMillis(), freshMoment = true)
        if (r != BreakModePolicy.Reason.MOMENT) return false
        openAuto(r)
        liveFlash = LiveFlash((liveFlash?.seq ?: 0) + 1, mo)
        return true
    }
    LaunchedEffect(liveId, panelIsOpen) {
        val id = liveId ?: return@LaunchedEffect
        val m = panelMatch ?: return@LaunchedEffect
        while (true) {
            val got = fetchLive(id, light = !panelIsOpen)
            if (got?.first == 404) {
                LivePanelRoute.missing = true
                panelOpen = false
                return@LaunchedEffect
            }
            if (MatchHidden.hides(id)) {
                panelOpen = false
                return@LaunchedEffect
            }
            val d = got?.second
            if (d != null) {
                val arrived = nowSec()
                snaps.add(d, full = panelIsOpen, arrivedSec = arrived)
                if (panelIsOpen) liveHasChat = d.hasChat
                // Every key moment is an anchor for "That just happened"
                d.moments.forEach { mo -> stampSec(mo.at)?.let { revealClock.noteMoment(it) } }
                val stamp = (d.updatedAt ?: arrived).toDouble()
                val late = AlertPolicy.isLateGame(d.layout, d.period, d.clock)
                // Flip toast (the first answer counts too, as before)
                val mk = d.market
                val at = mk?.flipAt
                if (mk != null && at != null && at != liveSeen.flipAt) {
                    liveSeen.flipAt = at
                    // A thin market never fires a flip toast (plan "thin markets")
                    // Odds are spoilers: flip toasts only with "Show scores (spoilers)" on (web df2302bc)
                    if (me?.sportFlipAlerts != false && me?.sportSpoilers == true && !mk.thin) pendingAlerts += AlertPolicy.Candidate("flip|$at", "flip", stampSec(at) ?: stamp, payload = {
                        val team = if (mk.flipTo == "away") m.away else m.home
                        // Winner markets: flip.to names the outcome; the toast reads "new favourite"
                        val name = if (mk.isWinner) mk.flipTo ?: mk.outcomes.first().name else team?.short ?: team?.name ?: mk.flipTo ?: "New"
                        val pal = matchPalette(m)
                        enqueueToast(
                            PlayerToast(
                                main = (if (mk.isWinner) "$name new favourite" else "$name now favourites") + (mk.flipProb?.let { " · ${pct(it)}" } ?: ""),
                                kicker = "${mk.source?.replaceFirstChar { it.uppercase() } ?: "Market"} flip",
                                sub = mk.volume?.let { v -> "${if (v >= 1_000_000) String.format(java.util.Locale.US, "$%.1fM", v / 1_000_000) else String.format(java.util.Locale.US, "$%.0fK", v / 1_000)} traded" },
                                edge = if (pal.known && !mk.isWinner) pal.side(mk.flipTo) else if (pal.known) pal.home else Broadcast.Gold,
                            ),
                        )
                    })
                    JellytipTrace.player("iptv", "market flip ${m.id} -> ${mk.flipTo}")
                }
                // Moments: the server's, else a lead change / NFL touchdown or field goal from the score
                liveSeen.newMoments(d, m).forEach { mo ->
                    JellytipTrace.player("iptv", "moment ${m.id} ${mo.kind ?: mo.label} held")
                    val kind = mo.kind ?: "score"
                    // The toast may fall back to this answer's score: then it waits for the answer's own time too
                    val moAt = stampSec(mo.at) ?: stamp
                    val gateAt = if (mo.detail == null) maxOf(moAt, stamp) else moAt
                    pendingAlerts += AlertPolicy.Candidate("mo|${mo.at}|${mo.label}", kind, gateAt, favourite = TeamAware.isFavouriteMoment(mo.kind, mo.team, favNow), late = late, payload = {
                    if (panelOpen) {
                        liveFlash = LiveFlash((liveFlash?.seq ?: 0) + 1, mo)
                    } else if (tryMomentOpen(mo)) {
                        // Break mode: the panel opened for 6 s on this moment
                    } else if (me?.sportFlipAlerts != false) {
                        val team = if (mo.team == "away") m.away else if (mo.team == "home") m.home else null
                        val score = if (d.scoreHome != null && d.scoreAway != null) " · ${m.home?.short ?: m.home?.name} ${d.scoreHome} – ${d.scoreAway} ${m.away?.short ?: m.away?.name}" else ""
                        val pal = matchPalette(m)
                        enqueueToast(
                            PlayerToast(
                                main = mo.label,
                                kicker = team?.let { it.short ?: it.name } ?: "Live",
                                sub = mo.detail ?: score.removePrefix(" · ").ifBlank { null },
                                edge = if (pal.known && mo.team != null) pal.side(mo.team) else Broadcast.Live,
                                icon = BroadcastIcons.Ball,
                            ),
                        )
                    }
                    })
                }
            }
            delay(if (panelIsOpen) LIVE_OPEN_POLL_MS else FLIP_POLL_MS)
        }
    }
    // The reveal tick: once a second, release what the delay allows (panel data, scorebug, alerts, fantasy toasts).
    // State is only written when it changes, so the page doesn't recompose every second.
    val panelIsOpenNow by androidx.compose.runtime.rememberUpdatedState(panelIsOpen)
    LaunchedEffect(liveId) {
        var shownSnap: MatchLive? = null
        var shownAtMs = 0L
        var prevClock: Triple<String?, String?, Long>? = null
        var lastClock: Triple<String?, String?, Long>? = null
        var prevKey: String? = null
        while (true) {
            val rp = revealClock.inReplay()
            if (rp != replayShown) {
                replayShown = rp
                JellytipTrace.player("iptv", "reveal ${if (rp) "replay: live info off" else "live"}")
            }
            val reveal: (Double?) -> Boolean = { revealClock.reveal(it) }
            if (liveId != null && !rp) {
                if (panelIsOpenNow) {
                    snaps.releasedFull(reveal)?.let { r ->
                        val tl = GameStateLogic.releasedTimeline(r.timeline, reveal)
                        val card = GameStateLogic.releasedCard(r.scorecard, reveal)
                        val fullCard = GameStateLogic.releasedFullCard(r.fullCard, card)
                        val gated = if (tl.size == r.timeline.size && card === r.scorecard && fullCard === r.fullCard) r else r.copy(timeline = tl, scorecard = card, fullCard = fullCard)
                        val next = gated.reuse(liveData)
                        if (next !== liveData) liveData = next
                    }
                }
                val rel = snaps.released(reveal)
                holdSecs = if (rel == null) snaps.newestAt()?.let { revealClock.holdSecs(it.toDouble()) } else null
                if (rel != null && rel !== shownSnap) {
                    shownSnap = rel
                    shownAtMs = System.currentTimeMillis()
                    prevClock = lastClock
                    lastClock = Triple(rel.clock, rel.period, (rel.updatedAt ?: nowSec()) * 1000)
                    val key = "${rel.scoreHome}-${rel.scoreAway}|${rel.period}"
                    if (key != prevKey) {
                        // The first release shows the pill once; later score / period changes show it for 8 s
                        bugChangedAt = System.currentTimeMillis()
                        prevKey = key
                    }
                    bugData = rel
                }
                shownSnap?.let { sn ->
                    val sport = CompanionPolicy.clockSport(sn.layout, sourceMatch?.leagueKey)
                    val running = lastClock?.let { CompanionPolicy.clockRunning(sn.status, prevClock, it) } ?: false
                    val c = CompanionPolicy.tickClock(sn.clock, sport, running, (System.currentTimeMillis() - shownAtMs) / 1000.0)
                    if (c != bugClock) bugClock = c
                }
                val nowS = System.currentTimeMillis() / 1000.0
                val sh = shownSnap?.scoreHome?.toIntOrNull()
                val sa = shownSnap?.scoreAway?.toIntOrNull()
                val blowout = AlertPolicy.isBlowout(shownSnap?.layout, sh, sa, AlertPolicy.isLateGame(shownSnap?.layout, shownSnap?.period, shownSnap?.clock))
                if (catchPending || catchOpen || catchCards != null) {
                    // Catch me up is deciding / showing: alerts wait (still gated by the delay afterwards)
                    delay(1_000)
                    continue
                }
                val pick =
                    AlertPolicy.pick(
                        pendingAlerts.toList(),
                        lastAlertAt[0].takeIf { !it.isNaN() },
                        nowS,
                        { at -> revealClock.reveal(at) },
                        AlertPolicy.parseMode(me?.sportAlerts),
                        blowout,
                    )
                pendingAlerts.clear()
                pendingAlerts.addAll(pick.pending)
                pick.show?.let { c ->
                    lastAlertAt[0] = nowS
                    JellytipTrace.player("iptv", "alert released ${c.kind} ${c.id}")
                    c.payload()
                }
            }
            // Fantasy totals: the newest answer the delay has released
            if (fantasySnaps.isNotEmpty() && !rp) {
                val rel = fantasySnaps.lastOrNull { revealClock.reveal(it.first) }?.second
                if (rel != null && rel !== fantasyShown) fantasyShown = rel
            }
            // Fantasy scoring toasts wait for the delay too (web: same gate, Sleeper-lagged anyway)
            if (heldFantasy.isNotEmpty()) {
                val out = heldFantasy.filter { revealClock.reveal(it.first) }
                if (out.isNotEmpty()) {
                    heldFantasy.removeAll(out.toSet())
                    if (!rp) out.forEach { enqueueToast(it.second) }
                }
            }
            delay(1_000)
        }
    }
    // Blowout (web BlowoutCard): a tighter live game to suggest, looked up while the panel is open on a decided game;
    // a short OK in the panel switches to it
    val blowout =
        remember(liveData, panelMatch?.id, me?.sportSpoilers, replayShown) {
            val d = liveData
            val m = panelMatch
            d != null && m != null && !m.isEvent && !replayShown && GameStateLogic.of(d, m.leagueKey, m.status, me?.sportSpoilers == true).kind == GameKind.BLOWOUT
        }
    val closerGame by produceState<FxItem?>(null, blowout, panelIsOpen, panelMatch?.id) {
        value = null
        if (!blowout || !panelIsOpen) return@produceState
        while (true) {
            val fx = viewModel.service.fixtures(maxAgeMs = 60_000)?.second
            if (fx != null) {
                val c = GameStateLogic.closerGame(fx.items, setOfNotNull(panelMatch?.id, matchId), nowSec())
                if (c?.fid != value?.fid) JellytipTrace.player("iptv", "blowout ${panelMatch?.id}: closer ${c?.fid ?: "none"}")
                value = c
            }
            delay(60_000)
        }
    }
    var pendingCloser by remember { mutableStateOf<FxItem?>(null) }
    val closerArmed = remember { booleanArrayOf(false) }
    LaunchedEffect(pendingCloser) {
        val c = pendingCloser ?: return@LaunchedEffect
        pendingCloser = null
        val ch = SportFixtures.pickChannel(c, nowSec()).channel ?: return@LaunchedEffect
        panelOpen = false
        launchFixture(viewModel.playback, viewModel.navigationManager, c, ch, fromStart = false, from = "closer game", replaceTop = true)
    }
    // Break mode (4A) and full time (3): once a second from the released answer (held to the delay)
    LaunchedEffect(liveId) {
        while (true) {
            delay(1_000)
            val d = bugData
            val m = panelMatch
            val kind = if (d != null && m != null && !m.isEvent) GameStateLogic.of(d, m.leagueKey, m.status, me?.sportSpoilers == true).kind else null
            lastKindRef[0] = kind
            lastBreakRef[0] = d?.let { BreakModePolicy.breakKey(it.period, it.clock) }
            val nowMs = System.currentTimeMillis()
            if (panelOpen && openedBy != BreakModePolicy.Reason.USER) {
                if (BreakModePolicy.autoClose(openedBy, openedAt[0], nowMs, kind, panelTouched, replayShown)) {
                    panelOpen = false
                    JellytipTrace.player("iptv", "companion tucked away ($openedBy, now $kind)")
                    openedBy = BreakModePolicy.Reason.USER
                }
            } else if (!panelOpen && m != null && !catchPending && !catchOpen && catchCards == null && ftCard == null && !overlaysUp()) {
                if (BreakModePolicy.autoOpen(breakModeOn, replayShown, false, kind, lastBreakRef[0], closedBreak, userClosedAt, nowMs, false) == BreakModePolicy.Reason.BREAK) {
                    openAuto(BreakModePolicy.Reason.BREAK)
                }
            }
            if (kind == GameKind.FINAL && d != null && m != null && m.id !in ftShown && !replayShown) {
                ftShown += m.id
                ftScore = d.scoreHome to d.scoreAway
                val fx = viewModel.service.fixtures(maxAgeMs = 60_000)?.second
                val next = fx?.let { FullTimePolicy.nextGame(it.items, setOfNotNull(m.id, matchId), nowSec()) }
                val page = if (next == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.specials.peek("home") } else null
                val pick = page?.specials?.filterIsInstance<com.github.damontecres.wholphin.jellytip.specials.JellytipSpecial.TonightsPick>()?.firstOrNull { it.itemId != null }
                ftCard = m to FullTimeNext(next, pick?.title, pick?.itemId, pick?.itemId?.let { page?.items?.get(it)?.type })
                if (panelOpen && openedBy != BreakModePolicy.Reason.USER) panelOpen = false
                JellytipTrace.player("iptv", "full time ${m.id}: next ${next?.fid ?: pick?.title ?: "none"}")
            }
        }
    }
    LaunchedEffect(current) { ftCard = null }
    val kickoffQuiet = catchOpen || catchCards != null || ftCard != null
    DisposableEffect(kickoffQuiet) {
        viewModel.kickoff.quiet = kickoffQuiet
        if (kickoffQuiet) viewModel.kickoff.hush()
        onDispose { viewModel.kickoff.quiet = false }
    }
    LaunchedEffect(ftCard) {
        if (ftCard != null) {
            delay(30_000)
            ftCard = null
        }
    }
    // Catch me up (8): joining a live game 10+ min in offers From kick-off / Catch me up / Live (default, 20 s).
    // Decided before offering (spec jellytip-catchup-client-spec-20261122 C): one full /live answer is fetched during the
    // wait, so Catch me up is only offered with plays to show and From kick-off only when the server allows it; with
    // only Live left there is no card at all.
    val spoilerNoticeNow by androidx.compose.runtime.rememberUpdatedState(spoilerNotice)
    LaunchedEffect(panelMatch?.id, current.isLive) {
        val m = panelMatch
        if (m == null || m.id in catchOffered || !current.isLive) {
            catchPending = false
            return@LaunchedEffect
        }
        catchPending = true
        catchDeciding = true
        try {
            val id = liveId
            val fullJob = id?.let { i -> async { kotlinx.coroutines.withTimeoutOrNull(CatchUpPolicy.DECIDE_FETCH_MS) { fetchLive(i, light = false)?.second } } }
            delay(2_500) // the first /live answer says whether it's under way
            val now = nowSec()
            val st = snaps.latest()?.status ?: m.status?.let { if (it == "pre" && (m.startsAt ?: now) <= now) "in" else it }
            if (!CatchUpPolicy.offer(current.isLive, replayShown, spoilerNoticeNow, st, m.startsAt, now)) {
                fullJob?.cancel()
                catchPending = false
                return@LaunchedEffect
            }
            val full = fullJob?.await()
            if (full != null) snaps.add(full, full = true, arrivedSec = nowSec())
            // The programme guide answer may still be on its way: give it a moment (it decides From kick-off)
            var waited = 0
            while (nowNext == null && waited < 4) {
                delay(500)
                waited++
            }
            // The waits may have changed things (replay, a spoiler programme, zapped off live): ask again
            if (!CatchUpPolicy.offer(current.isLive, replayShown, spoilerNoticeNow, st, m.startsAt, nowSec())) {
                catchPending = false
                return@LaunchedEffect
            }
            val reveal: (Double?) -> Boolean = { revealClock.reveal(it) }
            val cs = CatchUpPolicy.cards(snaps.releasedFull(reveal), snaps.latest(), snaps.latestFull(), reveal)
            val kick = current.isLive && nowNext?.now?.let { it.canStartOver && it.isOn(nowSec()) } == true
            val opts = CatchUpPolicy.options(kick, cs.size)
            if (opts.isEmpty()) {
                // A full answer that never came (timeout / error) is not "nothing to catch up on": a later visit may ask again
                if (full != null) catchOffered += m.id
                JellytipTrace.player("iptv", "catch-up not offered ${m.id}: kickoff=false cards=0 (full answer ${full != null})")
                catchPending = false
                return@LaunchedEffect
            }
            catchOffered += m.id
            catchReady = cs
            catchOptions = opts
            catchCursor = 2
            catchOpen = true
            catchPending = false
            JellytipTrace.player("iptv", "catch-up offered ${m.id} ${(now - (m.startsAt ?: now)) / 60} min in: kickoff=$kick cards=${cs.size}")
        } finally {
            catchDeciding = false
        }
    }
    LaunchedEffect(panelMatch?.id, catchOffered.size, catchOpen, catchDeciding) {
        // Not offered (on time, replay…): live info flows as normal
        if (!catchOpen && !catchDeciding) {
            delay(3_000)
            if (!catchOpen && !catchDeciding) catchPending = false
        }
    }
    LaunchedEffect(catchOpen) {
        if (!catchOpen) return@LaunchedEffect
        catchLeft = CatchUpPolicy.CHOICE_TIMEOUT_MS / 1000
        while (catchLeft > 0) {
            delay(1_000)
            catchLeft--
        }
        catchOpen = false
        bugChangedAt = System.currentTimeMillis()
        JellytipTrace.player("iptv", "catch-up choice timed out: live")
    }
    LaunchedEffect(catchCards, catchIndex) {
        val cs = catchCards ?: return@LaunchedEffect
        delay(CatchUpPolicy.CARD_MS)
        if (catchIndex < cs.size - 1) {
            catchIndex++
        } else {
            catchCards = null
            bugChangedAt = System.currentTimeMillis() // into live with the score showing
        }
    }
    // Catch me up: the recap decided before the card opened, or a fresher one if more has been released since
    LaunchedEffect(catchRequest) {
        if (catchRequest == 0) return@LaunchedEffect
        val reveal: (Double?) -> Boolean = { revealClock.reveal(it) }
        val fresh = CatchUpPolicy.cards(snaps.releasedFull(reveal), snaps.latest(), snaps.latestFull(), reveal)
        val cs = if (fresh.size >= catchReady.size) fresh else catchReady
        JellytipTrace.player("iptv", "catch-up: recap ${cs.size} cards (decided ${catchReady.size})")
        if (cs.isEmpty()) {
            // Not reachable (the option needs cards); never an empty recap
            bugChangedAt = System.currentTimeMillis()
        } else {
            catchIndex = 0
            catchCards = cs
        }
    }
    fun chooseCatchUp(c: Int) {
        catchOpen = false
        when (c) {
            0 -> {
                val prog = nowNext?.now ?: return
                JellytipTrace.player("iptv", "catch-up: from kick-off ${channel.id}")
                current = IptvTarget(channel.id, prog.title, prog.start, prog.stop)
                playback.play(current, "catch-up from kick-off")
                bannerTick++
            }
            1 -> catchRequest++
            else -> {
                JellytipTrace.player("iptv", "catch-up: live")
                bugChangedAt = System.currentTimeMillis()
            }
        }
    }
    // From kick-off / Watch from the start (spec D, E): a catch-up window of this channel that fails (the server's
    // no_catchup refusal, recovery giving up), ends without ever playing, or never starts within the catch-up stall
    // window goes back to the channel's live stream with an honest toast: never a dead "try again" screen or an endless
    // spinner. Every entry point (guide, Game Centre, live hero, banner Left, the choice card) lands on this page with
    // [current] set to the window. Recordings and IPTV films have their own failure screens.
    LaunchedEffect(current) {
        val t = current
        if (t.start == null || t.recordingId != null || t.vodId != null || t.seriesId != null || t.episodeId != null) return@LaunchedEffect
        var everPlayed = false
        var openingMs = 0L
        var playedMs = 0L
        while (true) {
            delay(500)
            // App in the background (stream stopped) or offline (our network, recovery waits): nothing is judged
            val away = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || playback.offline
            if (away) continue
            // Paused on this window: no time is counted, but a failure is still a failure. (The one player can keep
            // playWhenReady = false from a paused film, so it only counts while it holds this window's stream.)
            val paused = playback.holds(t) && playback.player?.playWhenReady == false
            val s = playback.state.value
            val mine = s.target == t
            if (mine && s is IptvPlayState.Playing && !paused) {
                everPlayed = true
                playedMs += 500
            }
            if (mine && s is IptvPlayState.Opening && !paused) openingMs += 500
            // A failure screen (all connections in use…) ends that attempt: an OK retry gets the whole start window
            if (mine && s is IptvPlayState.Failed) openingMs = 0L
            val failed = mine && s is IptvPlayState.Failed && CatchUpPolicy.fallsBack(s.cause)
            // ENDED only counts once the player holds this window's stream (not the previous item's ENDED)
            val endedUnplayed = mine && !everPlayed && playback.holds(t) && playback.player?.playbackState == androidx.media3.common.Player.STATE_ENDED
            if (CatchUpPolicy.catchUpGaveUp(failed || endedUnplayed, everPlayed, openingMs)) {
                val why = if (failed) (s as IptvPlayState.Failed).message else if (endedUnplayed) "ended before playing" else "never started"
                JellytipTrace.player("iptv", "catch-up gave up ${t.channelId} ($why, played ${playedMs / 1000} s): back to live")
                // Back on live: the catch-up card must not pop up and offer the same broken window again
                sourceMatch?.id?.let { catchOffered += it }
                val name = if (channel.id == t.channelId) channel.name else t.label
                current = IptvTarget(t.channelId, name)
                playback.play(current, "catch-up failed: back to live")
                bannerTick++
                android.widget.Toast.makeText(context, CatchUpPolicy.gaveUpToast(playedMs), android.widget.Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
        }
    }
    fun ftAccept() {
        val (_, next) = ftCard ?: return
        ftCard = null
        val g = next?.game
        val ch = g?.let { SportFixtures.pickChannel(it, nowSec()).channel }
        if (g != null && ch != null) {
            launchFixture(viewModel.playback, viewModel.navigationManager, g, ch, fromStart = false, from = "full time next", replaceTop = true)
        } else if (next?.pickId != null) {
            viewModel.navigationManager.navigateTo(
                com.github.damontecres.wholphin.ui.nav.Destination.MediaItem(next.pickId, next.pickKind ?: org.jellyfin.sdk.model.api.BaseItemKind.MOVIE),
                "full time: tonight's pick",
            )
        }
    }
    // Perf trace: the player with the panel open is measured on its own (perfByDest "…+panel")
    DisposableEffect(panelIsOpen) {
        com.github.damontecres.wholphin.jellytip.debug.JellytipPerf.subState = if (panelIsOpen) "panel" else null
        onDispose { com.github.damontecres.wholphin.jellytip.debug.JellytipPerf.subState = null }
    }
    LaunchedEffect(preStartsAt, channel.id) {
        if (preStartsAt != null) preCardTick++
    }
    LaunchedEffect(preCardTick) {
        if (preCardTick == 0 || preStartsAt == null) return@LaunchedEffect
        preCardVisible = true
        delay(PRE_CARD_MS)
        preCardVisible = false
    }
    LaunchedEffect(preStartsAt, channel.id) {
        val at = preStartsAt ?: return@LaunchedEffect
        delay(((at - nowSec()) * 1000).coerceAtLeast(0))
        preCardVisible = false
        android.widget.Toast.makeText(context, "Started", android.widget.Toast.LENGTH_SHORT).show()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (fullOpen || fullSwallowUp[0]) && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Back || e.key == Key.Escape)) {
                    fullSwallowUp[0] = false
                    return@onPreviewKeyEvent true
                }
                // Closer game: a short OK press in the panel's Stats tab (released, not held) switches to it
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && closerArmed[0]) {
                    closerArmed[0] = false
                    val c = closerGame
                    if (panelOpen && !panelMenu && !replayShown && panelTabs.getOrNull(panelTab) == "Stats") {
                        if (c != null) {
                            pendingCloser = c
                        } else if (fullCardNow != null) {
                            fullTab = 0
                            fullOpen = true
                            fullScope.launch { fullScroll.scrollTo(0) }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val okKey = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                // Recovery gave up: OK tries again (Up/Down still zap, Back still leaves)
                (playState as? IptvPlayState.Failed)?.takeIf { it.canRetry && okKey && it.target == current }?.let {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        JellytipTrace.player("iptv", "retry ${current.channelId} (OK)")
                        playback.retry()
                    }
                    return@onPreviewKeyEvent true
                }
                if (catchOpen) {
                    val opts = catchOptions
                    val i = opts.indexOf(catchCursor).coerceAtLeast(0)
                    when {
                        e.key == Key.DirectionLeft -> catchCursor = opts[(i - 1).coerceAtLeast(0)]
                        e.key == Key.DirectionRight -> catchCursor = opts[(i + 1).coerceAtMost(opts.size - 1)]
                        okKey -> if (e.nativeKeyEvent.repeatCount == 0) chooseCatchUp(catchCursor)
                        e.key == Key.Back || e.key == Key.Escape -> chooseCatchUp(2)
                        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {}
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                catchCards?.let { cs ->
                    when {
                        okKey || e.key == Key.DirectionRight -> if (e.nativeKeyEvent.repeatCount == 0) {
                            if (catchIndex < cs.size - 1) catchIndex++ else {
                                catchCards = null
                                bugChangedAt = System.currentTimeMillis()
                            }
                        }
                        e.key == Key.Back || e.key == Key.Escape -> {
                            catchCards = null
                            bugChangedAt = System.currentTimeMillis()
                        }
                        e.key == Key.DirectionLeft || e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {}
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (ftCard != null) {
                    when {
                        okKey -> if (e.nativeKeyEvent.repeatCount == 0) ftAccept()
                        e.key == Key.Back || e.key == Key.Escape -> {
                            ftCard = null
                            JellytipTrace.player("iptv", "full-time card dismissed")
                        }
                        e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {}
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (trackSheetOpen) {
                    when (e.key) {
                        Key.DirectionUp -> trackCursor = Math.floorMod(trackCursor - 1, trackRows)
                        Key.DirectionDown -> trackCursor = (trackCursor + 1) % trackRows
                        Key.DirectionLeft, Key.DirectionRight -> {}
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.nativeKeyEvent.repeatCount == 0) selectTrack()
                        Key.Back, Key.Escape -> {
                            trackSheetOpen = false
                            focus.tryRequestFocus()
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (fullOpen) {
                    val size = fullCardNow?.innings?.size ?: 0
                    when (e.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val next = stepFullTab(fullTab, if (e.key == Key.DirectionLeft) -1 else 1, size)
                            if (next != fullTab) {
                                fullTab = next
                                fullScope.launch { fullScroll.scrollTo(0) }
                            }
                        }
                        Key.DirectionUp -> fullScope.launch { fullScroll.animateScrollBy(-260f) }
                        Key.DirectionDown -> fullScope.launch { fullScroll.animateScrollBy(260f) }
                        Key.Back, Key.Escape, Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                fullOpen = false
                                fullSwallowUp[0] = true
                                focus.tryRequestFocus()
                            }
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (delaySheetOpen) {
                    when (e.key) {
                        Key.DirectionUp -> delayCursor = Math.floorMod(delayCursor - 1, DELAY_ROWS.size)
                        Key.DirectionDown -> delayCursor = (delayCursor + 1) % DELAY_ROWS.size
                        Key.DirectionLeft -> if (e.nativeKeyEvent.repeatCount == 0 || System.currentTimeMillis() - delaySheetAt[0] > 400) applyDelay(DelayMath.stepDelay(delayShown, -5))
                        Key.DirectionRight -> if (e.nativeKeyEvent.repeatCount == 0 || System.currentTimeMillis() - delaySheetAt[0] > 400) applyDelay(DelayMath.stepDelay(delayShown, 5))
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                when (delayCursor) {
                                    0 -> applyDelay(DelayMath.stepDelay(delayShown, 5))
                                    1 -> applyDelay(DelayMath.stepDelay(delayShown, -5))
                                    2 -> {
                                        val r = revealClock.calibrate()
                                        if (r is DelayMath.Calibrate.Set) applyDelay(r.sec)
                                        delayNote = DelayMath.calibrateMessage(r)
                                        JellytipTrace.player("iptv", "delay calibrate ${channel.id}: $r")
                                    }
                                    else -> applyDelay(me?.sportDelaySec ?: DelayMath.DEFAULT_DELAY_SEC)
                                }
                            }
                        Key.Back, Key.Escape -> {
                            delaySheetOpen = false
                            focus.tryRequestFocus()
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (chipsOpen) {
                    when (e.key) {
                        Key.DirectionLeft -> chipCursor = (chipCursor - 1).coerceAtLeast(0)
                        Key.DirectionRight -> chipCursor = (chipCursor + 1).coerceAtMost(chipChannels.size - 1)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                val ch = chipChannels.getOrNull(chipCursor)
                                chipsOpen = false
                                if (ch != null && ch.id != channel.id) {
                                    JellytipTrace.player("iptv", "channel chip ${channel.id} -> ${ch.id}")
                                    JellytipSportEvents.logChannel(SportEventRules.WATCH, ch.id, SportEventRules.PLAYER_SWITCHER) { viewModel.service.fixtures(maxAgeMs = 60_000)?.second?.items }
                                    tried += ch.id
                                    fallback = if (ch.id == lineup[index].id) null else ch
                                    current = IptvTarget(ch.id, ch.name)
                                    playback.play(current, "channel chip")
                                    bannerTick++
                                }
                            }
                        Key.DirectionUp, Key.DirectionDown, Key.Back, Key.Escape -> {
                            chipsOpen = false
                            focus.tryRequestFocus()
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.key == Key.Captions || e.key == Key.MediaAudioTrack) {
                    openTrackSheet()
                    return@onPreviewKeyEvent true
                }
                if (matchModal != null || preMenu || panelMenu) return@onPreviewKeyEvent false
                // The side panel takes Left (close) and Up/Down (tabs) while it is open; the video keeps playing
                if (panelOpen && openedBy != BreakModePolicy.Reason.USER) {
                    // Opened by itself: a press already under way keeps its old meaning (swallowed); after that any key
                    // means the viewer is using it, so it stays until they close it
                    if (e.key != Key.Back && (System.currentTimeMillis() - openedAt[0] < BreakModePolicy.KEY_GUARD_MS || (e.nativeKeyEvent.repeatCount > 0 && !panelTouched))) {
                        return@onPreviewKeyEvent true
                    }
                    panelTouched = true
                }
                if (panelOpen) {
                    when (e.key) {
                        Key.DirectionLeft -> {
                            closePanelByUser("Left")
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            panelTab = (panelTab - 1 + panelTabs.size) % panelTabs.size
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            panelTab = (panelTab + 1) % panelTabs.size
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            // Right inside the panel: Adjust delay (the chip above the panel says so)
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                delayCursor = 0
                                delaySheetOpen = true
                                delaySheetAt[0] = System.currentTimeMillis()
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.nativeKeyEvent.repeatCount > 0) {
                                closerArmed[0] = false
                                if (!panelMenu) panelMenu = true
                            } else {
                                closerArmed[0] = true
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                // Match info (plan "Match info"): the remote's Info key, or holding OK, on a programme with a fixture
                val matchNow = sourceMatch?.takeIf { !MatchInfoRoute.missing && !MatchHidden.hides(it.id) }
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (matchNow != null && (e.key == Key.Info || (isOk && e.nativeKeyEvent.repeatCount > 0))) {
                    matchModal = matchNow
                    return@onPreviewKeyEvent true
                }
                if (preStartsAt != null) {
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && preCardVisible) {
                        preMenu = true
                        return@onPreviewKeyEvent true
                    }
                    preCardTick++
                }
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown -> {
                        if (e.key == Key.DirectionDown && current.isLive && chipChannels.size > 1) {
                            chipCursor = chipChannels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
                            chipsOpen = true
                            JellytipTrace.player("iptv", "channel chips ${chipChannels.size} for ${lineup[index].id}")
                            return@onPreviewKeyEvent true
                        }
                        if (lineup.size > 1) {
                            val step = if (e.key == Key.DirectionUp || e.key == Key.ChannelDown || e.key == Key.PageUp) -1 else 1
                            index = Math.floorMod(index + step, lineup.size)
                            JellytipTrace.log("iptv", "zap ${if (step < 0) "up" else "down"} to ${lineup[index].name}")
                        }
                        bannerTick++
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.nativeKeyEvent.repeatCount > 0) {
                            true
                        } else if (bannerArmed) {
                            openTrackSheet()
                            bannerArmed = false
                        } else if (bannerVisible) {
                            bannerVisible = false
                            bannerArmed = true
                        } else {
                            bannerArmed = true
                            bannerTick++
                        }
                        true
                    }

                    // From kick-off: restart what is on now (catch-up from its start); Right goes back to live
                    Key.DirectionLeft, Key.MediaRewind -> {
                        val prog = nowNext?.now?.takeIf { it.canStartOver && it.isOn(nowSec()) }
                        if (current.isLive && prog != null) {
                            JellytipTrace.player("iptv", "from kick-off ${channel.id} ${prog.title}")
                            current = IptvTarget(channel.id, prog.title, prog.start, prog.stop)
                            playback.play(current, "from kick-off")
                            bannerTick++
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionRight, Key.MediaFastForward -> {
                        if (!current.isLive && current.channelId == channel.id) {
                            current = IptvTarget(channel.id, channel.name)
                            playback.play(current, "back to live")
                            bannerTick++
                            true
                        } else if (e.key == Key.DirectionRight && panelMatch != null && matchModal == null && !preMenu) {
                            // Live: Right opens the in-event panel (Right was unused here)
                            panelOpen = true
                            openedBy = BreakModePolicy.Reason.USER
                            JellytipTrace.player("iptv", "companion opened ${panelMatch.id} (${if (me?.sportPanelStyle == "floating") "overlay" else "squeeze"})")
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }.focusable(),
    ) {
        val player = playback.player
        // Squeeze-back (web companion spike: one layout change per open/close): docked panel = the picture moves to the
        // left 72 % at 16:9 and the panel takes the right 28 %; floating keeps the full-screen picture with the overlay
        val squeeze = panelOpen && panelMatch != null && me?.sportPanelStyle != "floating"
        if (player != null) {
            Box(
                if (squeeze) Modifier.align(Alignment.CenterStart).fillMaxWidth(0.72f).aspectRatio(16f / 9f) else Modifier.fillMaxSize(),
            ) {
                PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.fillMaxSize())
                AndroidView(
                    factory = { SubtitleView(it) },
                    update = { it.setCues(subtitleCues) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Stream recovery's calm line ("Reconnecting… (2 of 4)", "Stream is buffering…") wins while it has one
        val recoveryNote by playback.note.collectAsState()
        val status =
            recoveryNote ?: when (val s = playState) {
                is IptvPlayState.Opening -> if (s.target.channelId == channel.id) "Tuning ${channel.name}…" else null
                is IptvPlayState.Full -> s.message
                is IptvPlayState.Failed -> if (s.canRetry) "${s.message}\nOK: try again" else s.message
                IptvPlayState.Idle -> "Tuning ${channel.name}…"
                is IptvPlayState.Playing -> null
            }
        status?.let {
            // The recovery / tuning note: a Broadcast-styled card (gold edge) that rises in each time the line changes
            Text(
                text = it,
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontSize = 20.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .jellytipEnter(it, rise = 10.dp)
                        .background(Broadcast.Base.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .border(1.dp, Broadcast.Gold.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        StreamBusyPopup(playState, playback)
        // Slides in from the right (260 ms, decelerate) and out (200 ms, accelerate); kept composed until it is off screen
        val open = panelOpen && panelMatch != null
        LaunchedEffect(open, panelMatch?.id) { if (open) shownPanelMatch = panelMatch }
        // Read in graphicsLayer only (the page must not recompose every animation frame)
        val panelProgress = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (open) 1f else 0f,
            animationSpec =
                if (open) {
                    androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
                } else {
                    androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                },
            label = "live-panel",
        )
        val panelShown by remember { derivedStateOf { panelProgress.value > 0f } }
        val pm = if (open) panelMatch else shownPanelMatch
        if (pm != null && panelShown) {
            JellytipLivePanel(
                pm,
                tab = panelTab,
                spoilerNotice = spoilerNotice,
                data = liveData.takeUnless { replayShown },
                tabs = panelTabs,
                fantasy = fantasyShown.takeUnless { replayShown },
                flash = liveFlash,
                floating = me?.sportPanelStyle == "floating",
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(if (me?.sportPanelStyle == "floating") 0.32f else 0.28f),
                progress = { panelProgress.value },
                fav = favSide,
                closer = closerGame,
                allowWinProb = me?.sportSpoilers == true,
                reveal = { revealClock.reveal(it) },
                // The delay chip rides on the panel's first row (it shows only with the panel open; never over it)
                topRow = { DelayChipText(replay = { replayShown }, hold = { holdSecs }, delaySec = { delayShown }) },
            )
        }
        // Round 3 cards: full time (bottom), catch me up (centre)
        ftCard?.let { (m, next) ->
            FullTimeCard(m, ftScore?.first, ftScore?.second, next, nowSec(), Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        }
        if (catchOpen) {
            panelMatch?.let { m ->
                CatchUpChoiceCard(m, CatchUpPolicy.joiningLine(m.startsAt ?: nowSec(), nowSec()), catchOptions, CatchUpPolicy.catchUpLine(catchReady.size), catchCursor, { catchLeft }, Modifier.align(Alignment.Center))
            }
        }
        catchCards?.let { cs ->
            panelMatch?.let { m -> CatchUpRecapCard(m, cs[catchIndex.coerceIn(0, cs.size - 1)], catchIndex, cs.size, Modifier.align(Alignment.Center)) }
        }
        if (chipsOpen) {
            ChannelChips(
                chipChannels,
                currentId = channel.id,
                cursor = chipCursor,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
            )
        }
        if (fullOpen) {
            fullCardNow?.let { fc -> FullScorecardSheet(fc, fullTab, fullScroll, Modifier.align(Alignment.Center)) }
        }
        if (delaySheetOpen) {
            DelaySheet(
                delaySec = delayShown,
                defaultSec = me?.sportDelaySec ?: DelayMath.DEFAULT_DELAY_SEC,
                cursor = delayCursor,
                canCalibrate = remember(delayShown, bugData, delayNote) { revealClock.canCalibrate() },
                replay = replayShown,
                note = delayNote,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (panelMenu) {
            val floating = me?.sportPanelStyle == "floating"
            val pm = panelMatch
            com.github.damontecres.wholphin.jellytip.JellytipDialog(
                showDialog = true,
                title = "Live panel",
                dialogItems =
                    listOfNotNull(
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (floating) "Docked to the edge" else "Docked to the edge  ✓", dismissOnClick = true) {
                            com.github.damontecres.wholphin.jellytip.me.JellytipMe.setPanelStyle("docked")
                        },
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (floating) "Floating  ✓" else "Floating", dismissOnClick = true) {
                            com.github.damontecres.wholphin.jellytip.me.JellytipMe.setPanelStyle("floating")
                        },
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (breakModeOn) "Open by itself at breaks  ✓" else "Open by itself at breaks", dismissOnClick = true) {
                            breakModeOn = !breakModeOn
                            sportPrefs.edit().putBoolean("breakMode", breakModeOn).apply()
                            JellytipTrace.player("iptv", "break mode ${if (breakModeOn) "on" else "off"}")
                        },
                        com.github.damontecres.wholphin.ui.components.DialogItem("Spoiler delay: $delayShown s — Adjust", dismissOnClick = true) {
                            delayCursor = 0
                            delaySheetOpen = true
                        },
                        matchInfoItem(pm) { matchModal = it },
                    ),
                onDismissRequest = { panelMenu = false },
            )
        }
        // Toast: a Broadcast lower third, top right; slides in from the right (240 ms), never takes focus; the queue
        // above times it
        val toast = flipToast
        val toastIn = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (toast != null) 1f else 0f,
            animationSpec =
                androidx.compose.animation.core.tween(
                    if (toast != null) 240 else 200,
                    easing = if (toast != null) androidx.compose.animation.core.LinearOutSlowInEasing else androidx.compose.animation.core.FastOutLinearInEasing,
                ),
            label = "player-toast",
        )
        LaunchedEffect(toast) { if (toast != null) lastToast = toast }
        val shownToast = toast ?: lastToast
        val toastShown by remember { derivedStateOf { toastIn.value > 0f } }
        // Banner extras (only while the now/next banner shows): the key hint and the fantasy chip
        val bannerCatchUp = current.takeIf { it.start != null && it.stop != null && it.channelId == channel.id }
        val hintItems: List<String> =
            if (!bannerVisible) {
                emptyList()
            } else if (bannerCatchUp != null) {
                listOf("▶  Right: back to live")
            } else {
                val kick = nowNext?.now?.let { it.canStartOver && it.isOn(now) } == true
                val info = sourceMatch?.let { !MatchHidden.hides(it.id) } == true && !MatchInfoRoute.missing
                listOfNotNull(
                    if (kick) "◀  From kick-off" else null,
                    if (panelMatch != null) "→  Companion" else null,
                    if (current.isLive && chipChannels.size > 1) "▼  Other channels" else null,
                    if (info) "Hold OK: match info" else null,
                )
            }
        // Fantasy chip "🏈 CCP 87.4 – 79.2" (the league with starters in this game, else the first)
        val fantasyText =
            if (!bannerVisible || nflMatch == null) null else JellytipFantasy.leagueFor(fantasyShown.takeUnless { replayShown }, nflMatch?.id)?.let { l -> "🏈  ${l.short} ${l.scoreLine}" }
        PlayerCornerOverlays(
            // Shown on the panel's first row instead (JellytipLivePanel topRow)
            delayChip = null,
            // Scorebug (web Companion/Scorebug): the newest released score, 8 s after a change; never in the panel's place.
            // Its own composable reading lambdas, so the running clock doesn't recompose this page every second.
            scorebug = {
                ScorebugSlot(
                    match = panelMatch,
                    bug = { bugData },
                    clock = { bugClock },
                    changedAt = { bugChangedAt },
                    allowed = { !replayShown && !delaySheetOpen && !panelOpen && !catchPending && !catchOpen && catchCards == null && ftCard == null && !(bannerVisible && nflMatch != null) },
                )
            },
            toast = shownToast?.takeIf { toastShown },
            toastProgress = { toastIn.value },
            fantasy = fantasyText,
            hint = hintItems,
            trackMessage = trackMessage.takeIf { bannerVisible },
        )
        matchModal?.let { m ->
            JellytipMatchModal(
                m,
                onWatch = { ch ->
                    matchModal = null
                    if (ch.id != channel.id) {
                        tried += ch.id
                        fallback = ch
                        current = IptvTarget(ch.id, ch.name)
                        playback.play(current, "match info watch")
                        bannerTick++
                    }
                },
                onDismiss = { matchModal = null },
            )
        }
        if (preCardVisible && preStartsAt != null) {
            // Broadcast pre-game card: gold tag, the start in condensed caps, the hint in Barlow
            val pm = nowNext?.liveMatch
            val pal = remember(pm?.id) { matchPalette(pm) }
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 48.dp, bottom = if (bannerVisible) 150.dp else 40.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .background(pal.wash, alpha = 0.96f),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(Broadcast.Gold))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cond("Pre-game", 13.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 7.dp, vertical = 1.dp), tracking = 0.06f)
                        pm?.matchup?.let { Cond(it, 17.sp, pal.onHomeWash) }
                    }
                    Cond("Starts ${hhmm(preStartsAt)} · ${startsLabel(preStartsAt, now).substringAfter("· ")}", 20.sp, Color.White)
                    BodyText("OK: Remind me · Other channels", 12.sp, Broadcast.Secondary)
                }
            }
        }
        if (preMenu && preProg != null && preStartsAt != null) {
            val remindersOn by viewModel.reminders.available.collectAsState()
            val others = playback.alternates[lineup[index].id].orEmpty().filter { it.id != channel.id }
            val items =
                buildList {
                    if (remindersOn == true) {
                        add(
                            com.github.damontecres.wholphin.ui.components.DialogItem("Remind me", dismissOnClick = true) {
                                viewModel.reminders.add(
                                    IptvUpcoming(
                                        key = "${channel.id}@$preStartsAt",
                                        title = preProg.title,
                                        league = null,
                                        favourite = false,
                                        start = preStartsAt,
                                        stop = preProg.stop,
                                        kind = preProg.kind,
                                        channel = channel,
                                        alternateChannels = others,
                                        recordable = false,
                                        recordingId = null,
                                    ),
                                )
                                android.widget.Toast.makeText(context, "Reminder set", android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    others.forEach { ch ->
                        val name = ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
                        add(
                            com.github.damontecres.wholphin.ui.components.DialogItem("Watch on $name", dismissOnClick = true) {
                                tried += ch.id
                                fallback = ch
                                current = IptvTarget(ch.id, ch.name)
                                playback.play(current, "pre-game other channel")
                                bannerTick++
                            },
                        )
                    }
                }
            if (items.isEmpty()) {
                preMenu = false
            } else {
                com.github.damontecres.wholphin.jellytip.JellytipDialog(showDialog = true, title = preProg.title, dialogItems = items, onDismissRequest = { preMenu = false })
            }
        }
        if (bannerVisible) {
            val cu = current.takeIf { it.start != null && it.stop != null && it.channelId == channel.id }
            val shown = if (cu != null) IptvNowNext(IptvProg(cu.start!!, cu.stop!!, cu.label, null, true), null) else nowNext
            NowNextBanner(channel, shown, now, Modifier.align(Alignment.BottomStart), catchUp = cu != null)
        }
        if (trackSheetOpen) {
            Column(
                // 48 dp from the right: the safe area (audit P2)
                modifier = Modifier.align(Alignment.CenterEnd).padding(start = 32.dp, top = 32.dp, bottom = 32.dp, end = 48.dp).widthIn(min = 320.dp, max = 440.dp)
                    .fillMaxHeight(0.85f).background(Broadcast.Base.copy(alpha = 0.97f), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Audio & subtitles", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Audio", color = Broadcast.Gold, fontFamily = JellytipFontFamily, fontSize = 15.sp)
                if (audioTracks.isEmpty()) Text("No selectable audio tracks yet", color = Broadcast.Secondary, fontFamily = JellytipFontFamily, fontSize = 14.sp)
                audioTracks.forEachIndexed { i, choice -> TrackSheetRow(choice.label(), choice.selected, trackCursor == i) }
                Text("Subtitles", color = Broadcast.Gold, fontFamily = JellytipFontFamily, fontSize = 15.sp)
                TrackSheetRow("Off", subtitleTracks.none { it.selected }, trackCursor == audioTracks.size)
                subtitleTracks.forEachIndexed { i, choice -> TrackSheetRow(choice.label(), choice.selected, trackCursor == audioTracks.size + 1 + i) }
            }
        }
    }
}

@Composable
private fun TrackSheetRow(label: String, selected: Boolean, focused: Boolean) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bringIntoView.bringIntoView() }
    Text(
        text = "${if (selected) "✓  " else "    "}$label",
        color = Color.White,
        fontFamily = JellytipFontFamily,
        fontSize = 16.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .background(if (focused) JellytipPalette.Jelly.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun NowNextBanner(
    ch: IptvChannel,
    nn: IptvNowNext?,
    now: Long,
    modifier: Modifier,
    catchUp: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp, 64.dp), contentAlignment = Alignment.Center) {
                var failed by remember(ch.logo) { mutableStateOf(false) }
                if (ch.logo != null && !failed) {
                    AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                } else {
                    Text(initials(ch.name), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                val p = nn?.now
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!catchUp) KindPill(p?.shownKind)
                    if (ch.sport == true || p?.sport == true) SportMark(JellytipPalette.Vanilla.copy(alpha = 0.75f), 14.dp)
                    Text(
                        text = listOfNotNull(ch.num?.toString(), ch.name, if (catchUp) "Catch-up" else null).joinToString("  "),
                        color = JellytipPalette.Vanilla.copy(alpha = 0.75f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                    QualityChips(ch.quality)
                }
                Text(
                    text = p?.title ?: "No guide information",
                    color = JellytipPalette.Vanilla,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${hhmm(p.start)}–${hhmm(p.stop)}", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                        val frac = if (catchUp) 0f else ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (!catchUp) Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(JellytipPalette.Jelly))
                        }
                    }
                }
                nn?.next?.let { n ->
                    Text(
                        text = "Next  ${hhmm(n.start)}  ${n.title}",
                        color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** What the live poll has already shown: the last flip, moments (server or inferred from the score); the first answer primes. */
private class LiveSeen {
    var flipAt: String? = null
    private var primed = false
    private val moments = HashSet<String>()
    private var lastHome: Int? = null
    private var lastAway: Int? = null

    fun newMoments(
        d: MatchLive,
        m: IptvMatch,
    ): List<LiveMoment> {
        val first = !primed
        primed = true
        val out = ArrayList<LiveMoment>()
        if (d.hasMoments) {
            d.moments.forEach { mo -> if (moments.add("${mo.at}|${mo.label}") && !first) out += mo }
        }
        val h = d.scoreHome?.toIntOrNull()
        val a = d.scoreAway?.toIntOrNull()
        if (h != null && a != null) {
            val ph = lastHome
            val pa = lastAway
            // No `moments` from this server: a lead change, or an NFL touchdown / field goal, from the score
            if (!d.hasMoments && !first && ph != null && pa != null && (h != ph || a != pa)) {
                val before = Integer.signum(ph - pa)
                val now = Integer.signum(h - a)
                val team = if (h > ph) "home" else if (a > pa) "away" else null
                val delta = maxOf(h - ph, a - pa)
                when {
                    before != 0 && now != 0 && before != now -> out += LiveMoment("score:$h-$a", "lead_change", team, "LEAD CHANGE")
                    isNfl(m) && delta in 6..8 -> out += LiveMoment("score:$h-$a", "touchdown", team, "TOUCHDOWN")
                    isNfl(m) && delta == 3 -> out += LiveMoment("score:$h-$a", "field_goal", team, "FIELD GOAL")
                }
            }
            lastHome = h
            lastAway = a
        }
        return out
    }
}
