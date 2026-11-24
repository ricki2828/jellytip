package com.github.damontecres.wholphin.jellytip.kids

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cards.selectJellytipCardArt
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvVodService
import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry
import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.SlimItemFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---- Details models (plain values: the details content composable and the shots take these) ----

data class KidsChip(
    val text: String,
    /** The age/rating chip is drawn in the section colour. */
    val rating: Boolean = false,
)

data class KidsEpisode(
    val id: String,
    val number: Int,
    val title: String,
    val imageUrl: String?,
    val progress: Float,
    val played: Boolean,
)

data class KidsSeason(
    val number: Int,
    val label: String,
    val episodes: List<KidsEpisode>,
)

/** What the big Play button plays: a library item id and where to start. */
data class KidsPlayTarget(
    val itemId: String,
    val resumeMs: Long,
)

class KidsCollection(
    val name: String,
    val tiles: List<KidsTile>,
)

class KidsDetails(
    val id: String,
    val isSeries: Boolean,
    val title: String,
    val chips: List<KidsChip>,
    val overview: String?,
    val imageUrl: String?,
    val playLabel: String,
    val playSub: String?,
    val favourite: Boolean,
    val seasons: List<KidsSeason>,
    /** Index into [seasons] to open on (the one the Play button is in). */
    val initialSeason: Int,
    val playTarget: KidsPlayTarget?,
)

/** Which episode a series' Play button plays. Pure, so it is unit tested. */
object KidsPlayPick {
    data class Ep(
        val id: String,
        val season: Int,
        val number: Int,
        val played: Boolean,
        val resumeMs: Long,
    )

    data class Pick(
        val episode: Ep,
        /** A part-watched episode: the button says "Keep going!" and starts at its position. */
        val resume: Boolean,
    )

    /**
     * 1. a part-watched episode (the first in order), 2. the first unplayed one after the last played, 3. the first episode.
     * Specials (season 0) can be resumed but are not part of the order in 2 and 3 unless the series has nothing else.
     */
    fun pick(eps: List<Ep>): Pick? {
        if (eps.isEmpty()) return null
        val sorted = eps.sortedWith(compareBy({ it.season }, { it.number }))
        sorted.firstOrNull { it.resumeMs > 0 && !it.played }?.let { return Pick(it, true) }
        val ordered = sorted.filter { it.season != 0 }.ifEmpty { sorted }
        val lastPlayed = ordered.indexOfLast { it.played }
        if (lastPlayed >= 0) {
            ordered.drop(lastPlayed + 1).firstOrNull { !it.played }?.let { return Pick(it, false) }
            // Everything is watched: start again from the top
            return Pick(ordered.first(), false)
        }
        return Pick(ordered.first(), false)
    }
}

/** "1h 32m" / "11 min" from a runtime in minutes. */
fun kidsRuntime(minutes: Long): String? =
    when {
        minutes <= 0 -> null
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "$minutes min"
    }

/**
 * The library side of the Kids shell: art-first Shows / Movies grids, My list, and the details page data. Every call goes with
 * the signed-in (kid's) token, so Jellyfin's own parental filter already applies; nothing here widens it. Failures return
 * null / empty and are logged: a page that cannot load shows "nothing here yet", never an adult page.
 */
@Singleton
class KidsLibraryService
    @Inject
    constructor(
        private val api: ApiClient,
        private val images: ImageUrlService,
        private val myListService: JellytipMyListService,
        private val jellytipApi: JellytipApi,
        private val favourites: FavoriteWatchManager,
        private val vod: JellytipIptvVodService,
    ) {
        /** Resolver for the server's `{itemId, kind}` image refs. */
        val imageResolver =
            KidsImageResolver { itemId, kind, wide ->
                val uuid = runCatching { itemId.toUUID() }.getOrNull() ?: return@KidsImageResolver null
                when (kind) {
                    "Thumb" -> images.getItemImageUrl(itemId = uuid, imageType = ImageType.THUMB, fillWidth = if (wide) 1280 else 328, fillHeight = if (wide) 480 else 184)
                    "Backdrop" -> images.getItemImageUrl(itemId = uuid, imageType = ImageType.BACKDROP, fillWidth = if (wide) 1280 else 328, fillHeight = if (wide) 480 else 184)
                    else -> images.getItemImageUrl(itemId = uuid, imageType = ImageType.PRIMARY, maxWidth = if (wide) 1280 else 400)
                }
            }

        /** `GET /v1/kids/home`: 403 / error / old server = [KidsHomeResult.Unavailable] (hide the rows). */
        suspend fun home(): KidsHomeResult {
            val base = vod.base()
            val (code, body) = jellytipApi.userCall("v1/kids/home") ?: return KidsHomeResult.Unavailable.also { Timber.w("[kids] home: no answer") }
            if (code != 200) {
                Timber.w("[kids] home: HTTP %d", code)
                return KidsHomeResult.Unavailable
            }
            val data = KidsHomeParse.parse(body, base, imageResolver)
            if (data == null) Timber.w("[kids] home: not a kids answer")
            return data?.let { KidsHomeResult.Ok(it) } ?: KidsHomeResult.Unavailable
        }

        private fun kindOf(type: BaseItemKind?): KidsKind? =
            when (type) {
                BaseItemKind.MOVIE -> KidsKind.MOVIE
                BaseItemKind.SERIES -> KidsKind.SERIES
                BaseItemKind.EPISODE -> KidsKind.EPISODE
                else -> null
            }

        private fun tileOf(dto: BaseItemDto): KidsTile? {
            val kind = kindOf(dto.type) ?: return null
            val name = dto.name?.takeIf { it.isNotBlank() } ?: return null
            val pct = dto.userData?.playedPercentage ?: 0.0
            return KidsTile(
                id = dto.id.toString(),
                source = "library",
                kind = kind,
                name = name,
                seriesName = dto.seriesName,
                seriesId = dto.seriesId?.toString(),
                progress = if (pct > 0.0 && pct < 100.0) (pct / 100.0).toFloat() else 0f,
                imageUrl = selectJellytipCardArt(images, BaseItem(dto), 328, 184).baseUrl,
            )
        }

        /** The library's series or films by name, as tiles. */
        suspend fun browse(type: BaseItemKind): List<KidsTile> =
            try {
                val request =
                    GetItemsRequest(
                        includeItemTypes = listOf(type),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.ASCENDING),
                        fields = SlimItemFields,
                        enableUserData = true,
                        limit = BROWSE_LIMIT,
                        enableTotalRecordCount = false,
                    )
                api.itemsApi
                    .getItems(request)
                    .content.items
                    .mapNotNull { tileOf(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[kids] browse %s failed", type)
                emptyList()
            }

        /** The existing My list data (`/v1/me/list`), films then shows, library and IPTV, in the kids skin. */
        suspend fun myList(): List<KidsTile> =
            try {
                val movies = myListService.list("movie", MY_LIST_LIMIT).orEmpty()
                val series = myListService.list("series", MY_LIST_LIMIT).orEmpty()
                if (movies.size >= MY_LIST_LIMIT || series.size >= MY_LIST_LIMIT) Timber.w("[kids] my list hit the %d cap", MY_LIST_LIMIT)
                val entries = movies + series
                val libIds = entries.filterIsInstance<JellytipMyListEntry.Library>().map { it.id }.distinct()
                val byId =
                    if (libIds.isEmpty()) {
                        emptyMap()
                    } else {
                        api.itemsApi
                            .getItems(
                                GetItemsRequest(
                                    ids = libIds,
                                    fields = SlimItemFields,
                                    enableUserData = true,
                                    enableTotalRecordCount = false,
                                ),
                            ).content.items
                            .associateBy { it.id }
                    }
                entries.mapNotNull { entry ->
                    when (entry) {
                        is JellytipMyListEntry.Library -> byId[entry.id]?.let { tileOf(it) }
                        // No kids page for an IPTV series: it would be a dead tile, so it is not drawn
                        is JellytipMyListEntry.Iptv ->
                            if (entry.item.kind == "series") null else KidsTile(
                                id = entry.item.id,
                                source = "iptv",
                                kind = KidsKind.MOVIE,
                                name = entry.item.title,
                                imageUrl = entry.item.backdrop ?: entry.item.poster,
                            )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[kids] my list failed")
                emptyList()
            }

        /** A friend's collection (BoxSet): its name and its films and shows, by the kid's own token (Jellyfin filters). Null = could not load. */
        suspend fun collection(id: UUID): KidsCollection? =
            try {
                val box = api.userLibraryApi.getItem(id).content
                if (box.type != BaseItemKind.BOX_SET) {
                    null
                } else {
                    val request =
                        GetItemsRequest(
                            parentId = id,
                            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            sortOrder = listOf(SortOrder.ASCENDING),
                            fields = SlimItemFields,
                            enableUserData = true,
                            limit = BROWSE_LIMIT,
                            enableTotalRecordCount = false,
                        )
                    KidsCollection(box.name.orEmpty(), api.itemsApi.getItems(request).content.items.mapNotNull { tileOf(it) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[kids] collection %s failed", id)
                null
            }

        /** Details for a library movie or series; null = could not load. */
        suspend fun details(id: UUID): KidsDetails? =
            try {
                val dto = api.userLibraryApi.getItem(id).content
                val item = BaseItem(dto)
                val art = selectJellytipCardArt(images, item, 1280, 520).baseUrl
                when (dto.type) {
                    BaseItemKind.SERIES -> seriesDetails(dto, art)
                    // An episode opens its series' page (the page opens on the season the play button is in)
                    BaseItemKind.EPISODE -> dto.seriesId?.takeIf { it != id }?.let { details(it) }
                    BaseItemKind.MOVIE -> {
                        val resume = (dto.userData?.playbackPositionTicks ?: 0L) / 10_000L
                        KidsDetails(
                            id = dto.id.toString(),
                            isSeries = false,
                            title = dto.name.orEmpty(),
                            chips = listOfNotNull(dto.officialRating?.let { KidsChip(it, rating = true) }, kidsRuntime((dto.runTimeTicks ?: 0L) / 600_000_000L)?.let { KidsChip(it) }),
                            overview = dto.overview,
                            imageUrl = art,
                            playLabel = if (resume > 0) "Keep going!" else "Play",
                            playSub = null,
                            favourite = dto.userData?.isFavorite ?: false,
                            seasons = emptyList(),
                            initialSeason = 0,
                            playTarget = KidsPlayTarget(dto.id.toString(), resume),
                        )
                    }

                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[kids] details %s failed", id)
                null
            }

        private suspend fun seriesDetails(
            dto: BaseItemDto,
            art: String?,
        ): KidsDetails {
            val eps =
                api.tvShowsApi
                    .getEpisodes(dto.id, isMissing = false, enableUserData = true, limit = 2000)
                    .content.items
            val bySeason = eps.groupBy { it.parentIndexNumber ?: 0 }.toSortedMap()
            val seasons =
                bySeason.map { (n, list) ->
                    KidsSeason(
                        number = n,
                        label = if (n == 0) "Extras" else "Season $n",
                        episodes =
                            list.sortedBy { it.indexNumber ?: Int.MAX_VALUE }.mapIndexed { i, e ->
                                val pct = e.userData?.playedPercentage ?: 0.0
                                KidsEpisode(
                                    id = e.id.toString(),
                                    number = e.indexNumber ?: (i + 1),
                                    title = e.name.orEmpty(),
                                    imageUrl = selectJellytipCardArt(images, BaseItem(e), 320, 180).baseUrl,
                                    progress = if (pct > 0.0 && pct < 100.0) (pct / 100.0).toFloat() else 0f,
                                    played = e.userData?.played ?: false,
                                )
                            },
                    )
                }
            val pick =
                KidsPlayPick.pick(
                    eps.map {
                        KidsPlayPick.Ep(
                            id = it.id.toString(),
                            season = it.parentIndexNumber ?: 0,
                            number = it.indexNumber ?: 0,
                            played = it.userData?.played ?: false,
                            resumeMs = (it.userData?.playbackPositionTicks ?: 0L) / 10_000L,
                        )
                    },
                )
            val pickedSeason = pick?.episode?.season
            val realSeasons = seasons.count { it.number != 0 }
            return KidsDetails(
                id = dto.id.toString(),
                isSeries = true,
                title = dto.name.orEmpty(),
                chips =
                    listOfNotNull(
                        dto.officialRating?.let { KidsChip(it, rating = true) },
                        if (realSeasons > 0) KidsChip(if (realSeasons == 1) "1 season" else "$realSeasons seasons") else null,
                        kidsRuntime((eps.firstOrNull()?.runTimeTicks ?: 0L) / 600_000_000L)?.let { KidsChip(it) },
                    ),
                overview = dto.overview,
                imageUrl = art,
                playLabel = if (pick?.resume == true) "Keep going!" else "Play",
                playSub = pick?.let { p -> if (p.episode.season > 0) "Season ${p.episode.season} · Episode ${p.episode.number}" else "Episode ${p.episode.number}" },
                favourite = dto.userData?.isFavorite ?: false,
                seasons = seasons,
                initialSeason = seasons.indexOfFirst { it.number == pickedSeason }.coerceAtLeast(0),
                playTarget = pick?.let { KidsPlayTarget(it.episode.id, if (it.resume) it.episode.resumeMs else 0L) },
            )
        }

        /** The play target for a library series (hero Play on a favourite pick that is a series); null = could not tell in time. */
        suspend fun seriesPlayTarget(seriesId: UUID): KidsPlayTarget? =
            withTimeoutOrNull(PLAY_LOOKUP_MS) {
                try {
                    val eps = api.tvShowsApi.getEpisodes(seriesId, isMissing = false, enableUserData = true, limit = 2000).content.items
                    KidsPlayPick
                        .pick(
                            eps.map {
                                KidsPlayPick.Ep(it.id.toString(), it.parentIndexNumber ?: 0, it.indexNumber ?: 0, it.userData?.played ?: false, (it.userData?.playbackPositionTicks ?: 0L) / 10_000L)
                            },
                        )?.let { KidsPlayTarget(it.episode.id, if (it.resume) it.episode.resumeMs else 0L) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "[kids] series play target failed")
                    null
                }
            }

        /** Resume position (ms) of a library item, 0 when none or when the GET failed or was slow (the tap must still start a play). */
        suspend fun resumeMs(id: UUID): Long = withTimeoutOrNull(PLAY_LOOKUP_MS) { jellytipApi.libraryResumeMsOrNull(id) } ?: 0L

        suspend fun setFavourite(
            id: UUID,
            favourite: Boolean,
        ): Boolean =
            try {
                favourites.setFavorite(id, favourite)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "[kids] favourite failed")
                false
            }

        companion object {
            const val BROWSE_LIMIT = 300
            const val MY_LIST_LIMIT = 200

            /** Inside JellytipNavGate's 3 s window after the OK press (see pattern-jellytip-navigate-before-play). */
            const val PLAY_LOOKUP_MS = 1_500L
        }
    }
