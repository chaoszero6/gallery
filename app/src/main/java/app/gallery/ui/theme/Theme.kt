package app.gallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours measured from Samsung Gallery screenshots (One UI), redrawn as our own tokens. */
@Immutable
data class GalleryColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val pill: Color,
    val pillSelected: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val divider: Color,
    val danger: Color,
    val scrim: Color,
    val isDark: Boolean,
)

private val Light = GalleryColors(
    background = Color(0xFFF7F7F9),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFEDEDF0),
    pill = Color(0xF2FFFFFF),
    pillSelected = Color(0xFFE6E6E9),
    text = Color(0xFF111111),
    textSecondary = Color(0xFF7B7B80),
    accent = Color(0xFF3E7BF6),
    divider = Color(0x1A000000),
    danger = Color(0xFFE5483D),
    scrim = Color(0x66000000),
    isDark = false,
)

private val Dark = GalleryColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1B1B1D),
    card = Color(0xFF242427),
    pill = Color(0xF22A2A2D),
    pillSelected = Color(0xFF3A3A3E),
    text = Color(0xFFF2F2F2),
    textSecondary = Color(0xFF9C9CA1),
    accent = Color(0xFF5B95FF),
    divider = Color(0x26FFFFFF),
    danger = Color(0xFFFF6B60),
    scrim = Color(0x99000000),
    isDark = true,
)

val LocalGalleryColors = staticCompositionLocalOf { Light }

object G {
    val colors: GalleryColors @Composable get() = LocalGalleryColors.current
    val cardRadius = 26.dp
    val pillHeight = 56.dp
    val sideMargin = 10.dp
}

/**
 * The phone's own UI font. On Samsung phones the system family "sec" is One UI Sans (what Samsung
 * Gallery uses); Compose otherwise draws Roboto. Elsewhere the name falls back to the default font.
 * Nothing is bundled: the font is read from the phone. Declared per weight, so Bold and SemiBold
 * use the font's own weights.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont: FontFamily = FontFamily((100..900 step 100).map { Font(DeviceFontFamilyName("sec"), FontWeight(it)) })

@Composable
fun GalleryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, background = c.background, surface = c.surface, onSurface = c.text, onBackground = c.text,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.card, secondaryContainer = c.pillSelected,
    ) else lightColorScheme(
        primary = c.accent, background = c.background, surface = c.surface, onSurface = c.text, onBackground = c.text,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.card, secondaryContainer = c.pillSelected,
    )
    val type = Typography().let { d ->
        // Every Material style (dialogs, menus, fields) in the system's font, not Compose's Roboto.
        Typography(
            displayLarge = d.displayLarge.copy(fontFamily = AppFont), displayMedium = d.displayMedium.copy(fontFamily = AppFont),
            displaySmall = d.displaySmall.copy(fontFamily = AppFont), headlineLarge = d.headlineLarge.copy(fontFamily = AppFont),
            headlineSmall = d.headlineSmall.copy(fontFamily = AppFont), titleSmall = d.titleSmall.copy(fontFamily = AppFont),
            bodySmall = d.bodySmall.copy(fontFamily = AppFont), labelLarge = d.labelLarge.copy(fontFamily = AppFont),
            headlineMedium = TextStyle(fontSize = 25.5.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 13.5.sp),
        bodyMedium = TextStyle(fontSize = 12.sp),
        labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 9.5.sp),
        ).let { t ->
            t.copy(
                headlineMedium = t.headlineMedium.copy(fontFamily = AppFont), titleLarge = t.titleLarge.copy(fontFamily = AppFont),
                titleMedium = t.titleMedium.copy(fontFamily = AppFont), bodyLarge = t.bodyLarge.copy(fontFamily = AppFont),
                bodyMedium = t.bodyMedium.copy(fontFamily = AppFont), labelMedium = t.labelMedium.copy(fontFamily = AppFont),
                labelSmall = t.labelSmall.copy(fontFamily = AppFont),
            )
        }
    }
    CompositionLocalProvider(LocalGalleryColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
