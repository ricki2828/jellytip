package com.github.damontecres.wholphin.jellytip

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppStringPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup

/**
 * Jellytip-only settings, stored in extra fields of [AppPreferences]. Booleans that default to "on"
 * are stored inverted (`*_disabled`) so the protobuf default (false) means "on".
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

    val Splash =
        AppSwitchPreference<AppPreferences>(
            title = R.string.jellytip_splash_title,
            defaultValue = true,
            getter = { !it.jellytipSplashDisabled },
            setter = { prefs, value -> prefs.update { jellytipSplashDisabled = !value } },
            summary = R.string.jellytip_splash_summary,
        )

    val SplashSound =
        AppSwitchPreference<AppPreferences>(
            title = R.string.jellytip_splash_sound_title,
            defaultValue = true,
            getter = { !it.jellytipSplashSoundDisabled },
            setter = { prefs, value -> prefs.update { jellytipSplashSoundDisabled = !value } },
            summary = R.string.jellytip_splash_sound_summary,
        )

    val Ident =
        AppSwitchPreference<AppPreferences>(
            title = R.string.jellytip_ident_title,
            defaultValue = true,
            getter = { !it.jellytipIdentDisabled },
            setter = { prefs, value -> prefs.update { jellytipIdentDisabled = !value } },
            summary = R.string.jellytip_ident_summary,
        )

    /** One "Jellytip" section, first on the main settings screen: intro/sound/ident, companion API, trailers */
    val group =
        PreferenceGroup<AppPreferences>(
            title = R.string.jellytip_settings,
            preferences =
                listOf<AppPreference<AppPreferences, out Any?>>(
                    Splash,
                    SplashSound,
                    Ident,
                    ApiUrl,
                    ApiToken,
                    TrailerAutoplay,
                    TrailerSound,
                ),
        )
}
