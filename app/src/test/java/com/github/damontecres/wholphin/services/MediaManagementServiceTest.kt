package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ServerUserConfig
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.successResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.operations.LibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserPolicy
import com.github.damontecres.wholphin.jellytip.delete.JellytipDeleteResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class MediaManagementServiceTest {
    private val testDispatcher = StandardTestDispatcher()

    private val mockContext = mockk<Context>()
    private val mockApiClient = mockk<ApiClient>()
    private val mockServerRepository = mockk<ServerRepository>()
    private val mockUserPreferencesService = mockk<UserPreferencesService>()

    private val mockLibraryApi = mockk<LibraryApi>()

    private val mockJellytipApi = mockk<com.github.damontecres.wholphin.jellytip.JellytipApi>(relaxed = true)

    private val mediaManagementService =
        MediaManagementService(mockContext, mockApiClient, mockServerRepository, mockUserPreferencesService, mockJellytipApi)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        every { mockApiClient.libraryApi } returns mockLibraryApi
        // Jellytip: delete is admin-only; tests act as an administrator
        com.github.damontecres.wholphin.jellytip.JellytipAdmin.set(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        com.github.damontecres.wholphin.jellytip.JellytipAdmin.set(false)
        Dispatchers.resetMain()
    }

    private val movie =
        BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.MOVIE,
                canDelete = true,
            ),
        )
    private val recording =
        BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.RECORDING,
                canDelete = true,
            ),
        )

    private val movieCannotDelete =
        BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.MOVIE,
                canDelete = false,
            ),
        )

    private val userWithLiveTv =
        ServerUserConfig(
            id = UUID.randomUUID(),
            policy =
                mockk<UserPolicy> {
                    every { enableLiveTvManagement } returns true
                },
        )

    private val userWithoutLiveTv =
        ServerUserConfig(
            id = UUID.randomUUID(),
            policy =
                mockk<UserPolicy> {
                    every { enableLiveTvManagement } returns false
                },
        )

    private val prefsEnabled =
        AppPreferences
            .newBuilder()
            .apply {
                jellytipDisableMediaManagement = false
            }.build()

    private val prefsDisabled =
        AppPreferences
            .newBuilder()
            .apply {
                jellytipDisableMediaManagement = true
            }.build()

    @Test
    fun `Test canDelete`() {
        Assert.assertTrue(mediaManagementService.canDelete(movie, prefsEnabled))
        Assert.assertFalse(mediaManagementService.canDelete(movie, prefsDisabled))
        Assert.assertFalse(mediaManagementService.canDelete(movieCannotDelete, prefsEnabled))
    }

    @Test
    fun `Test canDelete non-admin`() {
        com.github.damontecres.wholphin.jellytip.JellytipAdmin.set(false)
        Assert.assertFalse(mediaManagementService.canDelete(movie, prefsEnabled))
    }

    @Test
    fun `Test canDelete recording`() {
        every { mockServerRepository.currentUserDto } returns userWithLiveTv
        Assert.assertTrue(mediaManagementService.canDelete(recording, prefsEnabled))
        Assert.assertFalse(mediaManagementService.canDelete(recording, prefsDisabled))
    }

    @Test
    fun `Test canDelete recording no permission`() {
        every { mockServerRepository.currentUserDto } returns userWithoutLiveTv
        Assert.assertFalse(mediaManagementService.canDelete(recording, prefsEnabled))
        Assert.assertFalse(mediaManagementService.canDelete(recording, prefsDisabled))
    }

    // Jellytip: deletes go through the companion (Radarr/Sonarr aware); plain Jellyfin delete only when the route is missing (404)
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test successful delete`() =
        runTest {
            coEvery { mockJellytipApi.deleteLibraryItem(any()) } returns JellytipDeleteResult(true, null)

            val deletedItems = mutableListOf<DeletedItem>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                mediaManagementService.deletedItemFlow.toList(deletedItems)
            }
            val result = mediaManagementService.deleteItem(movie)
            Assert.assertTrue(result is DeleteResult.Success)
            coVerify(exactly = 1) { mockJellytipApi.deleteLibraryItem(movie.id) }
            coVerify(exactly = 0) { mockLibraryApi.deleteItem(any()) }
            Assert.assertEquals(1, deletedItems.size)
            Assert.assertEquals(deletedItems[0].item.id, movie.id)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test delete falls back to Jellyfin when companion route is missing`() =
        runTest {
            coEvery { mockJellytipApi.deleteLibraryItem(any()) } returns JellytipDeleteResult(false, null, unsupported = true)
            coEvery { mockLibraryApi.deleteItem(any()) } returns successResponse(Unit)

            val result = mediaManagementService.deleteItem(movie)
            Assert.assertTrue(result is DeleteResult.Success)
            coVerify(exactly = 1) { mockLibraryApi.deleteItem(movie.id) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test unsuccessful delete`() =
        runTest {
            coEvery { mockJellytipApi.deleteLibraryItem(any()) } returns JellytipDeleteResult(false, "You are not allowed to delete this.")

            val deletedItems = mutableListOf<DeletedItem>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                mediaManagementService.deletedItemFlow.toList(deletedItems)
            }
            val result = mediaManagementService.deleteItem(movie)
            Assert.assertTrue(result is DeleteResult.Error)
            coVerify(exactly = 0) { mockLibraryApi.deleteItem(any()) }
            Assert.assertEquals(0, deletedItems.size)
        }
}
