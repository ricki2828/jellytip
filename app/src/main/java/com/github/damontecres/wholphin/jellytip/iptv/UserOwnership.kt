package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.JellytipApi

/**
 * Per-person answers vs user switches. Keyed like [JellytipApi.currentUserKey] (the signed-in token's hash, "" when
 * signed out). Rules, for every holder of per-person state:
 * - every user change (switchUser, including the A -> null -> B hop NavDrawerService makes) clears ALL of it;
 * - an answer is written only if the token didn't change while it was in flight ([still]); the token moves before the
 *   switch is announced (ServerRepository.changeUser), so an answer fetched in that gap is the new person's and is kept
 *   until the switch clears it;
 * - whatever a screen was using is fetched again right after the switch (no list left empty until the next visit).
 */
class UserOwnership(
    private val who: () -> String,
) {
    /** Call before the request; pass the result to [still] before writing the answer. */
    fun start(): String = who()

    /** The person signed in now is the one the answer was fetched for. */
    fun still(startedAs: String): Boolean = startedAs == who()

    companion object {
        /** The app's signed-in person, from the shared companion client. */
        fun app() = UserOwnership { JellytipApi.shared?.currentUserKey().orEmpty() }
    }
}
