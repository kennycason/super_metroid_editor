package com.supermetroid.editor.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Semantic color tokens shared across the editor UI.
 * These are colors that don't change with the theme, typically
 * matching ROM rendering colors or domain-specific visuals.
 */
object EditorColors {
    /** Dark background matching ROM tile/map rendering (ARGB 0xFF0C0C18). */
    val romBackground = Color(0xFF0C0C18)

    /** Emulator panel background. */
    val emulatorPanelBg = Color(0xFF0B0F12)

    /** Emulator panel border. */
    val emulatorBorder = Color(0xFF2D3942)

    /** Emulator secondary text. */
    val emulatorText = Color(0xFFB6C3CC)

    /** Zoom scroll factor per notch. */
    const val ZOOM_FACTOR = 1.15f
}

/** Available editor themes. */
enum class EditorTheme(val displayName: String) {
    DARK("Dark"),
    LIGHT("Light");

    val colorScheme: ColorScheme
        get() = when (this) {
            DARK -> darkColorScheme(
                primary = Color(0xFF90CAF9),
                onPrimary = Color(0xFF003258),
                primaryContainer = Color(0xFF1E3A5F),
                onPrimaryContainer = Color(0xFFBBDEFB),
                secondary = Color(0xFF80CBC4),
                onSecondary = Color(0xFF003731),
                secondaryContainer = Color(0xFF1B3B38),
                onSecondaryContainer = Color(0xFFB2DFDB),
                background = Color(0xFF1A1A1A),
                onBackground = Color(0xFFE0E0E0),
                surface = Color(0xFF222222),
                onSurface = Color(0xFFE0E0E0),
                surfaceVariant = Color(0xFF2D2D2D),
                onSurfaceVariant = Color(0xFFBDBDBD),
                inverseSurface = Color(0xFFE0E0E0),
                inverseOnSurface = Color(0xFF1A1A1A),
                error = Color(0xFFEF9A9A),
                onError = Color(0xFF601010),
                outline = Color(0xFF444444),
                outlineVariant = Color(0xFF333333),
            )
            LIGHT -> lightColorScheme(
                primary = Color(0xFF1565C0),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFD6E4FF),
                onPrimaryContainer = Color(0xFF0D47A1),
                secondary = Color(0xFF00897B),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFB2DFDB),
                onSecondaryContainer = Color(0xFF004D40),
                background = Color(0xFFF5F5F5),
                onBackground = Color(0xFF212121),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF212121),
                surfaceVariant = Color(0xFFE8E8E8),
                onSurfaceVariant = Color(0xFF616161),
                error = Color(0xFFC62828),
                onError = Color(0xFFFFFFFF),
                outline = Color(0xFFBDBDBD),
                outlineVariant = Color(0xFFDDDDDD),
            )
        }
}

/** Available font size presets. */
enum class FontSize(
    val displayName: String,
    val tabLabel: TextUnit,
    val body: TextUnit,
    val detail: TextUnit,
    val heading: TextUnit,
    /** Prominent page/card title that still follows the global font-size preset. */
    val display: TextUnit,
    val statusBar: TextUnit,
) {
    SMALL("Small", tabLabel = 10.sp, body = 11.sp, detail = 9.sp, heading = 13.sp, display = 17.sp, statusBar = 8.sp),
    MEDIUM("Medium", tabLabel = 11.sp, body = 12.sp, detail = 10.sp, heading = 14.sp, display = 20.sp, statusBar = 9.sp),
    LARGE("Large", tabLabel = 13.sp, body = 14.sp, detail = 12.sp, heading = 16.sp, display = 24.sp, statusBar = 11.sp),
    LARGER("Larger", tabLabel = 15.sp, body = 16.sp, detail = 14.sp, heading = 19.sp, display = 28.sp, statusBar = 13.sp);
}

/** Material defaults for controls that do not specify one of SMEDIT's semantic sizes explicitly. */
fun FontSize.materialTypography(): Typography {
    val base = Typography()
    fun TextStyle.at(size: TextUnit, lineHeightScale: Float = 1.3f): TextStyle =
        copy(fontSize = size, lineHeight = size * lineHeightScale)

    return base.copy(
        displayLarge = base.displayLarge.at(display, 1.2f),
        displayMedium = base.displayMedium.at(display, 1.2f),
        displaySmall = base.displaySmall.at(display, 1.2f),
        headlineLarge = base.headlineLarge.at(display, 1.2f),
        headlineMedium = base.headlineMedium.at(heading),
        headlineSmall = base.headlineSmall.at(heading),
        titleLarge = base.titleLarge.at(display, 1.2f),
        titleMedium = base.titleMedium.at(heading),
        titleSmall = base.titleSmall.at(body),
        bodyLarge = base.bodyLarge.at(body),
        bodyMedium = base.bodyMedium.at(body),
        bodySmall = base.bodySmall.at(detail),
        labelLarge = base.labelLarge.at(body),
        labelMedium = base.labelMedium.at(detail),
        labelSmall = base.labelSmall.at(statusBar),
    )
}

/** Holds the current theme + font size; observed by Compose. */
class EditorThemeState {
    var theme = mutableStateOf(EditorTheme.DARK)
    var fontSize = mutableStateOf(FontSize.MEDIUM)
}

/** CompositionLocal so any composable can access the theme state. */
val LocalEditorTheme = compositionLocalOf { EditorThemeState() }
