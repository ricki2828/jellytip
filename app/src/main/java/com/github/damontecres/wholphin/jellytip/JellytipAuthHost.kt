package com.github.damontecres.wholphin.jellytip

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Decides whether the Jellyfin session token may be attached to a request: only to the signed-in server's own host. */
object JellytipAuthHost {
    fun matches(
        requestHost: String,
        serverUrl: String?,
    ): Boolean {
        val serverHost = serverUrl?.toHttpUrlOrNull()?.host ?: return false
        return requestHost.equals(serverHost, ignoreCase = true)
    }
}
