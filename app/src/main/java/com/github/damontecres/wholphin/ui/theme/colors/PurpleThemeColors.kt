package com.github.damontecres.wholphin.ui.theme.colors

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.ui.theme.ThemeColors

/**
 * The Jellytip default theme (still registered under the legacy PURPLE preference value).
 * Mubi-style neutrals with zero hue; jelly red is reserved for small accents.
 */
val PurpleThemeColors =
    object : ThemeColors {
        val ink = Color(0xFF0A0A0A)
        val surface1 = Color(0xFF141414)
        val surface2 = Color(0xFF1C1C1C)
        val surface3 = Color(0xFF242424)
        val divider = Color(0xFF2A2A2A)
        val text = Color(0xFFF2F2F0)
        val textSecondary = Color(0xFFA3A3A3)
        val jelly = Color(0xFFE2264D)
        val error = Color(0xFFFFB4AB)
        val onError = Color(0xFF690005)
        val errorContainer = Color(0xFF93000A)
        val onErrorContainer = Color(0xFFFFDAD6)

        // The app is dark only: the light schemes reuse the dark values so nothing off-palette can show.
        override val lightSchemeMaterial = materialScheme()
        override val darkSchemeMaterial = materialScheme()
        override val lightScheme = tvScheme()
        override val darkScheme = tvScheme()

        private fun materialScheme() =
            androidx.compose.material3.darkColorScheme(
                primary = text,
                onPrimary = ink,
                primaryContainer = divider,
                onPrimaryContainer = text,
                secondary = textSecondary,
                onSecondary = ink,
                secondaryContainer = surface2,
                onSecondaryContainer = text,
                tertiary = jelly,
                onTertiary = Color.White,
                tertiaryContainer = divider,
                onTertiaryContainer = text,
                error = error,
                onError = onError,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                background = ink,
                onBackground = text,
                surface = surface1,
                onSurface = text,
                surfaceVariant = surface2,
                onSurfaceVariant = textSecondary,
                surfaceTint = Color.Transparent,
                outline = Color(0xFF3A3A3A),
                outlineVariant = divider,
                scrim = Color.Black,
                inverseSurface = text,
                inverseOnSurface = ink,
                inversePrimary = ink,
                surfaceBright = surface3,
                surfaceDim = ink,
                surfaceContainerLowest = ink,
                surfaceContainerLow = surface1,
                surfaceContainer = surface1,
                surfaceContainerHigh = surface2,
                surfaceContainerHighest = surface3,
            )

        private fun tvScheme() =
            darkColorScheme(
                primary = text,
                onPrimary = ink,
                primaryContainer = divider,
                onPrimaryContainer = text,
                secondary = textSecondary,
                onSecondary = ink,
                secondaryContainer = surface2,
                onSecondaryContainer = text,
                tertiary = jelly,
                onTertiary = Color.White,
                tertiaryContainer = divider,
                onTertiaryContainer = text,
                error = error,
                onError = onError,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                background = ink,
                onBackground = text,
                surface = surface1,
                onSurface = text,
                surfaceVariant = surface2,
                onSurfaceVariant = textSecondary,
                scrim = Color.Black,
                inverseSurface = text,
                inverseOnSurface = ink,
                inversePrimary = ink,
                border = Color.White,
                borderVariant = divider,
            )
    }
