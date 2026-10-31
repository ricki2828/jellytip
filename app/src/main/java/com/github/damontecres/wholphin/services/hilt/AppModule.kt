package com.github.damontecres.wholphin.services.hilt

import android.content.Context
import androidx.work.WorkManager
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.MostRecentServerProvider
import com.github.damontecres.wholphin.data.MostRecentServerSharedPreferences
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.util.CoroutineContextApiClientFactory
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import com.github.damontecres.wholphin.jellytip.JellytipAuthHost
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * An [OkHttpClient] that includes the user's access token when making requests
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

/**
 * A basic [OkHttpClient] that does not include auth
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardOkHttpClient

/**
 * A [CoroutineScope] with [WholphinDispatchers.IO]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoCoroutineScope

/**
 * A [CoroutineScope] with [WholphinDispatchers.Default]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultCoroutineScope

/**
 * [WholphinDispatchers.IO]
 *
 * @see IoCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * [WholphinDispatchers.Default]
 *
 * @see DefaultCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clientInfo(
        @ApplicationContext context: Context,
    ): ClientInfo =
        ClientInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
        )

    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient() =
        OkHttpClient
            .Builder()
            .apply {
                // TODO timeouts, logging, etc
                // Jellytip: the companion tells devices apart by User-Agent (TV calls were logged as "phone");
                // requests that set their own keep it
                addInterceptor { chain ->
                    val req = chain.request()
                    chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", "Jellytip-TV/${BuildConfig.VERSION_NAME}").build() else req)
                }
                // Jellytip: the seedbox is one remote host. OkHttp's default of 5 concurrent calls per host
                // (HTTP/2 included) would serialise home metadata behind image prefetches.
                dispatcher(
                    Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 16
                    },
                )
                connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            }.build()

    @AuthOkHttpClient
    @Provides
    @Singleton
    fun authOkHttpClient(
        serverRepository: ServerRepository,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) = okHttpClient
        .newBuilder()
        .addInterceptor {
            val request = it.request()
            val serverUrl = serverRepository.current.value?.server?.url
            val newRequest =
                serverRepository.current.value?.user?.accessToken?.takeIf { _ ->
                    JellytipAuthHost.matches(request.url.host, serverUrl)
                }?.let { token ->
                    request
                        .newBuilder()
                        .addHeader(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = clientInfo.name,
                                clientVersion = clientInfo.version,
                                deviceId = deviceInfo.id,
                                deviceName = deviceInfo.name,
                                accessToken = token,
                            ),
                        ).build()
                }
            it.proceed(newRequest ?: request)
        }.build()

    @Provides
    @Singleton
    fun okHttpFactory(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = CoroutineContextApiClientFactory(OkHttpFactory(okHttpClient))

    @Provides
    @Singleton
    fun jellyfin(
        okHttpFactory: CoroutineContextApiClientFactory,
        @ApplicationContext context: Context,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ): Jellyfin =
        createJellyfin {
            this.context = context
            this.clientInfo = clientInfo
            this.deviceInfo = deviceInfo
            apiClientFactory = okHttpFactory
            socketConnectionFactory = okHttpFactory
            minimumServerVersion = Jellyfin.minimumVersion
        }

    @Provides
    @Singleton
    fun apiClient(jellyfin: Jellyfin) = jellyfin.createApi()

    @Provides
    @Singleton
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = WholphinDispatchers.IO

    @Provides
    @Singleton
    @IoCoroutineScope
    fun ioCoroutineScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = WholphinDispatchers.Default

    @Provides
    @Singleton
    @DefaultCoroutineScope
    fun defaultCoroutineScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun seerrApi(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = SeerrApi(okHttpClient)

    @Provides
    @Singleton
    fun mostRecentServerProvider(
        @ApplicationContext context: Context,
    ): MostRecentServerProvider = MostRecentServerSharedPreferences(context)
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun deviceInfo(
        @ApplicationContext context: Context,
    ): DeviceInfo = androidDevice(context)
}
