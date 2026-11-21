package com.github.damontecres.wholphin.jellytip.journeys

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.home.JellytipCarouselService
import com.github.damontecres.wholphin.jellytip.rows.HubParse
import com.github.damontecres.wholphin.jellytip.rows.HubSection
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsParser
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Journey data from existing APIs only (web api/journeys.ts): the companion collection page (franchise), Jellyfin Items
 * with PersonTypes=Director (one bounded call, never one per person), the carousels manifest (Letterboxd), and the
 * collections hub (`GET /v1/collections`) for the journeys page. Build results are cached per person for 10 min (errors
 * are not); watched state is fetched fresh on each visit (one bounded Ids call).
 */
@Singleton
class JellytipJourneysService
    @Inject
    constructor(
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val serverRepository: ServerRepository,
        private val carousels: JellytipCarouselService,
    ) {
        private val cache = JourneyCache()

        private fun user() = serverRepository.currentUser?.id

        private val deps =
            object : JourneyDeps {
                override suspend fun collectionPage(tmdbId: Int): FranchisePage? {
                    // Only an explicit 404 is "no such franchise"; no answer or another status is a load failure (Retry)
                    val r = jellytipApi.userCall("v1/collections/$tmdbId", timeoutMs = 15_000) ?: throw IOException("collection $tmdbId: no answer")
                    if (Journeys.isNotFoundStatus(r.first)) return null
                    if (r.first != 200) throw IOException("collection $tmdbId: ${r.first}")
                    val c = JellytipRowsParser.collection(JSONObject(r.second)) ?: throw IOException("collection $tmdbId: unreadable")
                    return FranchisePage(c.name, c.parts.map { JourneyPart(it.itemId?.let { id -> Journeys.norm(id.toString()) }, it.title, it.year) })
                }

                override suspend fun directorFilms(personId: String): Pair<List<JourneyItem>, Int?> {
                    val page =
                        api.itemsApi
                            .getItems(
                                GetItemsRequest(
                                    userId = user(),
                                    personIds = listOf(personId.toUUID()),
                                    personTypes = listOf("Director"),
                                    recursive = true,
                                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                                    sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                                    sortOrder = listOf(SortOrder.ASCENDING),
                                    limit = Journeys.DIRECTOR_QUERY_LIMIT,
                                    enableTotalRecordCount = true,
                                    enableImages = false,
                                ),
                            ).content
                    return page.items.map { it.journeyItem() } to page.totalRecordCount
                }

                override suspend fun personName(personId: String): String =
                    api.userLibraryApi
                        .getItem(personId.toUUID())
                        .content.name
                        .orEmpty()

                override suspend fun list(slug: String): JourneyList? {
                    val m = carousels.manifest() ?: throw IOException("carousels manifest unavailable")
                    return m.letterboxd.firstOrNull { it.slug == slug }?.let { l -> JourneyList(l.slug, l.name, l.itemIds.map { Journeys.norm(it.toString()) }) }
                }
            }

        /** The journeys page: null = nothing could be loaded (hub and manifest both failed). */
        suspend fun groups(): JourneyGroups? {
            val hub: List<HubSection>? =
                runCatching {
                    jellytipApi
                        .userCall("v1/collections", timeoutMs = 10_000)
                        ?.takeIf { it.first == 200 }
                        ?.let { HubParse.sections(it.second) }
                }.getOrNull()
            val lists =
                runCatching { carousels.manifest() }
                    .getOrNull()
                    ?.letterboxd
                    ?.map { l -> JourneyList(l.slug, l.name, l.itemIds.map { Journeys.norm(it.toString()) }) }
            if (hub == null && lists == null) return null
            return Journeys.summaries(hub, lists)
        }

        suspend fun result(id: String): JourneyResult = cache.get(user()?.toString().orEmpty(), id) { Journeys.build(id, deps) }

        /** The path with fresh watched state: one Items call by Ids (no type filter: whatever the library holds the film as). */
        suspend fun entries(def: JourneyDef): List<JourneyEntry> {
            val ids = def.parts.mapNotNull { it.itemId }.distinct()
            if (ids.isEmpty()) return Journeys.buildEntries(def, emptyList())
            val page =
                api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            userId = user(),
                            ids = ids.mapNotNull { runCatching { it.toUUID() }.getOrNull() },
                            enableUserData = true,
                            enableImages = false,
                        ),
                    ).content
            return Journeys.buildEntries(def, page.items.map { it.journeyItem() })
        }

        fun clear() = cache.clear()
    }

private fun BaseItemDto.journeyItem() =
    JourneyItem(
        id = Journeys.norm(id.toString()),
        name = name,
        productionYear = productionYear,
        locationType = locationType?.serialName,
        played = userData?.played == true,
        positionTicks = userData?.playbackPositionTicks ?: 0,
    )
