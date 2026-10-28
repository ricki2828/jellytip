package com.github.damontecres.wholphin.jellytip.home

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.cache.jellytipBackdropUrl
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class JellytipHomeViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val trailerService: TrailerService,
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

        /** Single Jellyfin backdrop capped at 1920 wide; episodes and seasons use their series' backdrop. */
        fun backdropUrl(item: BaseItem): String? = jellytipBackdropUrl(imageUrlService, item)

        /** Stream URL of the first local trailer, or null when there is none. Cheap no-op without LocalTrailerCount. */
        suspend fun trailerUrl(item: BaseItem): String? {
            if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.SERIES) return null
            if ((item.data.localTrailerCount ?: 0) <= 0) return null
            return try {
                trailerService
                    .getLocalTrailers(item)
                    .filterIsInstance<LocalTrailer>()
                    .firstOrNull()
                    ?.let { api.videosApi.getVideoStreamUrl(itemId = it.baseItem.id, static = true) }
            } catch (e: Exception) {
                Timber.w(e, "Home trailer lookup failed")
                null
            }
        }
    }
