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
    val pillHeight = 64.dp
    val sideMargin = 10.dp
}

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
    val type = Typography(
        headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp),
    )
    CompositionLocalProvider(LocalGalleryColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
