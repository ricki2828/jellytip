package com.github.damontecres.wholphin.jellytip

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.ImageType
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

        /**
         * Companion stills first, then every Jellyfin backdrop at original size, then the usual fallback
         */
        fun stillUrls(
            item: BaseItem,
            extras: JellytipExtras?,
        ): List<String> {
            val companion =
                extras?.stills.orEmpty().map { it.url.replace("/t/p/original/", "/t/p/w1280/") }
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
