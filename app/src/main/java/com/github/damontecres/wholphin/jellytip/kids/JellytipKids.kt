package com.github.damontecres.wholphin.jellytip.kids

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserPolicy

/**
 * Whether the signed-in Jellyfin user is a kid, so the app shows the Kids shell instead of the adult one.
 *
 * One rule, the same as the server's `iptv/kids.py KidsPolicy.from_jellyfin`: a kid has `MaxParentalRating` set (0 = G cap
 * IS a kid: a null check, never truthiness) OR non-empty `BlockedTags` OR non-empty `BlockUnratedItems` (a LIST of item
 * types, e.g. ["Movie"]). `EnableAllFolders = false` alone is an adult. A missing policy is UNKNOWN: the adult shell (the
 * server still filters content for that token, so safety is unaffected) and a trace line.
 *
 * Set from the signed-in user's policy in ServerRepository, cleared on EVERY user change (A -> null -> B is normal).
 */
object JellytipKids {
    private val state = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> get() = state

    /** Non-composable check, e.g. in view models. */
    val active: Boolean get() = state.value

    /** The rule on plain values (unit tested): null / empty collections are "not set". */
    fun isKid(
        maxParentalRating: Int?,
        blockedTags: Collection<*>?,
        blockUnratedItems: Collection<*>?,
    ): Boolean = maxParentalRating != null || !blockedTags.isNullOrEmpty() || !blockUnratedItems.isNullOrEmpty()

    /** The rule on a Jellyfin policy; null = unknown = not a kid. */
    fun isKid(policy: UserPolicy?): Boolean = policy != null && isKid(policy.maxParentalRating, policy.blockedTags, policy.blockUnratedItems)

    /**
     * The signed-in user changed (or was refreshed): [policy] null = unknown. A new user ([newUser] true) also resets the Kids
     * section to Home; a refresh of the same user's settings keeps the page where it is.
     */
    fun set(
        policy: UserPolicy?,
        newUser: Boolean = true,
    ) {
        if (policy == null) JellytipTrace.log("kids", "[kids] policy unknown")
        val kid = isKid(policy)
        if (state.value != kid) JellytipTrace.log("kids", "shell=${if (kid) "kids" else "adult"}")
        if (newUser) KidsSectionState.reset()
        state.value = kid
    }

    /** Signed out / switching user: back to "not a kid" until the next user's policy is known. */
    fun clear() {
        KidsSectionState.reset()
        state.value = false
    }
}

/**
 * The only full-screen destinations a kid's session opens: the library player and the IPTV film player. Settings, Now playing, Update, Slideshow and the rest never
 * open for a kid (they show the Kids Home instead).
 */
fun kidsAllowsFullScreen(destination: Destination): Boolean =
    destination is Destination.Playback ||
        destination is Destination.PlaybackList ||
        destination is Destination.JellytipIptvPlay

/** What kids Search looks for. Adults keep the full list ([types] with `kids = false` returns [all] unchanged). */
object KidsSearch {
    val TYPES: List<BaseItemKind> = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.BOX_SET)

    fun types(
        all: List<BaseItemKind>,
        kids: Boolean,
    ): List<BaseItemKind> = if (kids) all.filter { it in TYPES } else all
}

/** Composable check; recomposes if the signed-in user changes. */
@Composable
fun rememberIsJellytipKid(): State<Boolean> = JellytipKids.activeFlow.collectAsState()

/** The four Kids sections on the Home entry (Search and Details are their own destinations). */
enum class KidsSection(
    val label: String,
) {
    HOME("Home"),
    SHOWS("Shows"),
    MOVIES("Movies"),
    MY_LIST("My list"),
}

/**
 * Which section the Kids Home entry shows. Process-wide on purpose: the nav bar is also drawn over Search, and a section
 * button there must land on that section. Reset on every user change (see [JellytipKids.set]).
 */
object KidsSectionState {
    private val state = MutableStateFlow(KidsSection.HOME)
    val flow: StateFlow<KidsSection> get() = state

    fun set(section: KidsSection) {
        state.value = section
    }

    fun reset() {
        state.value = KidsSection.HOME
    }
}
