package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.staticCompositionLocalOf
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.jellytip.cache.ImagePick
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * What the details composables ([JellytipDetailsBlock], [JellytipDetailsBackground], [JellytipScoreRow]) read from
 * [JellytipDetailsViewModel]. A seam so a test can provide a fake through [LocalJellytipDetailsSource] without Hilt.
 */
interface JellytipDetailsSource {
    val settings: Flow<JellytipSettings>
    val httpClient: OkHttpClient

    suspend fun getExtras(item: BaseItem): JellytipExtras?

    suspend fun getAudience(item: BaseItem): JellytipAudience?

    suspend fun detailsPick(item: BaseItem): ImagePick?

    fun cachedDetailsPick(item: BaseItem): ImagePick?

    fun stillChoice(id: UUID): Flow<Int>

    fun stillUrls(
        item: BaseItem,
        extras: JellytipExtras?,
    ): List<String>

    fun trailerUrl(trailer: LocalTrailer): String?
}

/** Null in production (the composables fall back to the Hilt view model); tests provide a fake. */
val LocalJellytipDetailsSource = staticCompositionLocalOf<JellytipDetailsSource?> { null }
