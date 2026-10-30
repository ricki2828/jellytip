package com.github.damontecres.wholphin.services

import androidx.navigation3.runtime.NavBackStack
import com.github.damontecres.wholphin.ui.nav.Destination
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject
import com.github.damontecres.wholphin.jellytip.debug.JellytipNavGate
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import javax.inject.Singleton

private val BROWSE_KINDS =
    setOf(
        org.jellyfin.sdk.model.api.BaseItemKind.COLLECTION_FOLDER,
        org.jellyfin.sdk.model.api.BaseItemKind.USER_VIEW,
        org.jellyfin.sdk.model.api.BaseItemKind.FOLDER,
    )

/**
 * Manages navigating between pages and manages the app's back stack
 *
 * Jellytip (30-Oct P0): this MUST be a singleton. e4946624 slipped [BROWSE_KINDS] in between the `@Singleton`
 * annotation and the class, so Hilt gave every ViewModel its own NavigationManager with its own back stack: their
 * navigations were traced but went to a list the screen never showed (live sport audio over a frozen Home).
 */
@Singleton
class NavigationManager
    @Inject
    constructor() {
        init {
            val n = instances.incrementAndGet()
            if (n > 1) {
                // Never again silently: a second instance means navigations can go to a back stack nobody shows
                JellytipTrace.log("nav", "ERROR NavigationManager instance #$n (must be a singleton)")
                Timber.e("NavigationManager instance #%d: must be a singleton", n)
            }
        }

        var backStack: MutableList<Destination> = NavBackStack(Destination.Home())

        /**
         * Go to the specified [com.github.damontecres.wholphin.ui.nav.Destination]
         */
        fun navigateTo(
            destination: Destination,
            /** Jellytip: why (key, timer, player...); traced with the call site. */
            reason: String? = null,
        ) {
            val from = backStack.lastOrNull()
            // Jellytip: from a browse page, only an explicit OK opens a title (see JellytipNavGate)
            if (!JellytipNavGate.allow(isBrowsePage(from), opensTitle(destination))) {
                JellytipTrace.nav("BLOCKED", from, destination, reason)
                Timber.w("Blocked navigation without OK: %s -> %s", from, destination)
                return
            }
            JellytipTrace.nav("to", from, destination, reason)
            backStack.add(destination)
            log()
        }

        private fun isBrowsePage(d: Destination?): Boolean =
            d is Destination.Home ||
                d is Destination.Favorites ||
                d is Destination.Discover ||
                d is Destination.JellytipLiveTv ||
                (d is Destination.MediaItem && d.type in BROWSE_KINDS)

        private fun opensTitle(d: Destination): Boolean =
            when (d) {
                is Destination.MediaItem -> d.type !in BROWSE_KINDS
                is Destination.SeriesOverview,
                is Destination.Playback,
                is Destination.PlaybackList,
                is Destination.JellytipCollection,
                is Destination.JellytipLiveTvPlayer,
                is Destination.JellytipIptvPlay,
                is Destination.JellytipIptvDetails,
                is Destination.JellytipDiscover,
                is Destination.DiscoveredItem,
                -> true

                else -> false
            }

        /**
         * Go to the specified [Destination], but reset the back stack to Home first
         */
        fun navigateToFromDrawer(destination: Destination) {
            JellytipTrace.nav("drawer", backStack.lastOrNull(), destination)
            goToHome()
            backStack.add(destination)
            log()
        }

        /**
         * Go to the previous page
         */
        fun goBack() {
            JellytipTrace.nav("back", backStack.lastOrNull(), backStack.getOrNull(backStack.size - 2))
            synchronized(this) {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            }
            log()
        }

        /**
         * Go all the way back to the home page
         */
        fun goToHome() {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack[0] !is Destination.Home) {
                backStack[0] = Destination.Home()
            }
            log()
        }

        /**
         * Go all the way back to the home page, and reload it from scratch
         */
        fun reloadHome() {
            goToHome()
            val id = (backStack[0] as Destination.Home).id + 1
            backStack[0] = Destination.Home(id)
            log()
        }

        /**
         * Resets the backstack to the specified destination
         */
        fun replace(destination: Destination) {
            JellytipTrace.nav("replace", backStack.lastOrNull(), destination)
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack.isEmpty()) {
                backStack.add(0, destination)
            } else {
                backStack[0] = destination
            }
            log()
        }

        companion object {
            private val instances = java.util.concurrent.atomic.AtomicInteger(0)
        }

        private fun log() {
            val dest = backStack.lastOrNull().toString()
            Timber.i("Current Destination: %s", dest)
            ACRA.errorReporter.putCustomData("destination", dest)
        }
    }
