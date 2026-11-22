package com.github.damontecres.wholphin.jellytip.ask

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/**
 * [AskQuery] as a Jellyfin SDK request. Lists go out as repeated query params (SDK 1.7.1 UrlBuilder), which the server's
 * pipe/comma array binders accept. Filters=IsUnplayed/IsPlayed is sent as isPlayed (the server sets the same flag).
 */
fun AskQuery.toGetItemsRequest(
    userId: UUID?,
    extraFields: List<ItemFields> = emptyList(),
): GetItemsRequest =
    GetItemsRequest(
        userId = userId,
        recursive = true,
        includeItemTypes = includeItemTypes.mapNotNull { if (it == "Movie") BaseItemKind.MOVIE else if (it == "Series") BaseItemKind.SERIES else null },
        sortBy = sortBy.mapNotNull { if (it == "Random") ItemSortBy.RANDOM else if (it == "CommunityRating") ItemSortBy.COMMUNITY_RATING else if (it == "SortName") ItemSortBy.SORT_NAME else null },
        sortOrder = sortOrder?.map { if (it == "Descending") SortOrder.DESCENDING else SortOrder.ASCENDING },
        limit = limit,
        startIndex = 0,
        fields =
            (
                fields.mapNotNull {
                    when (it) {
                        "PrimaryImageAspectRatio" -> ItemFields.PRIMARY_IMAGE_ASPECT_RATIO
                        "Genres" -> ItemFields.GENRES
                        "ChildCount" -> ItemFields.CHILD_COUNT
                        else -> null
                    }
                } + extraFields
            ).distinct(),
        genres = genres.ifEmpty { null },
        years = years.ifEmpty { null },
        isPlayed = if ("IsUnplayed" in filters) false else if ("IsPlayed" in filters) true else null,
        officialRatings = officialRatings.ifEmpty { null },
        minCommunityRating = minCommunityRating,
        personIds = personIds.mapNotNull { runCatching { it.toUUID() }.getOrNull() }.ifEmpty { null },
        enableTotalRecordCount = true,
        enableUserData = true,
    )

/** The slice of a Jellyfin item the client-side checks read. */
fun BaseItemDto.askItem(): AskItem =
    AskItem(
        type =
            when (type) {
                BaseItemKind.MOVIE -> "Movie"
                BaseItemKind.SERIES -> "Series"
                else -> type.serialName
            },
        runTimeTicks = runTimeTicks,
        childCount = childCount,
        genres = genres,
        productionYear = productionYear,
        played = userData?.played,
        people =
            people.orEmpty().mapNotNull { p ->
                val kind =
                    when (p.type) {
                        PersonKind.DIRECTOR -> "Director"
                        PersonKind.ACTOR -> "Actor"
                        else -> return@mapNotNull null
                    }
                AskPerson(p.id.toString(), p.name.orEmpty(), kind)
            },
    )
