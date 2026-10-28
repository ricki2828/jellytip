package com.github.damontecres.wholphin.jellytip

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup

/**
 * Jellytip-only settings. They live in [AppPreferences] as inverted booleans (`*_disabled`) so the
 * protobuf default (false) means "on" without touching the serializer defaults.
 */
object JellytipPreferences {
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

    val group =
        PreferenceGroup<AppPreferences>(
            title = R.string.jellytip_settings,
            preferences = listOf<AppPreference<AppPreferences, out Any?>>(Splash, SplashSound, Ident),
        )
}
