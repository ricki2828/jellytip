package com.github.damontecres.wholphin.jellytip

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import javax.inject.Inject

data class JellytipSettings(
    val trailerAutoplay: Boolean = true,
    val trailerSound: Boolean = false,
)

@HiltViewModel
class JellytipDetailsViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val imageUrlService: ImageUrlService,
        private val stillStore: JellytipStillStore,
        preferences: DataStore<AppPreferences>,
        @param:AuthOkHttpClient val httpClient: OkHttpClient,
    ) : ViewModel() {
        val settings: Flow<JellytipSettings> =
            preferences.data
                .map {
                    JellytipSettings(
                        trailerAutoplay = !it.jellytipDisableTrailerAutoplay,
                        trailerSound = it.jellytipTrailerSound,
                    )
                }.distinctUntilChanged()

        suspend fun getExtras(item: BaseItem): JellytipExtras? = jellytipApi.getExtras(item)

        suspend fun getAudience(item: BaseItem): JellytipAudience? = jellytipApi.getAudience(item)

        fun stillChoice(id: UUID): Flow<Int> = stillStore.choice(id)

        /** "Change still": advance to the next still (or back to automatic) for this item, locally only. */
        fun cycleStill(item: BaseItem) {
            viewModelScope.launch { stillStore.cycle(item.id) }
        }

        /**
         * Companion stills first, then every Jellyfin backdrop at original size, then the usual fallback
         */
        fun stillUrls(
            item: BaseItem,
            extras: JellytipExtras?,
        ): List<String> {
            // Textless stills (no language) first; sortedBy is stable so the service's ranking holds within each group.
            // TMDB "original" can be 4K; w1280 is plenty for a 1080p TV and much cheaper to decode on the Chromecast.
            val companion =
                extras?.stills.orEmpty()
                    .sortedBy { if (it.lang == null) 0 else 1 }
                    .map { it.url.replace("/t/p/original/", "/t/p/w1280/") }
            val tags = item.data.backdropImageTags.orEmpty()
            val jellyfin =
                tags.mapIndexedNotNull { index, tag ->
                    imageUrlService.getItemImageUrl(
                        itemId = item.id,
                        imageType = ImageType.BACKDROP,
                        maxWidth = MAX_WIDTH,
                        tag = tag,
                        imageIndex = index,
                    )
                }
            val fallback =
                if (companion.isEmpty() && jellyfin.isEmpty()) {
                    listOfNotNull(imageUrlService.getItemImageUrl(item, ImageType.BACKDROP))
                } else {
                    emptyList()
                }
            return (companion + jellyfin + fallback).distinct()
        }

        fun trailerUrl(trailer: LocalTrailer): String? =
            try {
                api.videosApi.getVideoStreamUrl(itemId = trailer.baseItem.id, static = true)
            } catch (e: Exception) {
                null
            }

        companion object {
            private const val MAX_WIDTH = 1920
        }
    }
