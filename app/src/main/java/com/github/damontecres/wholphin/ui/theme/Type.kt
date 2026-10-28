package com.github.damontecres.wholphin.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.tv.material3.Typography
import com.github.damontecres.wholphin.R

/** Instrument Sans (OFL), a close free match for Mubi's UI grotesque. Variable font, weights 400-600. */
@OptIn(ExperimentalTextApi::class)
val JellytipFontFamily =
    FontFamily(
        listOf(
            FontWeight.Normal to 400,
            FontWeight.Medium to 500,
            FontWeight.SemiBold to 600,
            FontWeight.Bold to 700,
        ).map { (weight, value) ->
            Font(
                resId = R.font.instrument_sans,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(value)),
            )
        },
    )

private fun TextStyle.jellytip(tracking: Float): TextStyle = copy(fontFamily = JellytipFontFamily, letterSpacing = tracking.em)

private val Base = Typography()

/** Display and titles are tight (-0.01em); body and labels use normal tracking. */
val AppTypography =
    Typography(
        displayLarge = Base.displayLarge.jellytip(-0.01f),
        displayMedium = Base.displayMedium.jellytip(-0.01f),
        displaySmall = Base.displaySmall.jellytip(-0.01f),
        headlineLarge = Base.headlineLarge.jellytip(-0.01f),
        headlineMedium = Base.headlineMedium.jellytip(-0.01f),
        headlineSmall = Base.headlineSmall.jellytip(-0.01f),
        titleLarge = Base.titleLarge.jellytip(-0.01f),
        titleMedium = Base.titleMedium.jellytip(-0.005f),
        titleSmall = Base.titleSmall.jellytip(0f),
        bodyLarge = Base.bodyLarge.jellytip(0f),
        bodyMedium = Base.bodyMedium.jellytip(0f),
        bodySmall = Base.bodySmall.jellytip(0f),
        labelLarge = Base.labelLarge.jellytip(0f),
        labelMedium = Base.labelMedium.jellytip(0f),
        labelSmall = Base.labelSmall.jellytip(0f),
    )

/** Material3 (non-TV) text styles used by a few dialogs get the same family. */
val AppMaterialTypography =
    androidx.compose.material3.Typography().let { t ->
        t.copy(
            displayLarge = t.displayLarge.jellytip(-0.01f),
            displayMedium = t.displayMedium.jellytip(-0.01f),
            displaySmall = t.displaySmall.jellytip(-0.01f),
            headlineLarge = t.headlineLarge.jellytip(-0.01f),
            headlineMedium = t.headlineMedium.jellytip(-0.01f),
            headlineSmall = t.headlineSmall.jellytip(-0.01f),
            titleLarge = t.titleLarge.jellytip(-0.01f),
            titleMedium = t.titleMedium.jellytip(0f),
            titleSmall = t.titleSmall.jellytip(0f),
            bodyLarge = t.bodyLarge.jellytip(0f),
            bodyMedium = t.bodyMedium.jellytip(0f),
            bodySmall = t.bodySmall.jellytip(0f),
            labelLarge = t.labelLarge.jellytip(0f),
            labelMedium = t.labelMedium.jellytip(0f),
            labelSmall = t.labelSmall.jellytip(0f),
        )
    }
