package com.github.damontecres.wholphin.jellytip.subtitles

import android.content.Context
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface JellytipSubtitleEntryPoint {
    fun apiClient(): ApiClient

    @AuthOkHttpClient
    fun authClient(): OkHttpClient
}

/** The server said no, in a way the user can act on (permission, no providers, ...). */
class JellytipSubtitleException(
    message: String,
) : IOException(message)

/**
 * Remote subtitle search with a ~70 s timeout. Jellyfin queries every subtitle provider in turn, which can
 * take 25-60 s, longer than the shared client allows. Only this call gets the long timeout.
 */
object JellytipSubtitleSearch {
    private const val TIMEOUT_SECONDS = 70L
    private val json = Json { ignoreUnknownKeys = true }

    const val NONE_FOUND_MESSAGE = "No subtitles found — the server's subtitle providers returned nothing"

    suspend fun search(
        context: Context,
        itemId: UUID,
        language: String,
    ): List<RemoteSubtitleInfo> {
        val entry =
            EntryPointAccessors.fromApplication(context.applicationContext, JellytipSubtitleEntryPoint::class.java)
        val api = entry.apiClient()
        val client =
            entry
                .authClient()
                .newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        return withContext(WholphinDispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(api.createUrl("/Items/$itemId/RemoteSearch/Subtitles/$language"))
                    .get()
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            throw JellytipSubtitleException(
                                "The server refused the search. Turn on \"Allow subtitle management\" " +
                                    "for this user in Jellyfin.",
                            )

                        !response.isSuccessful ->
                            throw JellytipSubtitleException("Subtitle search failed (HTTP ${response.code}).")
                    }
                    json.decodeFromString(ListSerializer(RemoteSubtitleInfo.serializer()), response.body.string())
                }
            } catch (e: java.io.InterruptedIOException) {
                throw JellytipSubtitleException("The subtitle search timed out after ${TIMEOUT_SECONDS}s. Try again.")
            }
        }
    }

    /** User-facing text for a failed search. */
    fun messageFor(e: Exception): String =
        (e as? JellytipSubtitleException)?.message ?: "Subtitle search failed: ${e.localizedMessage}"
}
