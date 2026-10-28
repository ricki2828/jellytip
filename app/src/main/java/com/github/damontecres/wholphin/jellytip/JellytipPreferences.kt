package com.github.damontecres.wholphin.jellytip

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppStringPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup

/**
 * Jellytip-only settings, stored in extra fields of [AppPreferences]
 */
object JellytipPreferences {
    const val DEFAULT_API_URL = "https://ricki28.palawan.usbx.me/jellytip-api"

    val ApiUrl =
        AppStringPreference<AppPreferences>(
            title = R.string.jellytip_api_url,
            defaultValue = DEFAULT_API_URL,
            getter = { it.jellytipApiUrl.ifBlank { DEFAULT_API_URL } },
            setter = { prefs, value -> prefs.update { jellytipApiUrl = value.trim() } },
            summary = null,
        )

    val ApiToken =
        AppStringPreference<AppPreferences>(
            title = R.string.jellytip_api_token,
            defaultValue = "",
            getter = { it.jellytipApiToken },
            setter = { prefs, value -> prefs.update { jellytipApiToken = value.trim() } },
            summary = R.string.jellytip_api_token_summary,
        )

    val TrailerAutoplay =
        AppSwitchPreference<AppPreferences>(
            title = R.string.jellytip_trailer_autoplay,
            defaultValue = true,
            getter = { !it.jellytipDisableTrailerAutoplay },
            setter = { prefs, value -> prefs.update { jellytipDisableTrailerAutoplay = !value } },
            summaryOn = R.string.enabled,
            summaryOff = R.string.disabled,
        )

    val TrailerSound =
        AppSwitchPreference<AppPreferences>(
            title = R.string.jellytip_trailer_sound,
            defaultValue = false,
            getter = { it.jellytipTrailerSound },
            setter = { prefs, value -> prefs.update { jellytipTrailerSound = value } },
            summaryOn = R.string.enabled,
            summaryOff = R.string.disabled,
        )

    val group =
        PreferenceGroup(
            title = R.string.jellytip_settings,
            preferences = listOf(ApiUrl, ApiToken, TrailerAutoplay, TrailerSound),
        )
}
