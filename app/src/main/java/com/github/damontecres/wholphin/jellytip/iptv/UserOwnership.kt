package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.JellytipApi

/**
 * Whose answer a per-person cache holds, keyed like [JellytipApi.currentUserKey] (the signed-in token's hash, "" when
 * signed out). The token moves to the new person before the user switch is announced (ServerRepository.changeUser:
 * token, a few calls, then the current user), so a generation bumped at the switch would drop the new person's own
 * answer fetched in that gap. Owning by key instead: an answer is kept only if the person didn't change while it was
 * in flight ([accept]), and a switch clears only what belongs to someone else ([mustClear]).
 */
class UserOwnership(
    private val who: () -> String,
) {
    @Volatile
    var owner: String? = null
        private set

    /** Call before the request; pass the result to [accept]. */
    fun start(): String = who()

    /** The answer fetched as [startedAs] is still for the person signed in now: keep it (and own it). */
    fun accept(startedAs: String): Boolean {
        if (startedAs != who()) return false
        owner = startedAs
        return true
    }

    /** On a user switch: true (and nothing owned any more) when what's held is someone else's, or nobody is signed in. */
    fun mustClear(signedOut: Boolean): Boolean {
        val clear = signedOut || (owner != null && owner != who())
        if (clear) owner = null
        return clear
    }

    companion object {
        /** The app's signed-in person, from the shared companion client. */
        fun app() = UserOwnership { JellytipApi.shared?.currentUserKey().orEmpty() }
    }
}
