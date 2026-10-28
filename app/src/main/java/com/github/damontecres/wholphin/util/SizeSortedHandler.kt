package com.github.damontecres.wholphin.util

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/** A fixed, pre-sorted id list; [base] supplies fields/image types for the page fetches. */
data class SizeSortedRequest(
    val base: GetItemsRequest,
    val orderedIds: List<UUID>,
    val startIndex: Int = 0,
    val limit: Int = 100,
)

/** Pages through [SizeSortedRequest.orderedIds], fetching each page's items by id and restoring the id order. */
@Serializable
object SizeSortedItemsHandler : RequestHandler<SizeSortedRequest> {
    override fun prepare(
        request: SizeSortedRequest,
        startIndex: Int,
        limit: Int,
        enableTotalRecordCount: Boolean,
    ): SizeSortedRequest = request.copy(startIndex = startIndex, limit = limit)

    override suspend fun execute(
        api: ApiClient,
        request: SizeSortedRequest,
    ): Response<BaseItemDtoQueryResult> {
        val slice = request.orderedIds.drop(request.startIndex).take(request.limit)
        if (slice.isEmpty()) {
            return Response(
                BaseItemDtoQueryResult(items = emptyList(), totalRecordCount = request.orderedIds.size, startIndex = request.startIndex),
                200,
                emptyMap(),
            )
        }
        val fetched =
            api.itemsApi.getItems(
                request.base.copy(
                    ids = slice,
                    startIndex = null,
                    limit = null,
                    enableTotalRecordCount = false,
                    sortBy = null,
                    sortOrder = null,
                ),
            )
        val byId = fetched.content.items.associateBy { it.id }
        return Response(
            BaseItemDtoQueryResult(
                items = slice.mapNotNull { byId[it] },
                totalRecordCount = request.orderedIds.size,
                startIndex = request.startIndex,
            ),
            fetched.status,
            fetched.headers,
        )
    }
}
