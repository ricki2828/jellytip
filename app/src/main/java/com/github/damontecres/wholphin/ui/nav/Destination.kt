@file:UseSerializers(UUIDSerializer::class)

package com.github.damontecres.wholphin.ui.nav

import androidx.navigation3.runtime.NavKey
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.RequestHandler
import com.github.damontecres.wholphin.util.SEERR_PAGE_SIZE
import kotlinx.serialization.Contextual
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/**
 * Represents a page in the app
 *
 * @param fullScreen whether the page should be full page aka not include the nav drawer
 */
@Serializable
sealed class Destination(
    val fullScreen: Boolean = false,
) : NavKey {
    @Serializable
    data class Home(
        val id: Long = 0L,
    ) : Destination()

    @Serializable
    data object HomeSettings : Destination(true)

    @Serializable
    data class Settings(
        val screen: PreferenceScreenOption,
    ) : Destination(true)

    @Serializable
    data class SubtitleSettings(
        val hdr: Boolean,
    ) : Destination(true)

    @Serializable
    data class Search(
        val query: String = "",
    ) : Destination()

    data object UserAppPreferences : Destination(true)

    @Serializable
    data class SeriesOverview(
        val itemId: UUID,
        val type: BaseItemKind,
        val seasonEpisode: SeasonEpisodeIds? = null,
    ) : Destination() {
        override fun toString(): String = "SeriesOverview(itemId=$itemId, type=$type, seasonEpisode=$seasonEpisode)"
    }

    @Serializable
    data class MediaItem(
        val itemId: UUID,
        val type: BaseItemKind,
        val collectionType: CollectionType? = null,
        val initialSongId: UUID? = null,
    ) : Destination() {
        constructor(item: BaseItem) : this(item.id, item.type, item.data.collectionType)
    }

    @Serializable
    data class Recordings(
        val itemId: UUID,
    ) : Destination()

    @Serializable
    data class Playback(
        val itemId: UUID,
        val positionMs: Long,
        val forceTranscoding: Boolean = false,
        val backend: PlayerBackend? = null,
        val shuffle: Boolean = false,
        /** Jellytip "Play both" (double feature): item id to queue right after this one. */
        val jellytipThenItemId: String? = null,
    ) : Destination(true) {
        constructor(item: BaseItem) : this(item.id, item.resumeMs)
    }

    @Serializable
    data class PlaybackList(
        val itemId: UUID,
        val filter: GetItemsFilter = GetItemsFilter(),
        val startIndex: Int? = null,
        val shuffle: Boolean = false,
        val recursive: Boolean = false,
        val sortAndDirection: SortAndDirection? = null,
    ) : Destination(true) {
        override fun toString(): String = "PlaybackList(itemId=$itemId)"
    }

    @Serializable
    data class FilteredCollection(
        val itemId: UUID,
        val parentType: BaseItemKind,
        val filter: CollectionFolderFilter,
        val recursive: Boolean,
        val collectionType: CollectionType,
    ) : Destination(false)

    @Serializable
    data class ItemGrid<T>(
        val title: StringProvider,
        @Contextual val request: T,
        val requestHandler: RequestHandler<T>,
        val initialPosition: Int = 0,
        val viewOptions: ViewOptions = ViewOptions(),
    ) : Destination(false)

    @Serializable
    data class MoreHomeRow(
        val title: StringProvider,
        val config: HomeRowConfig,
        val initialPosition: Int,
    ) : Destination(false)

    @Serializable
    data class Slideshow(
        val parentId: UUID,
        val index: Int,
        val filter: CollectionFolderFilter,
        val sortAndDirection: SortAndDirection,
        val recursive: Boolean,
        val startSlideshow: Boolean,
    ) : Destination(true)

    @Serializable
    data object Favorites : Destination(false)

    @Serializable
    data object Discover : Destination(false)

    @Serializable
    data class DiscoveredItem(
        val item: DiscoverItem,
        // Index to scroll to on the person page grid
        val startIndex: Int = 0,
    ) : Destination(false)

    @Serializable
    data class DiscoverMoreResult(
        val type: DiscoverRequestType,
        val startIndex: Int = SEERR_PAGE_SIZE,
        val initialFilter: DiscoverFilter = DiscoverFilter(),
        val titleOverride: StringProvider? = null,
    ) : Destination(false)

    /** Jellytip: a Discover title that is not in the library yet, with a "Get it" button. */
    @Serializable
    data class JellytipDiscover(
        val title: DiscoverTitle,
    ) : Destination(false)

    /** Jellytip: a Letterboxd list the companion tracks (its films in the list's order). */
    @Serializable
    data class JellytipList(
        val slug: String,
        val name: String,
    ) : Destination(false)

    // ---- Jellytip lane B (tvparity-library) destinations: start ----
    /** Jellytip: Pick for tonight (three picks with reasons; web 7056c179). */
    @Serializable
    data object JellytipTonight : Destination(false)

    /** Jellytip: availability inbox (new episodes, requests that arrived; web de17f4d4). */
    @Serializable
    data object JellytipInbox : Destination(false)
    // ---- Jellytip lane B (tvparity-library) destinations: end ----

    /** Jellytip personalisation: hub (settings that follow the person), taste, Home edit. */
    @Serializable
    data object JellytipMe : Destination(false)

    @Serializable
    data object JellytipTaste : Destination(false)

    @Serializable
    data object JellytipHomeEdit : Destination(false)

    /** Jellytip: Settings → Live sport (teams, leagues, sports, scores, alerts, delay, Not interested). */
    @Serializable
    data object JellytipSportSettings : Destination(false)

    /** Jellytip: fantasy matchups (leagues, line-ups, Watch a player's game). */
    @Serializable
    data object JellytipFantasy : Destination(false)

    /** Jellytip: the person's game reminders and team reminders, with Remove / Stop (web #/reminders). */
    @Serializable
    data object JellytipRemindersPage : Destination(false)

    /** Jellytip: the IPTV Live TV guide (sidebar "Live TV"). */
    @Serializable
    data object JellytipLiveTv : Destination(false)

    /** Jellytip: the Live TV page opened on its Recordings tab (sidebar "Recordings", Home recordings sheet). */
    @Serializable
    data object JellytipRecordings : Destination(false)

    /** Jellytip: the Collections hub (sidebar "Collections"): section chips and a grid of collections. */
    @Serializable
    data object JellytipCollections : Destination(false)

    /** Jellytip: one hub collection (`GET /v1/collections/{id}`, a row), as a poster grid. */
    @Serializable
    data class JellytipHubCollection(
        val id: String,
        val title: String,
    ) : Destination(false)

    /** Jellytip: upcoming sport over the next days, grouped by day (sidebar, Live TV). */
    @Serializable
    data object JellytipUpcomingSport : Destination(false)

    // ---- t5n wow extras (21-Nov): Movie night host screen + My Channels (sidebar) ----

    /** Jellytip: movie night, the TV hosts (code + live tally), phones vote in the web app. */
    @Serializable
    data object JellytipMovieNight : Destination(false)

    /** Jellytip: My Channels, personal always-on channels (web #/channels). */
    @Serializable
    data object JellytipMyChannels : Destination(false)

    // ---- t5n (21-Nov): Ask (plain-language discovery, web #/ask) + Journeys (web #/journeys) ----

    /** Jellytip: Ask, "something funny under 90 minutes" -> chips -> library picks ("Not sure? Ask" on Search). */
    @Serializable
    data object JellytipAsk : Destination(false)

    /** Jellytip: Journeys, short ordered film paths (franchise, director, Letterboxd list) ("Start a journey" on Collections). */
    @Serializable
    data object JellytipJourneys : Destination(false)

    /** Jellytip: one journey ("f:<tmdbId>", "d:<personId>", "l:<slug>"): films in order, watched ticks, Play next. */
    @Serializable
    data class JellytipJourney(
        val id: String,
    ) : Destination(false)

    // ---- end t5n ----

    /** Jellytip: an IPTV channel full screen (zapping with Up/Down through the guide tab it came from). */
    @Serializable
    data class JellytipLiveTvPlayer(
        val channelId: String,
        /** Jellytip: the match a Game Centre live launch is for (companion panel / scorebug); null = resolve from the guide. */
        val matchId: String? = null,
    ) : Destination(true)

    /** Jellytip: an IPTV film or series ("via IPTV" details with Play / Resume, seasons and episodes). */
    @Serializable
    data class JellytipIptvDetails(
        val id: String,
        val title: String,
    ) : Destination(false)

    /** Jellytip: an IPTV recording, film or series episode full screen. */
    @Serializable
    data class JellytipIptvPlay(
        val title: String,
        val recordingId: String? = null,
        val vodId: String? = null,
        val seriesId: String? = null,
        val episodeId: String? = null,
        val resumeMs: Long = 0,
    ) : Destination(true)

    /** Jellytip: a franchise (TMDB collection) page: series hero, blurb, progress, films in series order. */
    @Serializable
    data class JellytipCollection(
        val tmdbId: Int,
        val name: String,
    ) : Destination(false)

    @Serializable
    data object NowPlaying : Destination(true)

    @Serializable
    data object UpdateApp : Destination(true)

    @Serializable
    data object License : Destination(true)

    @Serializable
    data object Debug : Destination(true)
}
