package com.github.damontecres.wholphin.jellytip.home

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.cache.ImagePick
import com.github.damontecres.wholphin.jellytip.cache.JellytipImagePick
import com.github.damontecres.wholphin.jellytip.cache.jellytipBackdropUrl
import com.github.damontecres.wholphin.jellytip.cache.jellytipHeroItemId
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
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID
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
        fun backdropUrl(
            item: BaseItem,
            heroIndex: Int? = null,
        ): String? = jellytipBackdropUrl(imageUrlService, item, heroIndex)

        /** Home hero image + focal point; [heroIndex] is the companion answer when known. */
        fun heroPick(
            item: BaseItem,
            heroIndex: Int?,
        ): ImagePick = JellytipImagePick.forHome(imageUrlService, item, heroIndex)

        /** Companion `heroIndex` for [item] (cached after the first answer); null while unknown or unreachable. */
        suspend fun heroIndex(item: BaseItem): Int? = jellytipApi.heroBackdropIndex(jellytipHeroItemId(item))

        private val trailerCache = java.util.concurrent.ConcurrentHashMap<UUID, List<Pair<String, UUID>>>() // empty list = checked, none

        /**
         * Stream URL + item id of the first local trailer, or null when there is none. Asks the server directly: list queries
         * never carry LocalTrailerCount, so gating on it (as before) meant the home hero never found a trailer.
         */
        suspend fun localTrailer(item: BaseItem): Pair<String, UUID>? {
            if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.SERIES) return null
            trailerCache[item.id]?.let { return it.firstOrNull() }
            return try {
                val trailer =
                    api.userLibraryApi
                        .getLocalTrailers(item.id)
                        .content
                        .firstOrNull()
                val found = trailer?.let { api.videosApi.getVideoStreamUrl(itemId = it.id, static = true) to it.id }
                trailerCache[item.id] = listOfNotNull(found)
                found
            } catch (e: Exception) {
                Timber.w(e, "Home trailer lookup failed")
                null
            }
        }
    }
